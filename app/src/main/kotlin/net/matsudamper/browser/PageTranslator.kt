package net.matsudamper.browser

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.matsudamper.browser.data.TranslationProvider
import net.matsudamper.browser.translate.GeckoTranslator
import net.matsudamper.browser.translate.LocalAITranslator
import net.matsudamper.browser.translate.TranslationLanguages
import org.mozilla.geckoview.GeckoSession
import java.net.HttpURLConnection
import java.net.URL

internal class PageTranslator(
    private val session: GeckoSession,
    private val currentPageUrl: String,
) {
    suspend fun translatePage(
        provider: TranslationProvider,
        fromLanguage: String?,
        toLanguage: String,
    ): TranslationLanguages? {
        return when (provider) {
            TranslationProvider.TRANSLATION_PROVIDER_GECKO,
            TranslationProvider.UNRECOGNIZED,
                -> {
                // 言語不明の場合はHTMLのlang属性にフォールバック
                val lang = fromLanguage ?: fetchHtmlLang() ?: return null
                GeckoTranslator(session, lang, toLanguage)
            }

            TranslationProvider.TRANSLATION_PROVIDER_LOCAL_AI -> {
                LocalAITranslator(session, currentPageUrl, toLanguage)
            }
        }.translate()
    }

    /** ページのHTMLを取得し、&lt;html lang="..."&gt;属性から言語タグを抽出する */
    private suspend fun fetchHtmlLang(): String? = withContext(Dispatchers.IO) {
        runCatching {
            val connection = URL(currentPageUrl).openConnection() as HttpURLConnection
            connection.connectTimeout = NETWORK_TIMEOUT_MS
            connection.readTimeout = NETWORK_TIMEOUT_MS
            try {
                // lang属性は先頭付近にあるので、先頭部分だけ読む
                val head = connection.inputStream.bufferedReader().use { reader ->
                    val buffer = CharArray(HEAD_READ_SIZE)
                    val read = reader.read(buffer)
                    if (read > 0) String(buffer, 0, read) else ""
                }
                val match = Regex("<html[^>]+lang=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(head)
                match?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
    }

    companion object {
        private const val NETWORK_TIMEOUT_MS = 15_000
        /** lang属性の抽出に十分な読み取りサイズ */
        private const val HEAD_READ_SIZE = 4096
    }
}
