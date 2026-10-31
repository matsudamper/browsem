package net.matsudamper.browser.feature.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundPlaybackGuardTest {
    private val guard = BackgroundPlaybackGuard<String>()

    @Test
    fun 継続許可の判定には一致したidentityを含める() {
        val identity = PlaybackIdentity("a", "A", "element1")
        guard.onEnteredBackground(mapOf("tab" to identity))
        val result = guard.evaluate("tab", true, "a", "A", "element1", 100)
        assertFalse(result.shouldPause)
        assertEquals(identity, result.matchedIdentity)
        assertEquals("閉じた時点または通知操作で許可したidentityの継続", result.reason)
    }

    @Test
    fun 通知許可の判定にはコマンドからの経過時間を含める() {
        guard.onEnteredBackground(mapOf())
        guard.onUserTransportCommand("tab", 100)
        val result = guard.evaluate("tab", true, "a", "A", "element1", 250)
        assertFalse(result.shouldPause)
        assertEquals(150L, result.commandElapsedMs)
        assertEquals("通知操作の猶予内", result.reason)
    }

    @Test
    fun 同じタイトルとURLでも要素が違えば継続にしない() {
        guard.onEnteredBackground(mapOf("tab" to PlaybackIdentity("a", "X", "element1")))

        assertTrue(guard.shouldPause("tab", true, "a", "X", "element2", 1_000L))
    }

    @Test
    fun ページが確認した画質変更はIDが同じなら継続にする() {
        guard.onEnteredBackground(mapOf("tab" to PlaybackIdentity("quality1", "A", "element1")))

        assertFalse(guard.shouldPause("tab", true, "quality2", "A", "element1", 1_000L))
    }

    @Test
    fun 許可のない背景タブは通知を起動しない() {
        guard.onEnteredBackground(mapOf())

        assertFalse(guard.canActivate("tab", PlaybackIdentity("a", "A", "element1"), 1_000L))
    }

    @Test
    fun 継続中でも別のメディアの通知を起動しない() {
        guard.onEnteredBackground(mapOf("tab" to PlaybackIdentity("a", "X", "element1")))

        assertFalse(guard.canActivate("tab", PlaybackIdentity("a", "X", "element2"), 1_000L))
    }

    @Test
    fun 次へ操作の途中で古い動画が終了しても許可は残す() {
        guard.onEnteredBackground(mapOf("tab" to PlaybackIdentity("a", "A", "element1")))
        guard.onUserTransportCommand("tab", 1_000L)
        guard.onPlaybackEnded("tab", "element1")

        assertFalse(guard.shouldPause("tab", true, "b", "B", "element2", 2_000L))
    }

    @Test
    fun 遅れて届いた前の動画の終了で次の再生を取り消さない() {
        guard.onEnteredBackground(mapOf("tab" to PlaybackIdentity("a", "A", "element1")))
        guard.onUserTransportCommand("tab", 1_000L)
        guard.shouldPause("tab", true, "b", "B", "element2", 2_000L)
        guard.onPlaybackEnded("tab", "element1")

        assertFalse(guard.shouldPause("tab", true, "b", "B", "element2", 3_000L))
    }

    @Test
    fun 前面では再生を止めない() {
        val pause = guard.shouldPause(
            session = "tab",
            isPlaying = true,
            mediaSrc = "https://example.com/new",
            title = "new",
            playbackId = "",
            nowMs = 0L,
        )

        assertFalse(pause)
    }

    @Test
    fun 閉じた時点で鳴っていた同じ再生の再開は止めない() {
        enterBackgroundPlaying(src = "https://example.com/a", title = "A")

        val pause = guard.shouldPause(
            session = "tab",
            isPlaying = true,
            mediaSrc = "https://example.com/a",
            title = "A",
            playbackId = "",
            nowMs = 1_000L,
        )

        assertFalse(pause)
    }

    @Test
    fun タイトルが同じでも配信URLが変わっただけでは許さない() {
        enterBackgroundPlaying(src = "https://example.com/a?q=1", title = "A")

        val pause = guard.shouldPause(
            session = "tab",
            isPlaying = true,
            mediaSrc = "https://example.com/a?q=2",
            title = "A",
            playbackId = "",
            nowMs = 1_000L,
        )

        assertTrue(pause)
    }

    @Test
    fun URLとタイトルが両方変わった再生は止める() {
        enterBackgroundPlaying(src = "https://example.com/a", title = "A")

        val pause = guard.shouldPause(
            session = "tab",
            isPlaying = true,
            mediaSrc = "https://example.com/b",
            title = "B",
            playbackId = "",
            nowMs = 1_000L,
        )

        assertTrue(pause)
    }

    @Test
    fun 止まっていたタブが背面で再生を始めたら止める() {
        guard.onEnteredBackground(mapOf())

        val pause = guard.shouldPause(
            session = "tab",
            isPlaying = true,
            mediaSrc = "https://example.com/a",
            title = "A",
            playbackId = "",
            nowMs = 1_000L,
        )

        assertTrue(pause)
    }

    @Test
    fun 通知操作のあとに別の再生が始まったら許す() {
        enterBackgroundPlaying(src = "https://example.com/a", title = "A")
        guard.onUserTransportCommand(session = "tab", nowMs = 1_000L)

        val samePlayback = guard.shouldPause(
            session = "tab",
            isPlaying = true,
            mediaSrc = "https://example.com/a",
            title = "A",
            playbackId = "",
            nowMs = 1_100L,
        )
        val nextPlayback = guard.shouldPause(
            session = "tab",
            isPlaying = true,
            mediaSrc = "https://example.com/b",
            title = "B",
            playbackId = "",
            nowMs = 2_000L,
        )

        assertFalse(samePlayback)
        assertFalse(nextPlayback)
    }

    @Test
    fun 通知操作で許した次の再生のさらに次は止める() {
        enterBackgroundPlaying(src = "https://example.com/a", title = "A")
        guard.onUserTransportCommand(session = "tab", nowMs = 1_000L)
        guard.shouldPause(
            session = "tab",
            isPlaying = true,
            mediaSrc = "https://example.com/b",
            title = "B",
            playbackId = "",
            nowMs = 2_000L,
        )

        val pause = guard.shouldPause(
            session = "tab",
            isPlaying = true,
            mediaSrc = "https://example.com/c",
            title = "C",
            playbackId = "",
            nowMs = 3_000L,
        )

        assertTrue(pause)
    }

    @Test
    fun 通知操作は他のタブの再生を許さない() {
        enterBackgroundPlaying(src = "https://example.com/a", title = "A")
        guard.onUserTransportCommand(session = "tab", nowMs = 1_000L)

        val pause = guard.shouldPause(
            session = "other",
            isPlaying = true,
            mediaSrc = "https://example.com/b",
            title = "B",
            playbackId = "",
            nowMs = 1_100L,
        )

        assertTrue(pause)
    }

    @Test
    fun 通知操作は一定時間で失効する() {
        enterBackgroundPlaying(src = "https://example.com/a", title = "A")
        guard.onUserTransportCommand(session = "tab", nowMs = 0L)

        val pause = guard.shouldPause(
            session = "tab",
            isPlaying = true,
            mediaSrc = "https://example.com/b",
            title = "B",
            playbackId = "",
            nowMs = BackgroundPlaybackGuard.USER_COMMAND_WINDOW_MS + 1L,
        )

        assertTrue(pause)
    }

    @Test
    fun 前面に戻ったあとは再生を止めない() {
        guard.onEnteredBackground(mapOf())
        guard.onEnteredForeground()

        val pause = guard.shouldPause(
            session = "tab",
            isPlaying = true,
            mediaSrc = "https://example.com/a",
            title = "A",
            playbackId = "",
            nowMs = 1_000L,
        )

        assertFalse(pause)
    }

    @Test
    fun 一時停止のsnapshotは止めない() {
        guard.onEnteredBackground(mapOf())

        val pause = guard.shouldPause(
            session = "tab",
            isPlaying = false,
            mediaSrc = "https://example.com/a",
            title = "A",
            playbackId = "",
            nowMs = 1_000L,
        )

        assertFalse(pause)
    }

    @Test
    fun 明示的に止めたあとの同じ再生は許さない() {
        enterBackgroundPlaying(src = "https://example.com/a", title = "A")
        guard.onExplicitStop("tab")

        val pause = guard.shouldPause(
            session = "tab",
            isPlaying = true,
            mediaSrc = "https://example.com/a",
            title = "A",
            playbackId = "",
            nowMs = 2_000L,
        )

        assertTrue(pause)
    }

    @Test
    fun 明示的に止めたあとに通知から再生したら許す() {
        enterBackgroundPlaying(src = "https://example.com/a", title = "A")
        guard.onExplicitStop("tab")
        guard.onUserTransportCommand(session = "tab", nowMs = 2_000L)

        val pause = guard.shouldPause(
            session = "tab",
            isPlaying = true,
            mediaSrc = "https://example.com/a",
            title = "A",
            playbackId = "",
            nowMs = 2_100L,
        )

        assertFalse(pause)
    }

    @Test
    fun 明示停止は届く前の再生許可も消す() {
        enterBackgroundPlaying(src = "https://example.com/a", title = "A")
        guard.onUserTransportCommand(session = "tab", nowMs = 1_000L)
        guard.onExplicitStop("tab")

        val pause = guard.shouldPause(
            session = "tab",
            isPlaying = true,
            mediaSrc = "https://example.com/b",
            title = "B",
            playbackId = "",
            nowMs = 2_000L,
        )

        assertTrue(pause)
    }

    @Test
    fun 停止の通知は連続した再試行では一度だけ出す() {
        val notice = BlockedPlaybackNotice(repeatIntervalMs = 4_000L)

        assertTrue(notice.onBlocked(nowMs = 1_000L))
        assertFalse(notice.onBlocked(nowMs = 2_000L))
        assertTrue(notice.onBlocked(nowMs = 5_000L))
    }

    @Test
    fun 画面オフ中に止めた再生は前面に戻ったときにも知らせる() {
        val notice = BlockedPlaybackNotice(repeatIntervalMs = 4_000L)
        notice.onBlocked(nowMs = 1_000L)

        assertTrue(notice.onEnteredForeground(nowMs = 10_000L))
        assertFalse(notice.onEnteredForeground(nowMs = 20_000L))
    }

    @Test
    fun 直前に出した停止の通知は前面復帰では重ねない() {
        val notice = BlockedPlaybackNotice(repeatIntervalMs = 4_000L)
        notice.onBlocked(nowMs = 1_000L)

        assertFalse(notice.onEnteredForeground(nowMs = 2_000L))
    }

    private fun enterBackgroundPlaying(src: String, title: String) {
        guard.onEnteredBackground(
            mapOf("tab" to PlaybackIdentity(mediaSrc = src, title = title, playbackId = "")),
        )
    }
}
