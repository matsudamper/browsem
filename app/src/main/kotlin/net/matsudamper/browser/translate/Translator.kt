package net.matsudamper.browser.translate

interface Translator {
    suspend fun translate(): TranslationLanguages?

    enum class TranslateState {
        PAGE_SCAN,
        LANGUAGE_DETECTION,
        MODEL_DOWNLOAD,
        TRANSLATING,
    }
}

/**
 * ページ内テキストの翻訳進捗。
 *
 * [totalCount] は動的に追加されたテキストの分だけ増えるため、完了後も増えることがある。
 */
data class TranslationProgress(
    val translatedCount: Int,
    val totalCount: Int,
) {
    val isCompleted: Boolean get() = translatedCount >= totalCount
}

data class TranslationLanguages(val fromLanguage: String, val toLanguage: String)

object TranslationPriorityLanguage {
    const val FROM = "en"

    const val TO = "ja"
}
