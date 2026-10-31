package net.matsudamper.browser.feature.media

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MediaSessionBridgeCommandTest {
    @After
    fun tearDown() {
        MediaSessionBridge.userTransportCommandListener = null
        MediaSessionBridge.userPauseCommandListener = null
        MediaSessionBridge.deactivate()
    }

    @Test
    fun 内部操作はユーザーの再生許可や停止通知を発行しない() {
        var transportCount = 0
        var pauseCount = 0
        MediaSessionBridge.userTransportCommandListener = { transportCount++ }
        MediaSessionBridge.userPauseCommandListener = { pauseCount++ }

        MediaSessionBridge.play()
        MediaSessionBridge.pause()
        MediaSessionBridge.stop()
        MediaSessionBridge.nextTrack()
        MediaSessionBridge.previousTrack()

        assertEquals(0, transportCount)
        assertEquals(0, pauseCount)
    }

    @Test
    fun 通知の再生はページ側の準備ができるまで保留できる() {
        var pendingCommand: (() -> Unit)? = null
        MediaSessionBridge.userTransportCommandListener = { pendingCommand = it }

        assertNull(pendingCommand)
        MediaSessionBridge.playFromUser()
        assertNotNull(pendingCommand)
        pendingCommand?.invoke()
    }

    @Test
    fun 通知の一時停止と停止だけが明示停止を通知する() {
        var pauseCount = 0
        MediaSessionBridge.userPauseCommandListener = { pauseCount++ }

        MediaSessionBridge.pauseFromUser()
        MediaSessionBridge.stopFromUser()

        assertEquals(2, pauseCount)
    }
}
