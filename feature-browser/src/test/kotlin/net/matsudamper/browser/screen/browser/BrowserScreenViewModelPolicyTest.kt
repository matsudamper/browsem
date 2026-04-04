package net.matsudamper.browser.screen.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserScreenViewModelPolicyTest {

    @Test
    fun `空入力ではWebサジェストを取得しない`() {
        assertFalse(shouldFetchWebSuggestions(""))
    }

    @Test
    fun `HTTP URLではWebサジェストを取得しない`() {
        assertFalse(shouldFetchWebSuggestions("https://example.com"))
    }

    @Test
    fun `スキーム付きURLではWebサジェストを取得しない`() {
        assertFalse(shouldFetchWebSuggestions("about:blank"))
        assertFalse(shouldFetchWebSuggestions("file:///storage/emulated/0/Download/test.html"))
        assertFalse(shouldFetchWebSuggestions("HTTPS://example.com"))
    }

    @Test
    fun `ホスト形式の入力ではWebサジェストを取得しない`() {
        assertFalse(shouldFetchWebSuggestions("example.com"))
    }

    @Test
    fun `キーワード入力ではWebサジェストを取得する`() {
        assertTrue(shouldFetchWebSuggestions("kotlin compose browser"))
    }

    @Test
    fun `基準タブIDから前後タブを解決する`() {
        val adjacentTabIds = resolveAdjacentTabIds(
            orderedTabIds = listOf("a", "b", "c", "d"),
            anchorTabId = "c",
        )

        assertEquals(
            AdjacentTabIds(previousTabId = "b", nextTabId = "d"),
            adjacentTabIds,
        )
    }

    @Test
    fun `基準タブが見つからない場合は前後タブを返さない`() {
        val adjacentTabIds = resolveAdjacentTabIds(
            orderedTabIds = listOf("a", "b", "c"),
            anchorTabId = "z",
        )

        assertEquals(AdjacentTabIds(), adjacentTabIds)
    }
}
