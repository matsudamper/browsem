package net.matsudamper.browser.data.crashlog

data class CrashLogListItem(
    val id: Long,
    val occurredAt: Long,
    val tag: String,
    val title: String,
)
