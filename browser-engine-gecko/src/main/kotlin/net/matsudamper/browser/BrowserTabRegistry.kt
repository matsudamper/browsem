package net.matsudamper.browser

import net.matsudamper.browser.core.TabSummary

internal class BrowserTabRegistry {
    private val tabsById = LinkedHashMap<String, BrowserTab>()

    fun isEmpty(): Boolean = tabsById.isEmpty()

    fun contains(tabId: String): Boolean = tabId in tabsById

    fun find(tabId: String): BrowserTab? = tabsById[tabId]

    fun firstOrNull(): BrowserTab? = tabsById.values.firstOrNull()

    fun values(): Collection<BrowserTab> = tabsById.values

    fun orderedTabs(): List<BrowserTab> = tabsById.values.toList()

    fun insert(tab: BrowserTab, insertIndex: Int) {
        val orderedTabs = tabsById.values.toMutableList().apply {
            removeAll { existing -> existing.tabId == tab.tabId }
        }
        val targetIndex = insertIndex.coerceIn(0, orderedTabs.size)
        orderedTabs.add(targetIndex, tab)
        tabsById.clear()
        orderedTabs.forEach { orderedTab ->
            tabsById[orderedTab.tabId] = orderedTab
        }
    }

    fun remove(tabId: String): BrowserTab? = tabsById.remove(tabId)

    fun move(fromIndex: Int, toIndex: Int) {
        val orderedTabs = tabsById.values.toMutableList()
        if (fromIndex !in orderedTabs.indices || toIndex !in orderedTabs.indices) {
            return
        }
        orderedTabs.add(toIndex, orderedTabs.removeAt(fromIndex))
        tabsById.clear()
        orderedTabs.forEach { orderedTab ->
            tabsById[orderedTab.tabId] = orderedTab
        }
    }

    fun summaries(): List<TabSummary> = tabsById.values.map(BrowserTab::toSummary)

    fun clear() {
        tabsById.clear()
    }
}
