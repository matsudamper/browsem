package net.matsudamper.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenDownloadsIntentTrackerTest {

    @Test
    fun `未処理の workerId は処理対象`() {
        assertTrue(OpenDownloadsIntentTracker.shouldProcess("worker-1", null))
    }

    @Test
    fun `同一 workerId は処理済みとしてスキップ`() {
        assertFalse(OpenDownloadsIntentTracker.shouldProcess("worker-1", "worker-1"))
    }

    @Test
    fun `別 workerId は処理対象`() {
        assertTrue(OpenDownloadsIntentTracker.shouldProcess("worker-2", "worker-1"))
    }

    @Test
    fun `workerId が null の場合は空文字キーで識別`() {
        assertEquals("", OpenDownloadsIntentTracker.intentKey(null))
        assertFalse(OpenDownloadsIntentTracker.shouldProcess(null, ""))
    }
}
