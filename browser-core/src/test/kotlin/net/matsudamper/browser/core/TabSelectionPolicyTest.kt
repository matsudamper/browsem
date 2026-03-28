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
    fun closingSelectedTabInGroupPrefersAdjacentTabInSameGroup() {
        // グループ0: [a, b], グループ1: [c, d (selected)]
        // グローバル順序: [a, c, d, b]
        // d を閉じると、同じグループの c が選ばれるべき
        val state = TabStoreState(
            tabs = listOf(
                TabSummary(id = "a", title = "A", url = "https://a.example"),
                TabSummary(id = "c", title = "C", url = "https://c.example"),
                TabSummary(id = "d", title = "D", url = "https://d.example"),
                TabSummary(id = "b", title = "B", url = "https://b.example"),
            ),
            selectedTabId = "d",
            tabGroupAssignments = mapOf(
                "a" to "group0",
                "b" to "group0",
                "c" to "group1",
                "d" to "group1",
            ),
        )

        val result = TabSelectionPolicy.resolveNextSelectedTab("d", state)

        // 同グループの前のタブ c が選ばれるべき
        assertEquals("c", result)
    }

    @Test
    fun closingFirstTabInGroupSelectsNextInSameGroup() {
        // グループ0: [a], グループ1: [c (selected), d]
        // グローバル順序: [a, c, d]
        // c を閉じると、同グループの d が選ばれるべき
        val state = TabStoreState(
            tabs = listOf(
                TabSummary(id = "a", title = "A", url = "https://a.example"),
                TabSummary(id = "c", title = "C", url = "https://c.example"),
                TabSummary(id = "d", title = "D", url = "https://d.example"),
            ),
            selectedTabId = "c",
            tabGroupAssignments = mapOf(
                "a" to "group0",
                "c" to "group1",
                "d" to "group1",
            ),
        )

        val result = TabSelectionPolicy.resolveNextSelectedTab("c", state)

        assertEquals("d", result)
    }

    @Test
    fun closingLastTabInGroupFallsBackToOtherGroup() {
        // グループ0: [a, b], グループ1: [c (selected)]
        // c を閉じると、同グループにタブがないので他グループにフォールバック
        val state = TabStoreState(
            tabs = listOf(
                TabSummary(id = "a", title = "A", url = "https://a.example"),
                TabSummary(id = "c", title = "C", url = "https://c.example"),
                TabSummary(id = "b", title = "B", url = "https://b.example"),
            ),
            selectedTabId = "c",
            tabGroupAssignments = mapOf(
                "a" to "group0",
                "b" to "group0",
                "c" to "group1",
            ),
        )

        val result = TabSelectionPolicy.resolveNextSelectedTab("c", state)

        // 同グループに残りがないので最後のタブにフォールバック
        assertEquals("b", result)
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
