package net.matsudamper.browser

import android.content.Context
import android.view.MotionEvent
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

// SwipeRefreshLayout は ACTION_DOWN 時点で canChildScrollUp() が true だと
// onInterceptTouchEvent が fail-fast し mInitialDownY を更新しない仕様。
// その状態で非同期に判定が反転すると、直後の ACTION_MOVE で古い
// mInitialDownY を基準に差分計算が走り、ロード丸が一気に下へワープして
// しまう。これを防ぐため ACTION_DOWN dispatch 中だけ canChildScrollUp()
// を強制的に false にして DOWN を必ず親に拾わせ、最新の Y を記録させる。
internal class GeckoSwipeRefreshLayout(context: Context) : SwipeRefreshLayout(context) {

    private var currentDispatchAction: Int = MotionEvent.ACTION_CANCEL

    /**
     * キーボードのために下端から差し引く高さ。
     *
     * SwipeRefreshLayout は子を padding 分だけ縮めて measure/layout するため、
     * この値を下 padding として使う。適用は [onMeasure] で行い、measure される
     * 高さを超えないように抑える。横向きの全画面 IME やマルチウィンドウでは
     * ウィンドウ基準のキーボード高がコンテナの高さを超えることがあり、
     * そのまま padding にすると子の高さが負になる。
     */
    var keyboardHeight: Int = 0
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val paddingBottom = keyboardHeight.coerceIn(0, MeasureSpec.getSize(heightMeasureSpec))
        if (getPaddingBottom() != paddingBottom) {
            setPadding(0, 0, 0, paddingBottom)
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        currentDispatchAction = ev.actionMasked
        return try {
            super.dispatchTouchEvent(ev)
        } finally {
            currentDispatchAction = MotionEvent.ACTION_CANCEL
        }
    }

    override fun canChildScrollUp(): Boolean {
        // ACTION_DOWN 中は fail-fast を回避して mInitialDownY を必ず最新値で
        // 記録させる。MOVE 以降は setOnChildScrollUpCallback の判定に委ねる。
        if (currentDispatchAction == MotionEvent.ACTION_DOWN) return false
        return super.canChildScrollUp()
    }
}
