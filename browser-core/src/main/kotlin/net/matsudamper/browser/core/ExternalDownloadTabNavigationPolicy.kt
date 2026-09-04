package net.matsudamper.browser.core

/**
 * 外部 Intent で開いたダウンロード URL タブを閉じた後の遷移先を決める。
 */
object ExternalDownloadTabNavigationPolicy {

    /**
     * 閉じるタブを除いたタブのうち、デフォルトグループに属する最後のタブ ID を返す。
     * デフォルトグループに該当タブがなければ、残りタブの最後を返す。
     * 残りタブがなければ null。
     */
    fun resolveTargetTabAfterClosingExternalDownload(
        state: TabStoreState,
        defaultGroupId: String?,
        excludingTabId: String,
    ): String? {
        val remainingTabs = state.tabs.filter { it.id != excludingTabId }
        if (remainingTabs.isEmpty()) {
            return null
        }
        if (defaultGroupId != null) {
            val lastInDefaultGroup = remainingTabs.lastOrNull { tab ->
                state.tabGroupAssignments[tab.id] == defaultGroupId
            }
            if (lastInDefaultGroup != null) {
                return lastInDefaultGroup.id
            }
        }
        return remainingTabs.last().id
    }
}
