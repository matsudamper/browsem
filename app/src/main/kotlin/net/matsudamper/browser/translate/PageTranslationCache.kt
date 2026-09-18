package net.matsudamper.browser.translate

import java.util.concurrent.ConcurrentHashMap

/**
 * ページ遷移までの間、同じ原文の訳文を保持する。
 *
 * 「原文を表示」と再翻訳を往復しても推論をやり直さずに済むよう、
 * 翻訳 1 回ではなくタブ側で寿命を管理する。
 *
 * 訳文はプロバイダーやモデルごとに異なるため、[translatorKey] も含めて分ける。
 */
class PageTranslationCache {
    private val translations = ConcurrentHashMap<Key, String>()

    fun forTranslator(
        translatorKey: String,
        sourceLanguage: String,
        targetLanguage: String,
    ): LanguagePairCache = LanguagePairCache(translatorKey, sourceLanguage, targetLanguage)

    fun clear() {
        translations.clear()
    }

    inner class LanguagePairCache(
        private val translatorKey: String,
        private val sourceLanguage: String,
        private val targetLanguage: String,
    ) {
        operator fun get(text: String): String? = translations[keyOf(text)]

        operator fun set(text: String, translatedText: String) {
            translations[keyOf(text)] = translatedText
        }

        private fun keyOf(text: String) = Key(translatorKey, sourceLanguage, targetLanguage, text)
    }

    private data class Key(
        val translatorKey: String,
        val sourceLanguage: String,
        val targetLanguage: String,
        val text: String,
    )
}
