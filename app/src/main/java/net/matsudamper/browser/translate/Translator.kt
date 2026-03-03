package net.matsudamper.browser.translate
interface Translator {
    suspend fun translate()

    enum class TranslateState {
        MODEL_DOWNLOAD
    }
}
