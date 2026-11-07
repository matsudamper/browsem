package net.matsudamper.browser

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

internal class SessionStateProgress {
    private val progress = MutableStateFlow(Progress(0, 0, false))

    fun received(): Long {
        val next = progress.value.received + 1
        progress.value = progress.value.copy(received = next)
        return next
    }

    fun applied(generation: Long) {
        progress.value = progress.value.copy(applied = generation)
    }

    fun close() {
        progress.value = progress.value.copy(closed = true)
    }

    suspend fun flushAndAwait(
        receiveTimeoutMs: Long,
        completionTimeoutMs: Long,
        flush: () -> Unit,
    ): WaitResult {
        val before = progress.value.received
        flush()
        val reception = withTimeoutOrNull(receiveTimeoutMs) {
            progress.first { it.received > before || it.closed }
        }
        val completed = withTimeoutOrNull(completionTimeoutMs) {
            progress.first { it.applied == it.received || it.closed }
        }
        val current = progress.value
        return WaitResult(
            before = before,
            received = current.received,
            applied = current.applied,
            receiveTimedOut = reception == null,
            timedOut = completed == null,
            closed = current.closed,
        )
    }

    data class WaitResult(
        val before: Long,
        val received: Long,
        val applied: Long,
        val receiveTimedOut: Boolean,
        val timedOut: Boolean,
        val closed: Boolean,
    )

    private data class Progress(val received: Long, val applied: Long, val closed: Boolean)
}
