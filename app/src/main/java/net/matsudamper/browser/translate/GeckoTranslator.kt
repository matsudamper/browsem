package net.matsudamper.browser.translate

import android.util.Log
import net.matsudamper.browser.awaitGecko
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.TranslationsController

class GeckoTranslator(
    private val session: GeckoSession,
) : Translator {
    override suspend fun translate() {
        val delegate = object : TranslationsController.SessionTranslation.Delegate {
            override fun onExpectedTranslate(session: GeckoSession) {
                Log.d("LOG", "onExpectedTranslate")
            }

            override fun onOfferTranslate(session: GeckoSession) {
                Log.d("LOG", "onOfferTranslate")
            }

            override fun onTranslationStateChange(
                session: GeckoSession,
                translationState: TranslationsController.SessionTranslation.TranslationState?
            ) {
                Log.d("LOG", "onTranslationStateChange")
            }
        }
        session.translationsSessionDelegate = delegate

        val options = TranslationsController.SessionTranslation.TranslationOptions.Builder()
            .downloadModel(true)
            .build()
        val sessionTranslation = session.sessionTranslation ?: return
        sessionTranslation
            .translate("en", "ja", options)
            .awaitGecko()
    }
}
