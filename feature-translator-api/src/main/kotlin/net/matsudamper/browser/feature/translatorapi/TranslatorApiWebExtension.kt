package net.matsudamper.browser.feature.translatorapi

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive
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
 * @param writeAppLog アプリのログ画面へ診断を残す。ページ側の処理は拡張機能の中で完結することがあり、
 * アプリからは見えないため、拡張機能が経路と所要時間を送ってくる
 */
class TranslatorApiWebExtension(
    private val selectAppEngine: suspend () -> TranslatorApiEngine?,
    private val writeAppLog: (title: String, body: String) -> Unit,
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
        // installBuiltIn は起動のたびに動作中の拡張機能を停止して入れ直すため、起動直後に読み込まれた
        // ページの要求が停止に巻き込まれ、長く待たされた末に失敗する。バージョンが変わったときだけ入れ直す
        runtime.webExtensionController
            .ensureBuiltIn(EXTENSION_URI, EXTENSION_ID)
            .accept(
                { ext ->
                    Log.d(TAG, "インストール完了: id=${ext?.id} version=${ext?.metaData?.version}")
                    if (ext == null) return@accept
                    if (ext.flags and WebExtension.Flags.ALLOW_CONTENT_MESSAGING == 0L) {
                        Log.w(TAG, "content script からアプリへの通信が許可されていない")
                        writeAppLog(
                            "Translator API 拡張の通信が無効",
                            "content script からアプリへの通信が許可されていない。ページからの要求はすべて失敗する",
                        )
                    }
                    extension = ext
                    registeredSessions.forEach { session ->
                        attachSessionMessageDelegate(session, ext)
                    }
                },
                { error ->
                    Log.e(TAG, "インストール失敗", error)
                    writeAppLog("Translator API 拡張のインストール失敗", error.toString())
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
                    val sessionRequestJob = sessionRequestJobs[session]
                        ?: return GeckoResult.fromValue(errorJson("AbortError", "ページが閉じられました").toNativeMessageResponse())
                    return respondAsync(message, sessionRequestJob)
                }
            },
            NATIVE_APP_ID,
        )
    }

    private fun respondAsync(message: Any, requestJob: Job): GeckoResult<Any> {
        val request = (message as? JSONObject)?.toTranslatorApiRequest()
        if (request == null) {
            Log.w(TAG, "解釈できない要求を受け取った")
            return GeckoResult.fromValue(errorJson("TypeError", "無効なリクエストです").toNativeMessageResponse())
        }
        Log.d(TAG, "要求を受信: ${request::class.simpleName}")
        val result = GeckoResult<Any>()
        // 開始前に取り消されても応答を返せるよう、必ず本体を実行させる
        coroutineScope.launch(requestJob, CoroutineStart.ATOMIC) {
            val response = try {
                ensureActive()
                respond(request)
            } catch (error: CancellationException) {
                // 応答を返さないとページ側の Promise が完了しないままになる
                mainHandler.post { result.complete(errorJson("AbortError", "ページが閉じられました").toNativeMessageResponse()) }
                throw error
            }
            mainHandler.post { result.complete(response.toNativeMessageResponse()) }
        }
        return result
    }

    private suspend fun respond(request: TranslatorApiRequest): JSONObject {
        return when (request) {
            is TranslatorApiRequest.Log -> {
                writeAppLog(request.title, request.body)
                successJson()
            }

            is TranslatorApiRequest.EngineRequest -> respondWithEngine(request)
        }
    }

    private suspend fun respondWithEngine(request: TranslatorApiRequest.EngineRequest): JSONObject {
        return try {
            val engine = selectAppEngine() ?: return JSONObject().put("useGeckoEngine", true)
            val startedAt = SystemClock.elapsedRealtime()
            val response = when (request) {
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
            // 遅さの原因がエンジンか通信かを拡張機能側の記録で切り分けられるようにする
            response
                .put("engine", engine::class.simpleName)
                .put("appMs", SystemClock.elapsedRealtime() - startedAt)
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
        private const val EXTENSION_ID = "translator-api-bridge@browsem"
        private const val EXTENSION_URI =
            "resource://android/assets/web_extensions/translator_api_bridge/"
    }
}

internal sealed interface TranslatorApiRequest {
    /** 翻訳エンジンで処理する要求 */
    sealed interface EngineRequest : TranslatorApiRequest

    data class Availability(val languagePair: TranslatorApiEngine.LanguagePair) : EngineRequest

    data class Create(val languagePair: TranslatorApiEngine.LanguagePair) : EngineRequest

    data class Translate(
        val languagePair: TranslatorApiEngine.LanguagePair,
        val text: String,
    ) : EngineRequest

    data object LanguageDetectorAvailability : EngineRequest

    data class DetectLanguage(val text: String) : EngineRequest

    /** 拡張機能が送ってくる診断。アプリのログ画面へ残す */
    data class Log(val title: String, val body: String) : TranslatorApiRequest
}

// content.js の INPUT_QUOTA と一致させる
internal const val MAX_INPUT_CHARS = 100_000
private const val MAX_LANGUAGE_TAG_CHARS = 64
private const val MAX_LOG_TITLE_CHARS = 100
private const val MAX_LOG_BODY_CHARS = 2_000

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

        "log" -> TranslatorApiRequest.Log(
            title = optString("title", "").take(MAX_LOG_TITLE_CHARS),
            body = optString("body", "").take(MAX_LOG_BODY_CHARS),
        )

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

// Gecko は応答の JSONObject を拡張機能へ渡せず、応答ごと捨てて要求を失敗させる。文字列なら渡せる
private fun JSONObject.toNativeMessageResponse(): Any = toString()

private fun successJson(): JSONObject = JSONObject().put("success", true)

private fun errorJson(errorName: String, message: String): JSONObject {
    return JSONObject()
        .put("success", false)
        .put("errorName", errorName)
        .put("error", message)
}
