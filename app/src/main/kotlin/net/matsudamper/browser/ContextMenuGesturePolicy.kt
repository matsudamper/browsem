package net.matsudamper.browser

/**
 * 指を離してから onContextMenu が届くまでに許容する時間 (ms)。
 * 通常の長押しではメニューは指を置いたまま通知されるため、指を離した後に大きく遅れて
 * 届いたものは「滞留していたタッチイベントが遅延処理された結果」とみなす。
 */
internal const val CONTEXT_MENU_TOUCH_UP_GRACE_MS = 300L

/**
 * コンテキストメニュー (長押しメニュー) を表示してよいジェスチャーかを判定する。
 *
 * ページの JS がメインスレッドを占有している間、Gecko はタッチイベントをコンテンツ側の
 * 応答待ちでキューに溜める。解放後にまとめて処理されると、実際にはスクロールしていた
 * 操作でも「同じ位置を長押しした」と判定され、指を離した後に onContextMenu が届くことがある。
 * そのため、移動を伴ったジェスチャーや、指を離してから時間が経ったものは表示しない。
 */
internal fun shouldShowContextMenuForGesture(
    hasTouchGestureRecord: Boolean,
    isTouchGestureActive: Boolean,
    gestureMoved: Boolean,
    elapsedSinceGestureEndMs: Long,
): Boolean {
    // タッチ以外 (キーボード等) が発生源の場合は抑制しない
    if (!hasTouchGestureRecord) return true
    // スクロール・ピンチ等、移動を伴うジェスチャーは長押しではない
    if (gestureMoved) return false
    if (isTouchGestureActive) return true
    return elapsedSinceGestureEndMs <= CONTEXT_MENU_TOUCH_UP_GRACE_MS
}
