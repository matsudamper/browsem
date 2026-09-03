package net.matsudamper.browser

import android.content.Context
import android.inputmethodservice.InputMethodService
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup

/**
 * テストで IME insets を発生させるための、固定高さのダミーキーボード。
 *
 * GMD が使う aosp-atd イメージは LatinIME を含まないためソフトキーボードが存在せず、
 * キーボード表示を前提としたテストが成立しない。実キーボードの代わりにこのサービスを
 * 有効化して、決まった高さの IME insets を発生させる。
 */
class TestInputMethodService : InputMethodService() {
    override fun onCreateInputView(): View {
        return FixedHeightView(this)
    }

    /** 横向きでも全画面 IME にせず、insets として観測できるようにする */
    override fun onEvaluateFullscreenMode(): Boolean = false

    /**
     * 親の measureSpec に関わらず固定高さで測定されるビュー。
     *
     * minimumHeight だけでは親から与えられた高さいっぱいに広がり、
     * IME が画面全体を覆ってしまうため、onMeasure で高さを固定する。
     */
    private class FixedHeightView(context: Context) : View(context) {
        private val fixedHeightPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            KEYBOARD_HEIGHT_DP.toFloat(),
            context.resources.displayMetrics,
        ).toInt()

        init {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                fixedHeightPx,
            )
            setBackgroundColor(BACKGROUND_COLOR)
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, widthMeasureSpec), fixedHeightPx)
        }
    }

    companion object {
        /** 実キーボードに近い高さ。画面下部の入力欄を確実に覆う */
        const val KEYBOARD_HEIGHT_DP = 300

        private const val BACKGROUND_COLOR = 0xFF303030.toInt()
    }
}
