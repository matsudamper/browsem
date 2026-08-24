package net.matsudamper.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextMenuGesturePolicyTest {
    @Test
    fun `タッチ記録がない場合は表示する`() {
        assertTrue(
            shouldShowContextMenuForGesture(
                hasTouchGestureRecord = false,
                isTouchGestureActive = false,
                gestureMoved = false,
                elapsedSinceGestureEndMs = 100_000L,
            )
        )
    }

    @Test
    fun `指を置いたままで移動していなければ表示する`() {
        assertTrue(
            shouldShowContextMenuForGesture(
                hasTouchGestureRecord = true,
                isTouchGestureActive = true,
                gestureMoved = false,
                elapsedSinceGestureEndMs = 0L,
            )
        )
    }

    @Test
    fun `移動を伴うジェスチャーでは表示しない`() {
        assertFalse(
            shouldShowContextMenuForGesture(
                hasTouchGestureRecord = true,
                isTouchGestureActive = true,
                gestureMoved = true,
                elapsedSinceGestureEndMs = 0L,
            )
        )
    }

    @Test
    fun `指を離した直後の猶予内なら表示する`() {
        assertTrue(
            shouldShowContextMenuForGesture(
                hasTouchGestureRecord = true,
                isTouchGestureActive = false,
                gestureMoved = false,
                elapsedSinceGestureEndMs = CONTEXT_MENU_TOUCH_UP_GRACE_MS,
            )
        )
    }

    @Test
    fun `指を離してから猶予を超えたものは表示しない`() {
        assertFalse(
            shouldShowContextMenuForGesture(
                hasTouchGestureRecord = true,
                isTouchGestureActive = false,
                gestureMoved = false,
                elapsedSinceGestureEndMs = CONTEXT_MENU_TOUCH_UP_GRACE_MS + 1,
            )
        )
    }
}
