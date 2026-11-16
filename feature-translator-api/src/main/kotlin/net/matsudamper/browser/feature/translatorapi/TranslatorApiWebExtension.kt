package net.matsudamper.browser.feature.translatorapi

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension

/**
 * Translator and Language Detector APIs
 * (https://developer.mozilla.org/en-US/docs/Web/API/Translator_and_Language_Detector_APIs) のポリフィル用ビルトイン WebExtension。
 * ページ側ポリフィルが送る要求を受け取り、[TranslatorApiEngine] の結果を返す。
 *
 * @param selectAppEngine 翻訳に使うアプリ側のエンジンを返す。
 * null を返すと、拡張機能自身が Gecko 内蔵の翻訳エンジンで処理する
 */
class TranslatorApiWebExtension(
    private val selectAppEngine: suspend () -> TranslatorApiEngine?,
) {
    private var extension: WebExtension? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val registeredSessions: MutableSet<GeckoSession> =
        Collections.newSetFromMap(ConcurrentHashMap())

    // ページを閉じた後に翻訳待ちの要求が残り、後続ページの翻訳を待たせないよう、セッション単位で取り消す
    private val sessionRequestJobs = ConcurrentHashMap<GeckoSession, Job>()

    fun install(runtime: GeckoRuntime) {
        Log.d(TAG, "install() 開始: uri=$EXTENSION_URI")
        runtime.webExtensionController
            .installBuiltIn(EXTENSION_URI)
            .accept(
                { ext ->
                    Log.d(TAG, "インストール完了: id=${ext?.id} version=${ext?.metaData?.version}")
                    if (ext == null) return@accept
                    extension = ext
                    registeredSessions.forEach { session ->
                        attachSessionMessageDelegate(session, ext)
                    }
                },
                { error ->
                    Log.e(TAG, "インストール失敗", error)
                },
            )
    }

    fun registerSession(session: GeckoSession) {
        registeredSessions.add(session)
        sessionRequestJobs.computeIfAbsent(session) { SupervisorJob(coroutineScope.coroutineContext[Job]) }
        val extension = extension ?: return
        attachSessionMessageDelegate(session, extension)
    }

    fun unregisterSession(session: GeckoSession) {
        registeredSessions.remove(session)
        sessionRequestJobs.remove(session)?.cancel()
        val extension = extension ?: return
        session.webExtensionController.setMessageDelegate(extension, null, NATIVE_APP_ID)
    }

    private fun attachSessionMessageDelegate(session: GeckoSession, extension: WebExtension) {
        session.webExtensionController.setMessageDelegate(
            extension,
            object : WebExtension.MessageDelegate {
                override fun onMessage(
                    nativeApp: String,
                    message: Any,
                    sender: WebExtension.MessageSender,
                ): GeckoResult<Any>? {
                    val request = (message as? JSONObject)?.toTranslatorApiRequest()
                        ?: return GeckoResult.fromValue(errorJson("TypeError", "無効なリクエストです"))
                    val sessionRequestJob = sessionRequestJobs[session]
                        ?: return GeckoResult.fromValue(errorJson("AbortError", "ページが閉じられました"))
                    val result = GeckoResult<Any>()
                    coroutineScope.launch(sessionRequestJob) {
                        val response = try {
                            respond(request)
                        } catch (error: CancellationException) {
                            // 応答を返さないとページ側の Promise が完了しないままになる
                            mainHandler.post { result.complete(errorJson("AbortError", "ページが閉じられました")) }
                            throw error
                        }
                        mainHandler.post { result.complete(response) }
                    }
                    return result
                }
            },
            NATIVE_APP_ID,
        )
    }

    private suspend fun respond(request: TranslatorApiRequest): JSONObject {
        return try {
            val engine = selectAppEngine() ?: return JSONObject().put("useGeckoEngine", true)
            when (request) {
                is TranslatorApiRequest.Availability -> {
                    successJson().put("availability", engine.availability(request.languagePair).jsonValue)
                }

                is TranslatorApiRequest.Create -> {
                    engine.prepare(request.languagePair)
                    successJson()
                }

                is TranslatorApiRequest.Translate -> {
                    successJson().put("text", engine.translate(request.languagePair, request.text))
                }

                // アプリ側の言語判定はモデルを同梱しており、常に使える
                TranslatorApiRequest.LanguageDetectorAvailability -> {
                    successJson().put("availability", TranslatorApiEngine.Availability.AVAILABLE.jsonValue)
                }

                is TranslatorApiRequest.DetectLanguage -> {
                    val languages = engine.detectLanguages(request.text).map { language ->
                        JSONObject()
                            .put("detectedLanguage", language.languageTag)
                            .put("confidence", language.confidence)
                    }
                    successJson().put("languages", JSONArray(languages))
                }
            }
        } catch (error: TimeoutCancellationException) {
            // エンジン内部の期限切れはキャンセルの一種として投げられるが、要求自体は取り消されていない
            Log.w(TAG, "翻訳要求の処理が時間内に完了しなかった", error)
            errorJson("OperationError", "翻訳が時間内に完了しませんでした")
        } catch (error: CancellationException) {
            throw error
        } catch (error: TranslatorApiEngine.UnsupportedLanguagePairException) {
            errorJson("NotSupportedError", error.message.orEmpty())
        } catch (error: TranslatorApiEngine.ModelNotPreparedException) {
            errorJson("NotAllowedError", error.message.orEmpty())
        } catch (error: Exception) {
            Log.w(TAG, "翻訳要求の処理に失敗", error)
            errorJson("OperationError", "翻訳に失敗しました")
        }
    }

    companion object {
        private const val TAG = "TranslatorApiExt"
        private const val NATIVE_APP_ID = "translatorApiBridge"
        private const val EXTENSION_URI =
            "resource://android/assets/web_extensions/translator_api_bridge/"
    }
}

