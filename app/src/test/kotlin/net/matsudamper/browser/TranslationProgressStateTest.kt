package net.matsudamper.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationProgressStateTest {
    @Test
    fun 翻訳進捗の表示文言を返す() {
        assertEquals("翻訳を開始中...", translationProgressLabel(TranslationState.Loading))
        assertEquals("ページを解析中...", translationProgressLabel(TranslationState.ScanningPage))
        assertEquals("翻訳言語を確認中...", translationProgressLabel(TranslationState.DetectingLanguage))
        assertEquals("ML翻訳モデルを準備中...", translationProgressLabel(TranslationState.PreparingModel))
        assertEquals("翻訳中...", translationProgressLabel(TranslationState.Translating))
    }

    @Test
    fun 翻訳処理中の状態だけ進捗扱いにする() {
        assertTrue(TranslationState.Loading.isInProgress)
        assertTrue(TranslationState.ScanningPage.isInProgress)
        assertTrue(TranslationState.DetectingLanguage.isInProgress)
        assertTrue(TranslationState.PreparingModel.isInProgress)
        assertTrue(TranslationState.Translating.isInProgress)
        assertFalse(TranslationState.Idle.isInProgress)
        assertFalse(TranslationState.Translated.isInProgress)
        assertFalse(TranslationState.Error.isInProgress)
    }
}
