package net.matsudamper.browser.feature.networklog

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * ページの通信ログを保持するストア。
 * 無制限に溜めるとメモリを圧迫するため、[maxEntries] を超えた分は古いものから捨てる。
 */
class NetworkLogStore(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    private val _entries = MutableStateFlow<List<NetworkLogEntry>>(emptyList())

    /** 記録順（古い順）のログ */
    val entries: StateFlow<List<NetworkLogEntry>> = _entries.asStateFlow()

    /**
     * ログを記録する。
     * 同じ requestId のエントリが既にある場合は、その位置のまま新しい内容へ差し替える。
     */
    fun record(entries: List<NetworkLogEntry>) {
        if (entries.isEmpty()) return
        _entries.update { current ->
            val merged = current.toMutableList()
            entries.forEach { entry ->
                val index = merged.indexOfFirst { it.requestId == entry.requestId }
                if (index >= 0) {
                    merged[index] = entry
                } else {
                    merged.add(entry)
                }
            }
            if (merged.size > maxEntries) {
                merged.subList(0, merged.size - maxEntries).clear()
            }
            merged.toList()
        }
    }

    /** [tabId] を指定した場合はそのタブの分だけ、null の場合は全件を削除する */
    fun clear(tabId: Int?) {
        _entries.update { current ->
            if (tabId == null) {
                emptyList()
            } else {
                current.filterNot { it.tabId == tabId }
            }
        }
    }

    companion object {
        const val DEFAULT_MAX_ENTRIES: Int = 500
    }
}
