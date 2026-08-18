package net.matsudamper.browser

import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkLogStoreTest {
    @Test
    fun 記録した順に保持される() {
        val store = NetworkLogStore()

        store.record(listOf(entry("1"), entry("2")))

        assertEquals(listOf("1", "2"), store.entries.value.map { it.requestId })
    }

    @Test
    fun 同じrequestIdは位置を保ったまま更新される() {
        val store = NetworkLogStore()
        store.record(listOf(entry("1"), entry("2")))

        store.record(listOf(entry("1", statusCode = 404)))

        assertEquals(listOf("1", "2"), store.entries.value.map { it.requestId })
        assertEquals(404, store.entries.value.first().statusCode)
    }

    @Test
    fun 上限を超えると古いものから捨てられる() {
        val store = NetworkLogStore(maxEntries = 2)

        store.record(listOf(entry("1"), entry("2"), entry("3")))

        assertEquals(listOf("2", "3"), store.entries.value.map { it.requestId })
    }

    @Test
    fun タブ指定のクリアは他タブを残す() {
        val store = NetworkLogStore()
        store.record(listOf(entry("1", tabId = 1), entry("2", tabId = 2)))

        store.clear(tabId = 1)

        assertEquals(listOf("2"), store.entries.value.map { it.requestId })
    }

    @Test
    fun タブ未指定のクリアは全件消す() {
        val store = NetworkLogStore()
        store.record(listOf(entry("1", tabId = 1), entry("2", tabId = 2)))

        store.clear(tabId = null)

        assertEquals(emptyList<String>(), store.entries.value.map { it.requestId })
    }

    @Test
    fun サイズはContentLengthを優先し無ければ転送量を使う() {
        val withContentLength = entry("1", contentLength = 100, transferred = 40)
        val withoutContentLength = entry("2", contentLength = -1, transferred = 40)

        assertEquals(100, withContentLength.sizeBytes)
        assertEquals(40, withoutContentLength.sizeBytes)
    }

    @Test
    fun webRequestの種別をまとめて判定する() {
        assertEquals(NetworkResourceType.Document, NetworkResourceType.fromWebRequestType("main_frame"))
        assertEquals(NetworkResourceType.Document, NetworkResourceType.fromWebRequestType("sub_frame"))
        assertEquals(NetworkResourceType.Script, NetworkResourceType.fromWebRequestType("script"))
        assertEquals(NetworkResourceType.Image, NetworkResourceType.fromWebRequestType("imageset"))
        assertEquals(NetworkResourceType.Xhr, NetworkResourceType.fromWebRequestType("xmlhttprequest"))
        assertEquals(NetworkResourceType.Other, NetworkResourceType.fromWebRequestType("unknown"))
    }

    private fun entry(
        requestId: String,
        tabId: Int = 1,
        statusCode: Int = 200,
        contentLength: Long = 10,
        transferred: Long = 10,
    ): NetworkLogEntry {
        return NetworkLogEntry(
            requestId = requestId,
            tabId = tabId,
            url = "https://example.com/$requestId",
            method = "GET",
            resourceType = NetworkResourceType.Other,
            statusCode = statusCode,
            mimeType = "text/plain",
            startedAtMillis = 0,
            durationMillis = 0,
            transferredBytes = transferred,
            contentLengthBytes = contentLength,
            fromCache = false,
            error = null,
            requestHeaders = emptyList(),
            responseHeaders = emptyList(),
        )
    }
}
