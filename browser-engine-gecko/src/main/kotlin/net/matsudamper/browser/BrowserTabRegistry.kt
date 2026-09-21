package net.matsudamper.browser

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.setValue
import net.matsudamper.browser.core.TabSummary

internal class BrowserTabRegistry {
    private val tabsById = LinkedHashMap<String, BrowserTab>()

    // 読み出しはコンポジション中からも行われるため、変更のたびに再コンポーズを発火させる。
    // 同じインスタンスを入れ直して通知するので neverEqualPolicy が必要。
    private var observableTabsById: Map<String, BrowserTab> by mutableStateOf(tabsById, neverEqualPolicy())

    fun isEmpty(): Boolean = observableTabsById.isEmpty()

    fun contains(tabId: String): Boolean = tabId in observableTabsById

    fun find(tabId: String): BrowserTab? = observableTabsById[tabId]

    fun firstOrNull(): BrowserTab? = observableTabsById.values.firstOrNull()

    fun values(): Collection<BrowserTab> = observableTabsById.values

    fun orderedTabs(): List<BrowserTab> = observableTabsById.values.toList()

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
        notifyChanged()
    }

    fun remove(tabId: String): BrowserTab? {
        return tabsById.remove(tabId).also { notifyChanged() }
    }

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
        notifyChanged()
    }

    fun summaries(): List<TabSummary> = observableTabsById.values.map(BrowserTab::toSummary)

    fun clear() {
        tabsById.clear()
        notifyChanged()
    }

    private fun notifyChanged() {
        observableTabsById = tabsById
    }
}
