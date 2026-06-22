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

    /**
     * タブを即時に閉じる（一覧・永続化から削除する）が、[undoCloseTab] で復元できるよう
     * 直前に閉じたタブを内部で保持する。既に保持中のタブがある場合は先にそれを確定（破棄）する。
     * @param nextSelectedTabId 閉鎖後に選択するタブの ID。null の場合は実装側で決定する
     * @return 閉鎖後に選択されているタブの ID。タブが残っていない場合は null
     */
    fun closeTabWithUndo(tabId: String, nextSelectedTabId: String?): String?

    /**
     * 直前に [closeTabWithUndo] で閉じたタブを元の位置へ復元する。
     * @return 復元したタブの ID。保持中のタブがない場合は null
     */
    fun undoCloseTab(): String?

    /** [closeTabWithUndo] で保持中のタブの破棄を確定する。保持中のタブがなければ何もしない */
    fun confirmClosedTab()
}
