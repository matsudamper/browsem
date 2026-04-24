package net.matsudamper.browser

import net.matsudamper.browser.translate.TranslationPriorityLanguage
import org.junit.Assert.assertEquals
import org.junit.Test

class TranslationLanguageResolverTest {

    @Test
    fun 異なる言語ペアはそのまま返す() {
        val (from, to) = resolveTranslationLanguagePair("en", "ja")
        assertEquals("en", from)
        assertEquals("ja", to)
    }

    @Test
    fun 翻訳元と翻訳先が同じ場合に翻訳元を優先翻訳元言語に切り替える() {
        // from="ja", to="ja" の場合: fromをPRIORITY_FROMに切り替える
        val (from, to) = resolveTranslationLanguagePair("ja", "ja")
        assertEquals(TranslationPriorityLanguage.FROM, from)
        assertEquals("ja", to)
    }

    @Test
    fun 翻訳先が優先翻訳元言語と同じ場合に翻訳先を優先翻訳先言語に切り替える() {
        // from="en", to="en" の場合: toをPRIORITY_TOに切り替える
        val (from, to) = resolveTranslationLanguagePair("en", "en")
        assertEquals("en", from)
        assertEquals(TranslationPriorityLanguage.TO, to)
    }

    @Test
    fun 優先言語以外で同一の場合は翻訳元を優先翻訳元言語に切り替える() {
        // from="fr", to="fr" の場合: fromをPRIORITY_FROMに切り替える
        val (from, to) = resolveTranslationLanguagePair("fr", "fr")
        assertEquals(TranslationPriorityLanguage.FROM, from)
        assertEquals("fr", to)
    }

    @Test
    fun 優先翻訳元定数が英語であることを確認() {
        assertEquals("en", TranslationPriorityLanguage.FROM)
    }

    @Test
    fun 優先翻訳先定数が日本語であることを確認() {
        assertEquals("ja", TranslationPriorityLanguage.TO)
    }
}
