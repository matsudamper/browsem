package net.matsudamper.browser.core

data class TabStoreState(
    val tabs: List<TabSummary> = listOf(),
    val selectedTabId: String? = null,
    val tabGroupAssignments: Map<String, String> = mapOf(),
)

data class TabSummary(
    val id: String,
    val title: String,
    val url: String,
    val openerTabId: String? = null,
    val previewBitmapArray: ByteArray? = null,
    val themeColor: Int? = null,
    /** 属するプロファイル ID。プロファイル削除時に閉じる対象を判定する */
    val profileId: String = "",
)
