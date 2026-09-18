package net.matsudamper.browser.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PageTranslationCacheTest {
    @Test
    fun 同じ言語の組み合わせなら別の取得元でも訳文を返す() {
        val cache = PageTranslationCache()
        cache.forTranslator("mlkit", "en", "ja")["Hello"] = "こんにちは"

        assertEquals("こんにちは", cache.forTranslator("mlkit", "en", "ja")["Hello"])
    }

    @Test
    fun 言語の組み合わせが違えば訳文を共有しない() {
        val cache = PageTranslationCache()
        cache.forTranslator("mlkit", "en", "ja")["Hello"] = "こんにちは"

        assertNull(cache.forTranslator("mlkit", "ja", "en")["Hello"])
        assertNull(cache.forTranslator("mlkit", "en", "fr")["Hello"])
    }

    @Test
    fun プロバイダーやモデルが違えば訳文を共有しない() {
        val cache = PageTranslationCache()
        cache.forTranslator("gemini-nano/stable-full/a", "en", "ja")["Hello"] = "こんにちは"

        assertNull(cache.forTranslator("mlkit", "en", "ja")["Hello"])
        assertNull(cache.forTranslator("gemini-nano/stable-fast/b", "en", "ja")["Hello"])
    }

    @Test
    fun クリアすると訳文を返さない() {
        val cache = PageTranslationCache()
        cache.forTranslator("mlkit", "en", "ja")["Hello"] = "こんにちは"

        cache.clear()

        assertNull(cache.forTranslator("mlkit", "en", "ja")["Hello"])
    }
}
