package net.matsudamper.browser.screen.settings

import java.io.IOException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class WebAuthnSettingsUpdateWorkerTest {
    @Test
    fun `保存失敗後も後続更新を処理する`() = runTest {
        val channel = Channel<WebAuthnSettingsUpdate>(Channel.UNLIMITED)
        val persisted = mutableListOf<Int>()
        val reloaded = mutableListOf<Int>()
        val errors = mutableListOf<Throwable>()

        channel.send(
            WebAuthnSettingsUpdate(
                persist = { throw IOException("save failed") },
                onPersisted = { reloaded += 1 },
            ),
        )
        channel.send(
            WebAuthnSettingsUpdate(
                persist = { persisted += 2 },
                onPersisted = { reloaded += 2 },
            ),
        )
        channel.close()

        processWebAuthnSettingsUpdates(channel, errors::add)

        assertEquals(listOf(2), persisted)
        assertEquals(listOf(2), reloaded)
        assertEquals(1, errors.size)
        assertEquals("save failed", errors.single().message)
    }
}
