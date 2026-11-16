package net.matsudamper.browser.feature.translatorapi

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension

/**
 * Translator API (https://developer.mozilla.org/en-US/docs/Web/API/Translator) のポリフィル用ビルトイン WebExtension。
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
        val extension = extension ?: return
        attachSessionMessageDelegate(session, extension)
    }

    fun unregisterSession(session: GeckoSession) {
        registeredSessions.remove(session)
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
                    val result = GeckoResult<Any>()
                    coroutineScope.launch {
                        val response = respond(request)
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
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: TranslatorApiEngine.UnsupportedLanguagePairException) {
            errorJson("NotSupportedError", error.message.orEmpty())
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
    val languagePair: TranslatorApiEngine.LanguagePair

    data class Availability(override val languagePair: TranslatorApiEngine.LanguagePair) : TranslatorApiRequest

    data class Create(override val languagePair: TranslatorApiEngine.LanguagePair) : TranslatorApiRequest

    data class Translate(
        override val languagePair: TranslatorApiEngine.LanguagePair,
        val text: String,
    ) : TranslatorApiRequest
}

// content.js の INPUT_QUOTA と一致させる
internal const val MAX_INPUT_CHARS = 100_000
private const val MAX_LANGUAGE_TAG_CHARS = 64

internal fun JSONObject.toTranslatorApiRequest(): TranslatorApiRequest? {
    val sourceLanguage = optString("sourceLanguage", "")
    val targetLanguage = optString("targetLanguage", "")
    if (!isAcceptableLanguageTag(sourceLanguage) || !isAcceptableLanguageTag(targetLanguage)) return null
    val languagePair = TranslatorApiEngine.LanguagePair(
        sourceLanguage = sourceLanguage,
        targetLanguage = targetLanguage,
    )
    return when (optString("action", "")) {
        "availability" -> TranslatorApiRequest.Availability(languagePair)

        "create" -> TranslatorApiRequest.Create(languagePair)

        "translate" -> {
            val text = optString("text", "")
            if (text.length > MAX_INPUT_CHARS) return null
            TranslatorApiRequest.Translate(languagePair, text)
        }

        else -> null
    }
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
