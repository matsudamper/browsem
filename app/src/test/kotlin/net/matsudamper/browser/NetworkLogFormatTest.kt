package net.matsudamper.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class NetworkLogFormatTest {
    @Test
    fun バイト数を単位付きで表示する() {
        assertEquals("-", NetworkLogFormat.formatBytes(-1))
        assertEquals("0 B", NetworkLogFormat.formatBytes(0))
        assertEquals("512 B", NetworkLogFormat.formatBytes(512))
        assertEquals("1.0 KB", NetworkLogFormat.formatBytes(1024))
        assertEquals("1.5 MB", NetworkLogFormat.formatBytes(1024 * 1024 * 3 / 2))
        assertEquals("2.0 GB", NetworkLogFormat.formatBytes(2L * 1024 * 1024 * 1024))
    }

    @Test
    fun 所要時間を秒とミリ秒で出し分ける() {
        assertEquals("0 ms", NetworkLogFormat.formatDuration(0))
        assertEquals("999 ms", NetworkLogFormat.formatDuration(999))
        assertEquals("1.50 s", NetworkLogFormat.formatDuration(1500))
    }

    @Test
    fun 開始時刻を時分秒で表示する() {
        assertEquals("-", NetworkLogFormat.formatTime(0))
        assertEquals(
            "12:34:56.789",
            NetworkLogFormat.formatTime(
                epochMillis = Instant.parse("2026-08-18T12:34:56.789Z").toEpochMilli(),
                zoneId = ZoneId.of("UTC"),
            ),
        )
    }

    @Test
    fun URLからファイル名を取り出す() {
        assertEquals("main.js", NetworkLogFormat.displayName("https://example.com/js/main.js?v=1"))
        assertEquals("example.com", NetworkLogFormat.displayName("https://example.com/"))
        assertEquals("example.com", NetworkLogFormat.displayName("https://example.com"))
    }

    @Test
    fun URLからホストを取り出す() {
        assertEquals("cdn.example.com", NetworkLogFormat.hostOf("https://cdn.example.com/a.png"))
        assertEquals("", NetworkLogFormat.hostOf("not a url"))
    }

    @Test
    fun ステータスの区分を判定する() {
        assertEquals(
            NetworkLogUiState.StatusKind.Success,
            NetworkLogFormat.statusKind(200, null),
        )
        assertEquals(
            NetworkLogUiState.StatusKind.Redirect,
            NetworkLogFormat.statusKind(301, null),
        )
        assertEquals(
            NetworkLogUiState.StatusKind.ClientError,
            NetworkLogFormat.statusKind(404, null),
        )
        assertEquals(
            NetworkLogUiState.StatusKind.ServerError,
            NetworkLogFormat.statusKind(500, null),
        )
        assertEquals(
            NetworkLogUiState.StatusKind.Pending,
            NetworkLogFormat.statusKind(0, null),
        )
        assertEquals(
            NetworkLogUiState.StatusKind.Failed,
            NetworkLogFormat.statusKind(200, "NS_ERROR_ABORT"),
        )
    }

    @Test
    fun 種別と検索文字列で絞り込む() {
        val script = entry(url = "https://example.com/main.js", type = NetworkResourceType.Script)

        assertTrue(
            NetworkLogFormat.matches(script, NetworkLogUiState.ResourceFilter.All, ""),
        )
        assertTrue(
            NetworkLogFormat.matches(script, NetworkLogUiState.ResourceFilter.Script, "MAIN"),
        )
        assertFalse(
            NetworkLogFormat.matches(script, NetworkLogUiState.ResourceFilter.Image, ""),
        )
        assertFalse(
            NetworkLogFormat.matches(script, NetworkLogUiState.ResourceFilter.All, "other"),
        )
    }

    private fun entry(url: String, type: NetworkResourceType): NetworkLogEntry {
        return NetworkLogEntry(
            requestId = "1",
            tabId = 1,
            url = url,
            method = "GET",
            resourceType = type,
            statusCode = 200,
            mimeType = "application/javascript",
            startedAtMillis = 0,
            durationMillis = 0,
            transferredBytes = 0,
            contentLengthBytes = 0,
            fromCache = false,
            error = null,
            requestHeaders = emptyList(),
            responseHeaders = emptyList(),
        )
    }
}
