package net.matsudamper.browser.core

import kotlinx.coroutines.flow.StateFlow

interface TabStore {
    val tabStoreState: StateFlow<TabStoreState>
    fun moveTab(fromIndex: Int, toIndex: Int)
}
