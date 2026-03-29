package net.matsudamper.browser

import net.matsudamper.browser.core.TabSummary
import net.matsudamper.browser.data.PersistedTabState

internal class BrowserTabRegistry {
    private val tabsById = LinkedHashMap<String, BrowserTab>()

    fun isEmpty(): Boolean = tabsById.isEmpty()

    fun find(tabId: String): BrowserTab? = tabsById[tabId]

    fun firstOrNull(): BrowserTab? = tabsById.values.firstOrNull()

    fun values(): Collection<BrowserTab> = tabsById.values

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

    fun put(tab: BrowserTab) {
        tabsById[tab.tabId] = tab
    }

    fun remove(tabId: String): BrowserTab? = tabsById.remove(tabId)

    fun removeMissing(
        retainedTabIds: Set<String>,
        shouldKeep: (String) -> Boolean,
    ): List<BrowserTab> {
        val removedTabIds = tabsById.keys.filter { tabId ->
            tabId !in retainedTabIds && !shouldKeep(tabId)
        }
        return removedTabIds.mapNotNull(::remove)
    }

    fun orderedTabs(summaries: List<TabSummary>): List<BrowserTab> {
        return summaries.mapNotNull { summary -> tabsById[summary.id] }
    }

    fun summariesInPersistedOrder(persistedTabs: List<PersistedTabState>): List<TabSummary> {
        return persistedTabs.mapNotNull { persistedTab ->
            tabsById[persistedTab.tabId]?.toSummary()
        }
    }

    fun refreshSummaries(currentSummaries: List<TabSummary>): List<TabSummary> {
        return currentSummaries.mapNotNull { summary ->
            tabsById[summary.id]?.toSummary()
        }
    }

    fun clear() {
        tabsById.clear()
    }
}
