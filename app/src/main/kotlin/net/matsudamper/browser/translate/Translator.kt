package net.matsudamper.browser.translate

interface Translator {
    suspend fun translate(): TranslationLanguages?

    enum class TranslateState {
        MODEL_DOWNLOAD
    }
}

/** 翻訳元・翻訳先の言語タグペア */
data class TranslationLanguages(val fromLanguage: String, val toLanguage: String)

/** 翻訳の優先言語 */
object TranslationPriorityLanguage {
    /** 優先翻訳元言語（英語） */
    const val FROM = "en"

    /** 優先翻訳先言語（日本語） */
    const val TO = "ja"
}
