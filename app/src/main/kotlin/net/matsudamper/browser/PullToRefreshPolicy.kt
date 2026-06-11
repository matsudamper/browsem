package net.matsudamper.browser

import org.mozilla.geckoview.PanZoomController

/**
 * ACTION_DOWN 時の [PanZoomController.InputResultDetail] から
 * PullToRefresh を発動させてよいタッチかを判定する。
 *
 * Fenix (android-components) の InputResultDetail.canOverscrollTop() と同等のロジック。
 * https://github.com/mozilla-firefox/firefox/blob/main/mobile/android/android-components/components/concept/engine/src/main/java/mozilla/components/concept/engine/InputResultDetail.kt
 *
 * - コンテンツ (JS) がタッチを消費する場合 (preventDefault / touch-action) は発動させない。
 *   X の画像ビューアー等、JS 管理ズーム中のパン操作がこれに該当する。
 * - 上方向にまだスクロールできる場合は発動させない。scrollableDirections は
 *   visual viewport 基準のため、ピンチズーム中で上端に達していない場合も TOP フラグが立つ。
 * - タッチ対象のスクロールフレームが縦方向にオーバースクロールできない場合は発動させない。
 *   ライトボックス表示中の overflow: hidden な body や overscroll-behavior: none が該当する
 *   (https://bugzilla.mozilla.org/show_bug.cgi?id=1902313 で Chrome 互換にされた挙動)。
 *   短い（スクロール不能な）ページは UNHANDLED + 縦オーバースクロール可で返るため発動できる。
 */
internal fun canTriggerPullToRefresh(
    handledResult: Int,
    scrollableDirections: Int,
    overscrollDirections: Int,
): Boolean {
    // HANDLED_CONTENT はコンテンツがタッチを消費。IGNORED は「ブラウザは何もすべきでない」
    val isEligibleResult = handledResult == PanZoomController.INPUT_RESULT_HANDLED ||
        handledResult == PanZoomController.INPUT_RESULT_UNHANDLED
    if (!isEligibleResult) return false
    if (scrollableDirections and PanZoomController.SCROLLABLE_FLAG_TOP != 0) return false
    if (overscrollDirections and PanZoomController.OVERSCROLL_FLAG_VERTICAL == 0) return false
    return true
}
