package net.matsudamper.browser.feature.translatorapi

/** Translator API のポリフィルが翻訳を委譲する先 */
interface TranslatorApiEngine {
    suspend fun availability(languagePair: LanguagePair): Availability

    /** 翻訳に必要なモデルを揃える。対応していない言語の組み合わせでは [UnsupportedLanguagePairException] を投げる */
    suspend fun prepare(languagePair: LanguagePair)

    /** 対応していない言語の組み合わせでは [UnsupportedLanguagePairException] を投げる */
    suspend fun translate(languagePair: LanguagePair, text: String): String

    /** BCP 47 の言語タグの組 */
    data class LanguagePair(
        val sourceLanguage: String,
        val targetLanguage: String,
    )

    /** Translator.availability() が返す値 */
    enum class Availability(val jsonValue: String) {
        AVAILABLE("available"),
        DOWNLOADABLE("downloadable"),
        UNAVAILABLE("unavailable"),
    }

    class UnsupportedLanguagePairException(languagePair: LanguagePair) :
        Exception("${languagePair.sourceLanguage} から ${languagePair.targetLanguage} へは翻訳できません")
}
