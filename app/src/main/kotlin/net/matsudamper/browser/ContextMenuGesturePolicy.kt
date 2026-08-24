package net.matsudamper.browser

/**
 * 指を離してから onContextMenu が届くまでに許容する時間 (ms)。
 * 通常の長押しではメニューは指を置いたまま通知されるため、指を離した後に大きく遅れて
 * 届いたものは「滞留していたタッチイベントが遅延処理された結果」とみなす。
 */
internal const val CONTEXT_MENU_TOUCH_UP_GRACE_MS = 300L

/**
 * 指を置いてから onContextMenu が届くまでに最低限必要な時間 (ms)。
 * Gecko の長押し判定は ui.click_hold_context_menus.delay (既定 500ms) 経過後のため、
 * 指を置いた直後に届いたものは前のジェスチャーの滞留分とみなす。
 * 判定遅延を考慮して既定値より短めの余裕を持たせる。
 */
internal const val CONTEXT_MENU_MIN_TOUCH_DURATION_MS = 200L

/**
 * コンテキストメニュー (長押しメニュー) を表示してよいジェスチャーかを判定する。
 *
 * ページの JS がメインスレッドを占有している間、Gecko はタッチイベントをコンテンツ側の
 * 応答待ちでキューに溜める。解放後にまとめて処理されると、実際にはスクロールしていた
 * 操作でも「同じ位置を長押しした」と判定され、指を離した後に onContextMenu が届くことがある。
 * そのため、移動を伴ったジェスチャーや、指を離してから時間が経ったものは表示しない。
 *
 * 指を置いたままの場合も、滞留していた前のジェスチャー分が次のタッチ中に届くことがあるため、
 * 長押しが成立し得るだけの時間そのタッチが続いているかを併せて確認する。
 */
internal fun shouldShowContextMenuForGesture(
    hasTouchGestureRecord: Boolean,
    isTouchGestureActive: Boolean,
    gestureMoved: Boolean,
    elapsedSinceGestureStartMs: Long,
    elapsedSinceGestureEndMs: Long,
): Boolean {
    // タッチ以外 (キーボード等) が発生源の場合は抑制しない
    if (!hasTouchGestureRecord) return true
    // スクロール・ピンチ等、移動を伴うジェスチャーは長押しではない
    if (gestureMoved) return false
    // 指を置いたまま: そのタッチで長押しが成立し得る時間が経っている場合のみ表示する
    if (isTouchGestureActive) return elapsedSinceGestureStartMs >= CONTEXT_MENU_MIN_TOUCH_DURATION_MS
    return elapsedSinceGestureEndMs <= CONTEXT_MENU_TOUCH_UP_GRACE_MS
}
