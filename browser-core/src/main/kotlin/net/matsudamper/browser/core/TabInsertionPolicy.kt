package net.matsudamper.browser.core

object TabInsertionPolicy {
    /**
     * 新しいタブを挿入するインデックスを計算する。
     * openerTabId が指定されていてリストに存在する場合はその次のインデックス、
     * openerTabId がなく selectedTabId が存在する場合はその次のインデックス、
     * いずれもない場合は末尾のインデックスを返す。
     */
    fun resolveInsertionIndex(
        tabIds: List<String>,
        openerTabId: String?,
        selectedTabId: String? = null,
    ): Int {
        if (openerTabId != null) {
            val openerIndex = tabIds.indexOf(openerTabId).takeIf { it >= 0 }
            if (openerIndex != null) return openerIndex + 1
        }
        if (selectedTabId != null) {
            val selectedIndex = tabIds.indexOf(selectedTabId).takeIf { it >= 0 }
            if (selectedIndex != null) return selectedIndex + 1
        }
        return tabIds.size
    }
}
