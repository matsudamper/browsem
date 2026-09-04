package net.matsudamper.browser.download

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
 * proceedDownloadFromResponse の順序契約:
 * 1. 通知権限待機
 * 2. WorkManager エンキュー完了
 * 3. タブ閉鎖コールバック
 *
 * エンキュー前にタブを閉じると rememberCoroutineScope がキャンセルされ、
 * ダウンロードが開始されない不具合が起きる（PR #672 レビュー指摘）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProceedDownloadExternalTabOrderTest {

    @Test
    fun tabCloseCallbackRunsOnlyAfterPermissionAndEnqueueComplete() = runTest {
        val events = mutableListOf<String>()
        val permissionGranted = CompletableDeferred<Unit>()

        launch {
            proceedDownloadFromResponse(
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
            proceedDownloadFromResponse(
                awaitPermission = { events.add("permission_granted") },
                enqueue = {
                    events.add("enqueue_start")
                    error("enqueue failed")
                },
                onEnqueued = { events.add("tab_close") },
                onEnqueueFailed = { events.add("body_closed") },
            )
        }

        assertEquals(
            listOf("permission_granted", "enqueue_start", "body_closed"),
            events,
        )
        assertFalse(events.contains("tab_close"))
    }
}
