package net.matsudamper.browser.feature.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackDiagnosticsTest {
    @Test
    fun 許可と遮断の理由および直近イベントを保存する() {
        val saved = mutableListOf<String>()
        val diagnostics = PlaybackDiagnostics { tag, title, body -> saved.add("$tag\n$title\n$body") }
        diagnostics.trace("HOST enteredBackground", 10)
        diagnostics.trace("WX setActive=true", 20)

        diagnostics.record("判定・許可", "tab:media", "origin=通知 reason=通知操作の猶予内 commandElapsedMs=50", 50)
        diagnostics.record("遮断", "tab:next", "origin=アンミュート stopPath=content script", 60)

        assertEquals(2, saved.size)
        assertTrue(saved[0].contains("MediaPlayback/判定・許可"))
        assertTrue(saved[0].contains("commandElapsedMs=50"))
        assertTrue(saved[1].contains("WX setActive=true"))
        assertTrue(saved[1].contains("stopPath=content script"))
    }

    @Test
    fun 重複は即保存せず後から抑制件数を集計する() {
        val saved = mutableListOf<String>()
        val diagnostics = PlaybackDiagnostics { _, _, body -> saved.add(body) }
        diagnostics.record("遮断", "tab", "origin=play", 0)
        diagnostics.record("遮断", "tab", "origin=play", 100)
        diagnostics.record("遮断", "tab", "origin=play", 200)
        diagnostics.flush(3_999, force = false)
        assertEquals(1, saved.size)
        diagnostics.flush(4_000, force = false)
        assertEquals(2, saved.size)
        assertTrue(saved.last().contains("suppressedDuplicates=2"))
        diagnostics.flush(8_000, force = false)
        assertEquals(2, saved.size)
    }

    @Test
    fun 前面復帰で期限前の抑制件数も残す() {
        val saved = mutableListOf<String>()
        val diagnostics = PlaybackDiagnostics { _, _, body -> saved.add(body) }
        diagnostics.record("操作", "tab", "reason=policyReady待ち", 0)
        diagnostics.record("操作", "tab", "reason=policyReady待ち", 10)
        diagnostics.flush(20, force = true)
        assertTrue(saved.last().contains("suppressedDuplicates=1"))
    }

    @Test
    fun 時刻の異なる重複も次の集計時刻を失わない() {
        val saved = mutableListOf<String>()
        val diagnostics = PlaybackDiagnostics { _, _, body -> saved.add(body) }
        diagnostics.record("遮断", "a", "origin=a", 0)
        diagnostics.record("遮断", "b", "origin=b", 3_000)
        diagnostics.record("遮断", "b", "origin=b", 3_500)
        diagnostics.flush(4_000, force = false)
        assertEquals(3_000L, diagnostics.nextFlushDelayMs(4_000))
        diagnostics.flush(7_000, force = false)
        assertTrue(saved.last().contains("origin=b"))
        assertTrue(saved.last().contains("suppressedDuplicates=1"))
        assertNull(diagnostics.nextFlushDelayMs(7_000))
    }

    @Test
    fun リングバッファと本文を上限内に収める() {
        val saved = mutableListOf<String>()
        val diagnostics = PlaybackDiagnostics { _, _, body -> saved.add(body) }
        diagnostics.trace("破棄される古いイベント", 0)
        repeat(100) { diagnostics.trace("event=$it " + "x".repeat(10_000), it.toLong()) }
        diagnostics.record("遮断", "tab", "src=" + "a".repeat(50_000), 100)
        val body = saved.single()
        assertFalse(body.contains("破棄される古いイベント"))
        assertTrue(body.contains("event=99"))
        assertTrue(body.length <= PlaybackDiagnostics.MAX_BODY_LENGTH)
        assertTrue(body.contains("recentEvents:"))
    }

    @Test
    fun 重複キーが増えても退避前に集計を残す() {
        val saved = mutableListOf<String>()
        val diagnostics = PlaybackDiagnostics { _, _, body -> saved.add(body) }
        diagnostics.record("遮断", "old", "origin=old", 0)
        diagnostics.record("遮断", "old", "origin=old", 1)
        repeat(32) { diagnostics.record("遮断", "tab$it", "origin=new", 2) }
        assertTrue(saved.any { it.contains("origin=old") && it.contains("suppressedDuplicates=1") })
    }
}
