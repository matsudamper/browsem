package net.matsudamper.browser

import android.text.Html
import android.util.Base64
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import net.matsudamper.browser.data.TranslationProvider
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.TranslationsController
import java.net.URL
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class PageTranslator(
    private val session: GeckoSession,
    private val currentPageUrl: String,
) {
    suspend fun translatePageToJapanese(provider: TranslationProvider) {
        when (provider) {
            TranslationProvider.TRANSLATION_PROVIDER_GECKO,
            TranslationProvider.UNRECOGNIZED,
            -> {
                translateByGecko()
            }

            TranslationProvider.TRANSLATION_PROVIDER_LOCAL_AI -> {
                translateByLocalAi()
            }
        }
    }

    private suspend fun translateByGecko() {
        val state = suspendCancellableCoroutine { cont ->
            val delegate = object : TranslationsController.SessionTranslation.Delegate {
                override fun onTranslationStateChange(
                    session: GeckoSession,
                    translationState: TranslationsController.SessionTranslation.TranslationState?
                ) {
                    if (cont.isActive) {
                        cont.resume(translationState)
                    }
                    session.translationsSessionDelegate = null
                }
            }
            session.translationsSessionDelegate = delegate
            cont.invokeOnCancellation {
                session.translationsSessionDelegate = null
            }
        }
        val source = state?.detectedLanguages?.docLangTag ?: return
        val sourceTag = source.substringBefore('-').lowercase(Locale.ROOT)
        if (sourceTag == "ja") {
            return
        }
        val options = TranslationsController.SessionTranslation.TranslationOptions.Builder()
            .downloadModel(true)
            .build()
        val sessionTranslation = session.sessionTranslation ?: return
        sessionTranslation
            .translate(sourceTag, "ja", options)
            .awaitGecko()
    }

    private suspend fun translateByLocalAi() {
        val plainText = withContext(Dispatchers.IO) {
            val raw = URL(currentPageUrl).readText()
            Html.fromHtml(raw, Html.FROM_HTML_MODE_COMPACT).toString()
        }.take(4500)
        if (plainText.isBlank()) {
            return
        }
        val sourceLang = detectLanguage(plainText)
        val sourceTranslateLang = toTranslateLanguageTag(sourceLang) ?: return
        if (sourceTranslateLang == TranslateLanguage.JAPANESE) {
            return
        }
        val translated = translateWithLocalAi(
            text = plainText,
            sourceLanguage = sourceTranslateLang,
            targetLanguage = TranslateLanguage.JAPANESE,
        )
        val html = """
            <html><body style=\"font-family:sans-serif;padding:16px;line-height:1.6;\">
            <h2>翻訳（ローカルAI）</h2>
            <p>${escapeHtml(translated)}</p>
            </body></html>
        """.trimIndent()
        val encoded = Base64.encodeToString(html.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        session.loadUri("data:text/html;base64,$encoded")
    }

    private fun escapeHtml(input: String): String {
        return input
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }

    private suspend fun detectLanguage(text: String): String = withContext(Dispatchers.IO) {
        val languageIdentifier = LanguageIdentification.getClient()
        languageIdentifier.use { languageIdentifier ->
            languageIdentifier.identifyLanguage(text.take(1000)).await().orEmpty()
        }
    }

    private fun toTranslateLanguageTag(languageTag: String): String? {
        if (languageTag.isBlank() || languageTag == "und") {
            return null
        }
        val normalized = languageTag.lowercase(Locale.ROOT)
        return TranslateLanguage.fromLanguageTag(normalized)
            ?: TranslateLanguage.fromLanguageTag(normalized.substringBefore('-'))
    }

    private suspend fun translateWithLocalAi(
        text: String,
        sourceLanguage: String,
        targetLanguage: String,
    ): String = withContext(Dispatchers.IO) {
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(sourceLanguage)
            .setTargetLanguage(targetLanguage)
            .build()
        val translator = Translation.getClient(options)
        translator.use { translator ->
            val conditions = DownloadConditions.Builder().build()
            translator.downloadModelIfNeeded(conditions).await()
            translator.translate(text).await()
        }
    }
}

private suspend fun <T> GeckoResult<T>.awaitGecko(): T? = suspendCancellableCoroutine { cont ->
    accept(
        { value ->
            if (cont.isActive) {
                cont.resume(value)
            }
        },
        { throwable ->
            if (cont.isActive) {
                cont.resumeWithException(throwable ?: RuntimeException("Unknown Gecko error"))
            }
        },
    )
}
