package net.matsudamper.browser.feature.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundPlaybackGuardTest {
    private val guard = BackgroundPlaybackGuard<String>()

    @Test
    fun 前面では再生を止めない() {
        val pause = guard.shouldPause(
            session = "tab",
            isPlaying = true,
            mediaSrc = "https://example.com/new",
            title = "new",
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
            nowMs = 1_000L,
        )

        assertFalse(pause)
    }

    @Test
    fun 配信URLだけ変わってもタイトルが同じなら止めない() {
        enterBackgroundPlaying(src = "https://example.com/a?q=1", title = "A")

        val pause = guard.shouldPause(
            session = "tab",
            isPlaying = true,
            mediaSrc = "https://example.com/a?q=2",
            title = "A",
            nowMs = 1_000L,
        )

        assertFalse(pause)
    }

    @Test
    fun URLとタイトルが両方変わった再生は止める() {
        enterBackgroundPlaying(src = "https://example.com/a", title = "A")

        val pause = guard.shouldPause(
            session = "tab",
            isPlaying = true,
            mediaSrc = "https://example.com/b",
            title = "B",
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
            nowMs = 1_100L,
        )
        val nextPlayback = guard.shouldPause(
            session = "tab",
            isPlaying = true,
            mediaSrc = "https://example.com/b",
            title = "B",
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
            nowMs = 2_000L,
        )

        val pause = guard.shouldPause(
            session = "tab",
            isPlaying = true,
            mediaSrc = "https://example.com/c",
            title = "C",
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
            nowMs = 1_000L,
        )

        assertFalse(pause)
    }

    private fun enterBackgroundPlaying(src: String, title: String) {
        guard.onEnteredBackground(
            mapOf("tab" to PlaybackIdentity(mediaSrc = src, title = title)),
        )
    }
}
