package net.matsudamper.browser.feature.translatorapi

/** Translator API と Language Detector API のポリフィルが翻訳と言語判定を委譲する先 */
interface TranslatorApiEngine {
    suspend fun availability(languagePair: LanguagePair): Availability

    /** 翻訳に必要なモデルを揃える。対応していない言語の組み合わせでは [UnsupportedLanguagePairException] を投げる */
    suspend fun prepare(languagePair: LanguagePair)

    /** 対応していない言語の組み合わせでは [UnsupportedLanguagePairException] を投げる */
    suspend fun translate(languagePair: LanguagePair, text: String): String

    /** 判定できた言語だけを返す。判定不能を表す und は含めない */
    suspend fun detectLanguages(text: String): List<DetectedLanguage>

    /** BCP 47 の言語タグの組 */
    data class LanguagePair(
        val sourceLanguage: String,
        val targetLanguage: String,
    )

    /** @param confidence 0 から 1 の確度 */
    data class DetectedLanguage(
        val languageTag: String,
        val confidence: Double,
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
