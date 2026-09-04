package net.matsudamper.browser

import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import org.mozilla.geckoview.GeckoSession

/**
 * 画面遷移やタブ切替直後の autofocus ではソフトキーボードを出さず、
 * ユーザーがコンテンツを操作したあとだけ [GeckoSession.TextInputDelegate.showSoftInput]
 * をシステムへ委譲する。
 */
internal class UserGestureGatedTextInputDelegate(
    private val session: GeckoSession,
    private val policy: KeyboardShowPolicy = KeyboardShowPolicy(),
) : GeckoSession.TextInputDelegate {
    private val defaultDelegate: GeckoSession.TextInputDelegate = session.textInput.delegate
    private var pendingShowSoftInput = false

    init {
        session.textInput.setDelegate(this)
    }

    fun onNavigationStarted() {
        pendingShowSoftInput = false
        policy.onNavigationStarted()
        defaultDelegate.hideSoftInput(session)
    }

    fun onSessionShownWithoutUserGesture() {
        pendingShowSoftInput = false
        policy.onSessionShownWithoutUserGesture()
        defaultDelegate.hideSoftInput(session)
    }

    fun onUserGesture() {
        policy.onUserGesture()
        flushPendingShowSoftInput()
    }

    override fun restartInput(session: GeckoSession, reason: Int) {
        defaultDelegate.restartInput(session, reason)
    }

    override fun showSoftInput(session: GeckoSession) {
        if (policy.shouldShowSoftInput()) {
            pendingShowSoftInput = false
            defaultDelegate.showSoftInput(session)
        } else {
            pendingShowSoftInput = true
        }
    }

    override fun hideSoftInput(session: GeckoSession) {
        pendingShowSoftInput = false
        defaultDelegate.hideSoftInput(session)
    }

    private fun flushPendingShowSoftInput() {
        if (!pendingShowSoftInput) return
        pendingShowSoftInput = false
        defaultDelegate.showSoftInput(session)
    }

    override fun updateSelection(
        session: GeckoSession,
        selStart: Int,
        selEnd: Int,
        compositionStart: Int,
        compositionEnd: Int,
    ) {
        defaultDelegate.updateSelection(
            session,
            selStart,
            selEnd,
            compositionStart,
            compositionEnd,
        )
    }

    override fun updateExtractedText(
        session: GeckoSession,
        request: ExtractedTextRequest,
        text: ExtractedText,
    ) {
        defaultDelegate.updateExtractedText(session, request, text)
    }

    override fun updateCursorAnchorInfo(session: GeckoSession, info: CursorAnchorInfo) {
        defaultDelegate.updateCursorAnchorInfo(session, info)
    }
}
