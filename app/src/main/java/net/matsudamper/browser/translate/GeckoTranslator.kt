package net.matsudamper.browser.translate

import net.matsudamper.browser.awaitGecko
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.TranslationsController

class GeckoTranslator(
    private val session: GeckoSession,
    private val fromLanguage: String,
) : Translator {
    override suspend fun translate() {
        val options = TranslationsController.SessionTranslation.TranslationOptions.Builder()
            .downloadModel(true)
            .build()
        val sessionTranslation = session.sessionTranslation ?: return
        sessionTranslation
            .translate(fromLanguage, "ja", options)
            .awaitGecko()
    }
}
