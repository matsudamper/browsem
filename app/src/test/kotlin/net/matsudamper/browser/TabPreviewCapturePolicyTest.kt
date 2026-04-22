package net.matsudamper.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TabPreviewCapturePolicyTest {

    @Test
    fun skipsBeforePreviewCaptureReady() {
        assertFalse(
            shouldCaptureTabPreview(
                previewCaptureReady = false,
            )
        )
    }

    @Test
    fun capturesAfterPreviewCaptureReady() {
        assertTrue(
            shouldCaptureTabPreview(
                previewCaptureReady = true,
            )
        )
    }
}
