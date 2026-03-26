package net.matsudamper.browser

/** タブ内の履歴エントリを表すデータクラス */
data class TabHistoryEntry(
    val title: String,
    val url: String,
)
