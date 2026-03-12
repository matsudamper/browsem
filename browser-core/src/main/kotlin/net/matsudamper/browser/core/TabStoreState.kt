package net.matsudamper.browser.core

data class TabStoreState(
    val tabs: List<TabSummary> = emptyList(),
    val selectedTabId: String? = null,
)

data class TabSummary(
    val id: String,
    val title: String,
    val url: String,
    val openerTabId: String? = null,
    val previewBitmapArray: ByteArray? = null,
    val themeColor: Int? = null,
)
