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
                elapsedSinceGestureStartMs = 0L,
                elapsedSinceGestureEndMs = 100_000L,
            )
        )
    }

    @Test
    fun `指を置いたまま長押し相当の時間が経っていれば表示する`() {
        assertTrue(
            shouldShowContextMenuForGesture(
                hasTouchGestureRecord = true,
                isTouchGestureActive = true,
                gestureMoved = false,
                elapsedSinceGestureStartMs = CONTEXT_MENU_MIN_TOUCH_DURATION_MS,
                elapsedSinceGestureEndMs = 5_000L,
            )
        )
    }

    @Test
    fun `指を置いた直後に届いたものは前のジェスチャー由来として表示しない`() {
        assertFalse(
            shouldShowContextMenuForGesture(
                hasTouchGestureRecord = true,
                isTouchGestureActive = true,
                gestureMoved = false,
                elapsedSinceGestureStartMs = CONTEXT_MENU_MIN_TOUCH_DURATION_MS - 1,
                elapsedSinceGestureEndMs = 5_000L,
            )
        )
    }

    @Test
    fun `指を置いたままでも移動を伴うジェスチャーでは表示しない`() {
        assertFalse(
            shouldShowContextMenuForGesture(
                hasTouchGestureRecord = true,
                isTouchGestureActive = true,
                gestureMoved = true,
                elapsedSinceGestureStartMs = 5_000L,
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
                elapsedSinceGestureStartMs = 5_000L,
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
                elapsedSinceGestureStartMs = 5_000L,
                elapsedSinceGestureEndMs = CONTEXT_MENU_TOUCH_UP_GRACE_MS + 1,
            )
        )
    }

    @Test
    fun `指を離した後は移動を伴うジェスチャーだったものは表示しない`() {
        assertFalse(
            shouldShowContextMenuForGesture(
                hasTouchGestureRecord = true,
                isTouchGestureActive = false,
                gestureMoved = true,
                elapsedSinceGestureStartMs = 5_000L,
                elapsedSinceGestureEndMs = 0L,
            )
        )
    }
}
