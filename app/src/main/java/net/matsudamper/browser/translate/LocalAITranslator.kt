package net.matsudamper.browser.translate

import android.text.Html
import android.util.Base64
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.mozilla.geckoview.GeckoSession
import java.net.URL
import java.util.Locale

class LocalAITranslator(
    private val session: GeckoSession,
    private val currentPageUrl: String,
) : Translator {
    override suspend fun translate() {
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
            <html>
            <head><meta charset="utf-8"></head>
            <body style="font-family:sans-serif;padding:16px;line-height:1.6;">
            <h2>翻訳（ローカルAI）</h2>
            <p>${escapeHtml(translated)}</p>
            </body></html>
        """.trimIndent()
        val encoded = Base64.encodeToString(html.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        session.loadUri("data:text/html;charset=utf-8;base64,$encoded")
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
