package net.matsudamper.browser.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TabSelectionPolicyTest {

    @Test
    fun closingSelectedTabPrefersOpenerWhenAlive() {
        val state = TabStoreState(
            tabs = listOf(
                TabSummary(id = "a", title = "A", url = "https://a.example"),
                TabSummary(
                    id = "b",
                    title = "B",
                    url = "https://b.example",
                    openerTabId = "a",
                ),
                TabSummary(id = "c", title = "C", url = "https://c.example"),
            ),
            selectedTabId = "b",
        )

        val result = TabSelectionPolicy.resolveNextSelectedTab("b", state)

        assertEquals("a", result)
    }

    @Test
    fun closingSelectedTabFallsBackToLastRemainingTab() {
        val state = TabStoreState(
            tabs = listOf(
                TabSummary(id = "a", title = "A", url = "https://a.example"),
                TabSummary(id = "b", title = "B", url = "https://b.example"),
            ),
            selectedTabId = "b",
        )

        val result = TabSelectionPolicy.resolveNextSelectedTab("b", state)

        assertEquals("a", result)
    }

    @Test
    fun closingNonSelectedTabKeepsCurrentSelection() {
        val state = TabStoreState(
            tabs = listOf(
                TabSummary(id = "a", title = "A", url = "https://a.example"),
                TabSummary(id = "b", title = "B", url = "https://b.example"),
            ),
            selectedTabId = "a",
        )

        val result = TabSelectionPolicy.resolveNextSelectedTab("b", state)

        assertEquals("a", result)
    }

    @Test
    fun closingLastTabReturnsNull() {
        val state = TabStoreState(
            tabs = listOf(
                TabSummary(id = "a", title = "A", url = "https://a.example"),
            ),
            selectedTabId = "a",
        )

        val result = TabSelectionPolicy.resolveNextSelectedTab("a", state)

        assertNull(result)
    }
}
