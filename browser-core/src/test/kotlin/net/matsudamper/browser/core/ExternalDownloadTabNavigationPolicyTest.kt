package net.matsudamper.browser.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExternalDownloadTabNavigationPolicyTest {

    @Test
    fun resolveTargetTab_returnsLastTabInDefaultGroup() {
        val state = TabStoreState(
            tabs = listOf(
                tab("tab-a"),
                tab("tab-b"),
                tab("tab-download"),
            ),
            tabGroupAssignments = mapOf(
                "tab-a" to "group-default",
                "tab-b" to "group-other",
                "tab-download" to "group-default",
            ),
        )

        val target = ExternalDownloadTabNavigationPolicy.resolveTargetTabAfterClosingExternalDownload(
            state = state,
            defaultGroupId = "group-default",
            excludingTabId = "tab-download",
        )

        assertEquals("tab-a", target)
    }

    @Test
    fun resolveTargetTab_whenDefaultGroupHasMultipleTabs_returnsLastOne() {
        val state = TabStoreState(
            tabs = listOf(
                tab("tab-a"),
                tab("tab-b"),
                tab("tab-download"),
            ),
            tabGroupAssignments = mapOf(
                "tab-a" to "group-default",
                "tab-b" to "group-default",
                "tab-download" to "group-default",
            ),
        )

        val target = ExternalDownloadTabNavigationPolicy.resolveTargetTabAfterClosingExternalDownload(
            state = state,
            defaultGroupId = "group-default",
            excludingTabId = "tab-download",
        )

        assertEquals("tab-b", target)
    }

    @Test
    fun resolveTargetTab_whenNoDefaultGroupTabs_fallsBackToLastRemainingTab() {
        val state = TabStoreState(
            tabs = listOf(
                tab("tab-a"),
                tab("tab-download"),
            ),
            tabGroupAssignments = mapOf(
                "tab-a" to "group-other",
                "tab-download" to "group-default",
            ),
        )

        val target = ExternalDownloadTabNavigationPolicy.resolveTargetTabAfterClosingExternalDownload(
            state = state,
            defaultGroupId = "group-default",
            excludingTabId = "tab-download",
        )

        assertEquals("tab-a", target)
    }

    @Test
    fun resolveTargetTab_whenNoRemainingTabs_returnsNull() {
        val state = TabStoreState(
            tabs = listOf(tab("tab-download")),
            tabGroupAssignments = mapOf("tab-download" to "group-default"),
        )

        val target = ExternalDownloadTabNavigationPolicy.resolveTargetTabAfterClosingExternalDownload(
            state = state,
            defaultGroupId = "group-default",
            excludingTabId = "tab-download",
        )

        assertNull(target)
    }

    private fun tab(id: String): TabSummary = TabSummary(
        id = id,
        title = id,
        url = "https://example.com/$id",
    )
}
