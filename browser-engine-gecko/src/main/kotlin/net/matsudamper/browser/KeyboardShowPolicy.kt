package net.matsudamper.browser

import android.os.SystemClock

/**
 * GeckoView の [org.mozilla.geckoview.GeckoSession.TextInputDelegate.showSoftInput]
 * をユーザー操作由来のときだけ許可するための判定ロジック。
 *
 * 画面遷移直後の autofocus ではキーボードを出さず、インプットへの明示的な操作後だけ
 * 表示する。autofocus はナビゲーション直後の短い時間帯に集中するため、その間だけ
 * 抑制し、それ以降のフォーカス移動（アクセシビリティ操作を含む）は許可する。
 */
internal class KeyboardShowPolicy {
    private var suppressUntilUserGesture = false
    private var userTouchedSinceSuppress = false
    private var navigationStartedAtMs = 0L
    private var sessionShownAtMs = 0L

    fun onNavigationStarted(nowMs: Long = SystemClock.elapsedRealtime()) {
        suppressUntilUserGesture = true
        userTouchedSinceSuppress = false
        navigationStartedAtMs = nowMs
    }

    fun onSessionShownWithoutUserGesture(nowMs: Long = SystemClock.elapsedRealtime()) {
        suppressUntilUserGesture = true
        userTouchedSinceSuppress = false
        sessionShownAtMs = nowMs
    }

    fun onUserGesture() {
        userTouchedSinceSuppress = true
        suppressUntilUserGesture = false
    }

    fun shouldShowSoftInput(nowMs: Long = SystemClock.elapsedRealtime()): Boolean {
        if (!suppressUntilUserGesture) return true
        if (userTouchedSinceSuppress) return true
        val suppressStartedAtMs = maxOf(navigationStartedAtMs, sessionShownAtMs)
        return nowMs - suppressStartedAtMs > AUTOFOCUS_SUPPRESS_MS
    }

    companion object {
        /** autofocus が集中するナビゲーション直後の抑制時間 */
        const val AUTOFOCUS_SUPPRESS_MS = 500L
    }
}
