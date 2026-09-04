package net.matsudamper.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardShowPolicyTest {
    @Test
    fun `navigation直後のautofocusではキーボードを出さない`() {
        val policy = KeyboardShowPolicy()
        policy.onNavigationStarted(nowMs = 1_000L)

        assertFalse(policy.shouldShowSoftInput(nowMs = 1_100L))
    }

    @Test
    fun `ユーザー操作後はキーボードを出す`() {
        val policy = KeyboardShowPolicy()
        policy.onNavigationStarted(nowMs = 1_000L)
        policy.onUserGesture()

        assertTrue(policy.shouldShowSoftInput(nowMs = 1_100L))
    }

    @Test
    fun `タブ表示直後のフォーカス復元ではキーボードを出さない`() {
        val policy = KeyboardShowPolicy()
        policy.onNavigationStarted(nowMs = 0L)
        policy.onSessionShownWithoutUserGesture(nowMs = 10_000L)

        assertFalse(policy.shouldShowSoftInput(nowMs = 10_100L))
    }

    @Test
    fun `抑制開始から十分時間が経過したフォーカスはユーザー操作とみなす`() {
        val policy = KeyboardShowPolicy()
        policy.onNavigationStarted(nowMs = 1_000L)

        assertTrue(
            policy.shouldShowSoftInput(
                nowMs = 1_000L + KeyboardShowPolicy.LATE_FOCUS_ALLOW_MS + 1,
            ),
        )
    }
}
