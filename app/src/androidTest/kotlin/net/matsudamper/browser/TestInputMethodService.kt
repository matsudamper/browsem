package net.matsudamper.browser

import android.inputmethodservice.InputMethodService
import android.util.TypedValue
import android.view.View

/**
 * テストで IME insets を発生させるための、固定高さのダミーキーボード。
 *
 * GMD が使う aosp-atd イメージは LatinIME を含まないためソフトキーボードが存在せず、
 * キーボード表示を前提としたテストが成立しない。実キーボードの代わりにこのサービスを
 * 有効化して、決まった高さの IME insets を発生させる。
 */
class TestInputMethodService : InputMethodService() {
    private val keyboardHeightPx: Int
        get() = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            KEYBOARD_HEIGHT_DP.toFloat(),
            resources.displayMetrics,
        ).toInt()

    override fun onCreateInputView(): View {
        return View(this).apply {
            minimumHeight = keyboardHeightPx
            setBackgroundColor(BACKGROUND_COLOR)
        }
    }

    /** 横向きでも全画面 IME にせず、insets として観測できるようにする */
    override fun onEvaluateFullscreenMode(): Boolean = false

    /**
     * アプリへ通知する IME の高さを固定する。
     *
     * 入力ビューの測定に任せると IME ウィンドウが画面いっぱいに広がり、
     * insets が画面高さ相当になってしまうため、ここで直接指定する。
     */
    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        val decorHeight = window?.window?.decorView?.height ?: return
        val top = (decorHeight - keyboardHeightPx).coerceAtLeast(0)
        outInsets.contentTopInsets = top
        outInsets.visibleTopInsets = top
    }

    companion object {
        /** 実キーボードに近い高さ。画面下部の入力欄を確実に覆う */
        const val KEYBOARD_HEIGHT_DP = 300

        private const val BACKGROUND_COLOR = 0xFF303030.toInt()
    }
}
