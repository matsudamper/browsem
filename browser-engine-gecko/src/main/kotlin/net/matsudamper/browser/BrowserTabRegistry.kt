package net.matsudamper.browser

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.setValue
import net.matsudamper.browser.core.TabSummary

internal class BrowserTabRegistry {
    // move は要素を変えず挿入順だけを変えるが Map.equals は順序を見ないため、
    // 既定の構造等価ポリシーでは順序変更の代入が破棄される。
    private var tabsById: Map<String, BrowserTab> by mutableStateOf(
        value = linkedMapOf(),
        policy = referentialEqualityPolicy(),
    )

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
        replaceWith(orderedTabs)
    }

    fun remove(tabId: String): BrowserTab? {
        val removed = tabsById[tabId] ?: return null
        replaceWith(tabsById.values.filterNot { tab -> tab.tabId == tabId })
        return removed
    }

    fun move(fromIndex: Int, toIndex: Int) {
        val orderedTabs = tabsById.values.toMutableList()
        if (fromIndex !in orderedTabs.indices || toIndex !in orderedTabs.indices) {
            return
        }
        orderedTabs.add(toIndex, orderedTabs.removeAt(fromIndex))
        replaceWith(orderedTabs)
    }

    fun summaries(): List<TabSummary> = tabsById.values.map(BrowserTab::toSummary)

    fun clear() {
        tabsById = linkedMapOf()
    }

    private fun replaceWith(orderedTabs: List<BrowserTab>) {
        tabsById = orderedTabs.associateByTo(LinkedHashMap()) { tab -> tab.tabId }
    }
}