internal sealed interface TranslatorApiRequest {
    data class Availability(val languagePair: TranslatorApiEngine.LanguagePair) : TranslatorApiRequest

    data class Create(val languagePair: TranslatorApiEngine.LanguagePair) : TranslatorApiRequest

    data class Translate(
        val languagePair: TranslatorApiEngine.LanguagePair,
        val text: String,
    ) : TranslatorApiRequest

    data object LanguageDetectorAvailability : TranslatorApiRequest

    data class DetectLanguage(val text: String) : TranslatorApiRequest
}

// content.js の INPUT_QUOTA と一致させる
internal const val MAX_INPUT_CHARS = 100_000
private const val MAX_LANGUAGE_TAG_CHARS = 64

internal fun JSONObject.toTranslatorApiRequest(): TranslatorApiRequest? {
    return when (optString("action", "")) {
        "availability" -> toLanguagePair()?.let(TranslatorApiRequest::Availability)

        "create" -> toLanguagePair()?.let(TranslatorApiRequest::Create)

        "translate" -> {
            val languagePair = toLanguagePair() ?: return null
            val text = toInputText() ?: return null
            TranslatorApiRequest.Translate(languagePair, text)
        }

        "languageDetectorAvailability" -> TranslatorApiRequest.LanguageDetectorAvailability

        "detectLanguage" -> toInputText()?.let(TranslatorApiRequest::DetectLanguage)

        else -> null
    }
}

private fun JSONObject.toLanguagePair(): TranslatorApiEngine.LanguagePair? {
    val sourceLanguage = optString("sourceLanguage", "")
    val targetLanguage = optString("targetLanguage", "")
    if (!isAcceptableLanguageTag(sourceLanguage) || !isAcceptableLanguageTag(targetLanguage)) return null
    return TranslatorApiEngine.LanguagePair(
        sourceLanguage = sourceLanguage,
        targetLanguage = targetLanguage,
    )
}

private fun JSONObject.toInputText(): String? {
    return optString("text", "").takeIf { it.length <= MAX_INPUT_CHARS }
}

private fun isAcceptableLanguageTag(languageTag: String): Boolean {
    return languageTag.isNotEmpty() && languageTag.length <= MAX_LANGUAGE_TAG_CHARS
}

private fun successJson(): JSONObject = JSONObject().put("success", true)

private fun errorJson(errorName: String, message: String): JSONObject {
    return JSONObject()
        .put("success", false)
        .put("errorName", errorName)
        .put("error", message)
}
