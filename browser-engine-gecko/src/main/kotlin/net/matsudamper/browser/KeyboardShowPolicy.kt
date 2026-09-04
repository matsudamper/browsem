package net.matsudamper.browser

import android.os.SystemClock

/**
 * GeckoView の [org.mozilla.geckoview.GeckoSession.TextInputDelegate.showSoftInput]
 * をユーザー操作由来のときだけ許可するための判定ロジック。
 *
 * 画面遷移直後の autofocus ではキーボードを出さず、インプットへの明示的な操作後だけ
 * 表示する。アクセシビリティ操作はタッチリスナーを通らないため、抑制開始から一定時間
 * 経過したフォーカス移動はユーザー操作とみなす。
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
        return nowMs - suppressStartedAtMs > LATE_FOCUS_ALLOW_MS
    }

    companion object {
        const val LATE_FOCUS_ALLOW_MS = 3_000L
    }
}
