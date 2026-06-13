package net.matsudamper.browser.core

import kotlinx.coroutines.flow.StateFlow

interface TabStore {
    val tabStoreState: StateFlow<TabStoreState>
    fun moveTab(fromIndex: Int, toIndex: Int)

    /**
     * タブを閉じる。
     * @return 閉鎖後に選択されているタブの ID。タブが残っていない場合は null
     */
    fun closeTab(tabId: String): String?
}
