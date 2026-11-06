package net.matsudamper.browser.feature.media

internal class PlaybackDiagnostics(
    private val save: (tag: String, title: String, body: String) -> Unit,
) {
    private val recentEvents = ArrayDeque<String>()
    private val entries = linkedMapOf<String, Entry>()

    @Synchronized
    fun trace(message: String, nowMs: Long) {
        recentEvents.addLast("t=$nowMs ${message.take(MAX_TRACE_LENGTH)}")
        while (recentEvents.size > MAX_TRACE_ENTRIES || recentEvents.sumOf { it.length } > MAX_TRACE_CHARS) {
            recentEvents.removeFirst()
        }
    }

    @Synchronized
    fun record(kind: String, key: String, details: String, nowMs: Long) {
        val boundedKey = "${kind.take(40)}:${key.take(512)}"
        val entry = entries[boundedKey]
        if (entry != null) {
            entry.details = details.take(MAX_DETAILS_LENGTH)
            if (nowMs - entry.savedAtMs < REPEAT_INTERVAL_MS) {
                entry.suppressed++
                return
            }
            persist(entry, nowMs)
            return
        }
        if (entries.size >= MAX_DUPLICATE_KEYS) {
            val oldest = entries.entries.first()
            if (oldest.value.suppressed > 0) persist(oldest.value, nowMs)
            entries.remove(oldest.key)
        }
        val next = Entry(kind.take(40), details.take(MAX_DETAILS_LENGTH), nowMs, 0)
        entries[boundedKey] = next
        persist(next, nowMs)
    }

    @Synchronized
    fun flush(nowMs: Long, force: Boolean) {
        entries.values.filter { it.suppressed > 0 && (force || nowMs - it.savedAtMs >= REPEAT_INTERVAL_MS) }
            .forEach { persist(it, nowMs) }
    }

    @Synchronized
    fun nextFlushDelayMs(nowMs: Long): Long? {
        return entries.values.filter { it.suppressed > 0 }
            .minOfOrNull { (it.savedAtMs + REPEAT_INTERVAL_MS - nowMs).coerceAtLeast(1L) }
    }

    private fun persist(entry: Entry, nowMs: Long) {
        val body = buildString {
            appendLine("event=${entry.kind} t=$nowMs")
            appendLine(entry.details)
            appendLine("suppressedDuplicates=${entry.suppressed}")
            appendLine("recentEvents:")
            recentEvents.forEach { appendLine(it) }
        }.take(MAX_BODY_LENGTH)
        save("MediaPlayback/${entry.kind}", "メディア: ${entry.kind}", body)
        entry.savedAtMs = nowMs
        entry.suppressed = 0
    }

    private data class Entry(
        val kind: String,
        var details: String,
        var savedAtMs: Long,
        var suppressed: Int,
    )

    companion object {
        const val REPEAT_INTERVAL_MS = 4_000L
        const val MAX_BODY_LENGTH = 20_480
        private const val MAX_TRACE_ENTRIES = 64
        private const val MAX_TRACE_LENGTH = 512
        private const val MAX_TRACE_CHARS = 12_288
        private const val MAX_DETAILS_LENGTH = 4_096
        private const val MAX_DUPLICATE_KEYS = 32
    }
}
