package net.matsudamper.browser.download

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 外部ダウンロードタブで「ダウンロード」確定後にタブを閉じる処理の順序を検証する。
 *
 * BrowserTabScreenState.proceedDownloadFromResponse と同じ順序契約:
 * 1. 通知権限待機
 * 2. WorkManager エンキュー完了
 * 3. タブ閉鎖コールバック
 *
 * エンキュー前にタブを閉じると rememberCoroutineScope がキャンセルされ、
 * ダウンロードが開始されない不具合が起きる（PR #672 レビュー指摘）。
 */
class ProceedDownloadExternalTabOrderTest {

    @Test
    fun tabCloseCallbackRunsOnlyAfterPermissionAndEnqueueComplete() = runTest {
        val events = mutableListOf<String>()
        val permissionGranted = CompletableDeferred<Unit>()

        launch {
            simulateProceedDownloadFromResponse(
                events = events,
                awaitPermission = {
                    events.add("permission_wait")
                    permissionGranted.await()
                    events.add("permission_granted")
                },
                enqueue = {
                    events.add("enqueue_start")
                    delay(50)
                    events.add("enqueue_done")
                },
                onEnqueued = { events.add("tab_close") },
            )
        }

        advanceTimeBy(10)
        assertFalse("権限待機中にタブを閉じてはいけない", events.contains("tab_close"))
        assertFalse("権限待機中にエンキューしてはいけない", events.contains("enqueue_start"))

        permissionGranted.complete(Unit)
        advanceUntilIdle()

        assertEquals(
            listOf(
                "permission_wait",
                "permission_granted",
                "enqueue_start",
                "enqueue_done",
                "tab_close",
            ),
            events,
        )
    }

    @Test
    fun tabCloseCallbackNotInvokedWhenEnqueueFails() = runTest {
        val events = mutableListOf<String>()

        runCatching {
            simulateProceedDownloadFromResponse(
                events = events,
                awaitPermission = { events.add("permission_granted") },
                enqueue = {
                    events.add("enqueue_start")
                    error("enqueue failed")
                },
                onEnqueued = { events.add("tab_close") },
            )
        }

        assertEquals(
            listOf("permission_granted", "enqueue_start", "body_closed"),
            events,
        )
        assertFalse(events.contains("tab_close"))
    }

    private suspend fun simulateProceedDownloadFromResponse(
        events: MutableList<String>,
        awaitPermission: suspend () -> Unit,
        enqueue: suspend () -> Unit,
        onEnqueued: () -> Unit,
    ) {
        var enqueued = false
        try {
            awaitPermission()
            enqueue()
            enqueued = true
            onEnqueued()
        } finally {
            if (!enqueued) {
                events.add("body_closed")
            }
        }
    }
}
