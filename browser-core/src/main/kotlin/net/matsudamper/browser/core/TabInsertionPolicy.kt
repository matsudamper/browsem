package net.matsudamper.browser.core

object TabInsertionPolicy {
    /**
     * 新しいタブを挿入するインデックスを計算する。
     * openerTabId が指定されていてリストに存在する場合はその次のインデックス、
     * そうでない場合は末尾のインデックスを返す。
     */
    fun resolveInsertionIndex(tabIds: List<String>, openerTabId: String?): Int {
        if (openerTabId == null) return tabIds.size
        val openerIndex = tabIds.indexOf(openerTabId)
        return if (openerIndex < 0) tabIds.size else openerIndex + 1
    }
}
