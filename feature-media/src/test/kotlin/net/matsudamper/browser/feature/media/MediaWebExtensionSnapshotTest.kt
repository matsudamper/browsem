package net.matsudamper.browser.feature.media

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaWebExtensionSnapshotTest {

    @Test
    fun payloadのタイミング情報をそのまま採用する() {
        val previousSnapshot =
            SessionPlaybackSnapshot(
                isActive = true,
                isPlaying = true,
                title = "Song A",
                artist = "Artist",
                album = "Album",
                durationMs = 180_000L,
                positionMs = 42_000L,
                features = 123L,
            )

        val snapshot =
            buildSessionPlaybackSnapshot(
                previousSnapshot = previousSnapshot,
                payload =
                    WebExtensionPlaybackPayload(
                        isActive = true,
                        isPlaying = true,
                        title = "Song A",
                        artist = "Artist",
                        album = "Album",
                        durationMs = 120_000L,
                        positionMs = 10_000L,
                    ),
            )

        assertEquals(120_000L, snapshot.durationMs)
        assertEquals(10_000L, snapshot.positionMs)
        assertEquals(123L, snapshot.features)
    }

    @Test
    fun payloadが0を返した時は前回値を引き継がない() {
        val previousSnapshot =
            SessionPlaybackSnapshot(
                isActive = true,
                isPlaying = true,
                title = "Song A",
                artist = "Artist",
                album = "Album",
                durationMs = 180_000L,
                positionMs = 42_000L,
            )

        val snapshot =
            buildSessionPlaybackSnapshot(
                previousSnapshot = previousSnapshot,
                payload =
                    WebExtensionPlaybackPayload(
                        isActive = true,
                        isPlaying = true,
                        title = "Song A",
                        artist = "Artist",
                        album = "Album",
                        durationMs = 0L,
                        positionMs = 0L,
                    ),
            )

        assertEquals(0L, snapshot.durationMs)
        assertEquals(0L, snapshot.positionMs)
    }
}
