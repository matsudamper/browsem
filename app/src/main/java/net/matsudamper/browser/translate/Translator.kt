package net.matsudamper.browser.translate

interface Translator {
    suspend fun translate(): TranslationLanguages?

    enum class TranslateState {
        MODEL_DOWNLOAD
    }
}

/** 翻訳元・翻訳先の言語タグペア */
data class TranslationLanguages(val fromLanguage: String, val toLanguage: String)
