package net.matsudamper.browser.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PageTranslationCacheTest {
    @Test
    fun 同じ言語の組み合わせなら別の取得元でも訳文を返す() {
        val cache = PageTranslationCache()
        cache.forLanguagePair("en", "ja")["Hello"] = "こんにちは"

        assertEquals("こんにちは", cache.forLanguagePair("en", "ja")["Hello"])
    }

    @Test
    fun 言語の組み合わせが違えば訳文を共有しない() {
        val cache = PageTranslationCache()
        cache.forLanguagePair("en", "ja")["Hello"] = "こんにちは"

        assertNull(cache.forLanguagePair("ja", "en")["Hello"])
        assertNull(cache.forLanguagePair("en", "fr")["Hello"])
    }

    @Test
    fun クリアすると訳文を返さない() {
        val cache = PageTranslationCache()
        cache.forLanguagePair("en", "ja")["Hello"] = "こんにちは"

        cache.clear()

        assertNull(cache.forLanguagePair("en", "ja")["Hello"])
    }
}
