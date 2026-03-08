package net.matsudamper.browser.translate

import android.text.Html
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.mozilla.geckoview.GeckoSession
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class LocalAITranslator(
    private val session: GeckoSession,
    private val currentPageUrl: String,
) : Translator {
    override suspend fun translate() {
        // 1. HTMLを取得（タイムアウト付き）
        val rawHtml = withContext(Dispatchers.IO) {
            val connection = URL(currentPageUrl).openConnection() as HttpURLConnection
            connection.connectTimeout = NETWORK_TIMEOUT_MS
            connection.readTimeout = NETWORK_TIMEOUT_MS
            try {
                connection.inputStream.bufferedReader().readText()
            } finally {
                connection.disconnect()
            }
        }

        // 2. <script>/<style>/<noscript>/<template> を除去してJSON-LD汚染を防ぐ
        val cleanedHtml = rawHtml
            .replace(Regex("<script[^>]*>[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<style[^>]*>[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<noscript[^>]*>[\\s\\S]*?</noscript>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<template[^>]*>[\\s\\S]*?</template>", RegexOption.IGNORE_CASE), "")

        val plainText = Html.fromHtml(cleanedHtml, Html.FROM_HTML_MODE_COMPACT)
            .toString()
            .take(4500)
        if (plainText.isBlank()) return

        // 3. 言語検出
        val sourceLang = detectLanguage(plainText)
        val sourceTranslateLang = toTranslateLanguageTag(sourceLang) ?: return
        if (sourceTranslateLang == TranslateLanguage.JAPANESE) return

        // 4. 翻訳
        val translated = translateWithLocalAi(
            text = plainText,
            sourceLanguage = sourceTranslateLang,
            targetLanguage = TranslateLanguage.JAPANESE,
        )

        // 5. javascript: URI でURLを変えずにDOMを置換
        //    各文字をUnicode エスケープにすることで単引用符・制御文字の問題を回避
        val escapedText = translated.asSequence()
            .joinToString("") { c ->
                when {
                    c == '\\' -> "\\\\"
                    c.code < 0x20 || c.code > 0x7E -> "\\u${c.code.toString(16).padStart(4, '0')}"
                    c == '\'' -> "\\'"
                    else -> c.toString()
                }
            }
        // 翻訳（ローカルAI） の各文字をUnicodeエスケープ済みリテラル
        val title = "\\u7ffb\\u8a33\\uff08\\u30ed\\u30fc\\u30ab\\u30ebAI\\uff09"
        val script = "javascript:void((function(){" +
            "var d=document;" +
            "var div=d.createElement('div');" +
            "div.style='font-family:sans-serif;padding:16px;line-height:1.6';" +
            "var h=d.createElement('h2');" +
            "h.textContent='$title';" +
            "var p=d.createElement('p');" +
            "p.style='white-space:pre-wrap';" +
            "p.textContent='$escapedText';" +
            "div.appendChild(h);" +
            "div.appendChild(p);" +
            "d.body.innerHTML='';" +
            "d.body.appendChild(div);" +
            "})())"
        session.loadUri(script)
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

    companion object {
        private const val NETWORK_TIMEOUT_MS = 15_000
    }
}
