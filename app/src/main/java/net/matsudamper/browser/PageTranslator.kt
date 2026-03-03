package net.matsudamper.browser

import net.matsudamper.browser.data.TranslationProvider
import net.matsudamper.browser.translate.GeckoTranslator
import net.matsudamper.browser.translate.LocalAITranslator
import org.mozilla.geckoview.GeckoSession

internal class PageTranslator(
    private val session: GeckoSession,
    private val currentPageUrl: String,
) {
    suspend fun translatePageToJapanese(provider: TranslationProvider) {
        when (provider) {
            TranslationProvider.TRANSLATION_PROVIDER_GECKO,
            TranslationProvider.UNRECOGNIZED,
                -> GeckoTranslator(session)

            TranslationProvider.TRANSLATION_PROVIDER_LOCAL_AI -> {
                LocalAITranslator(session, currentPageUrl)
            }
        }.translate()
    }
}
