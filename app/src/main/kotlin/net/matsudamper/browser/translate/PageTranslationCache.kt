package net.matsudamper.browser.translate

import java.util.concurrent.ConcurrentHashMap

/**
 * ページ遷移までの間、同じ原文の訳文を保持する。
 *
 * 「原文を表示」と再翻訳を往復しても推論をやり直さずに済むよう、
 * 翻訳 1 回ではなくタブ側で寿命を管理する。
 */
class PageTranslationCache {
    private val translations = ConcurrentHashMap<Key, String>()

    fun forLanguagePair(sourceLanguage: String, targetLanguage: String): LanguagePairCache =
        LanguagePairCache(sourceLanguage, targetLanguage)

    fun clear() {
        translations.clear()
    }

    inner class LanguagePairCache(
        private val sourceLanguage: String,
        private val targetLanguage: String,
    ) {
        operator fun get(text: String): String? = translations[Key(sourceLanguage, targetLanguage, text)]

        operator fun set(text: String, translatedText: String) {
            translations[Key(sourceLanguage, targetLanguage, text)] = translatedText
        }
    }

    private data class Key(
        val sourceLanguage: String,
        val targetLanguage: String,
        val text: String,
    )
}
