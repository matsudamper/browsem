// キーボード表示で表示領域が縮んだとき、フォーカス中の入力欄を可視範囲へ運ぶ。
// Gecko 側のスクロール判断はレイアウトビューポートの扱いやアニメーションの
// タイミングに左右されるため、ページ側で明示的にスクロールさせて確実にする。
(function () {
  if (window !== window.top) return;
  const visualViewport = window.visualViewport;
  if (!visualViewport) return;

  // 表示領域が変わってから安定するまでの待ち時間 (ms)。
  // キーボードのアニメーション中は resize が連続で届く。
  const SETTLE_DELAY = 120;

  function isTextEntry(element) {
    if (!element) return false;
    if (element.isContentEditable) return true;
    const tagName = element.tagName;
    return tagName === "INPUT" || tagName === "TEXTAREA" || tagName === "SELECT";
  }

  function scrollFocusedIntoView() {
    const element = document.activeElement;
    if (!isTextEntry(element)) return;

    const rect = element.getBoundingClientRect();
    // getBoundingClientRect はレイアウトビューポート基準。
    // 可視範囲は visualViewport のオフセットと高さで表す。
    const visibleTop = visualViewport.offsetTop;
    const visibleBottom = visibleTop + visualViewport.height;
    if (rect.top >= visibleTop && rect.bottom <= visibleBottom) return;

    element.scrollIntoView({ block: "center", inline: "nearest" });
  }

  let settleTimer = 0;
  function scheduleScroll() {
    clearTimeout(settleTimer);
    settleTimer = setTimeout(scrollFocusedIntoView, SETTLE_DELAY);
  }

  visualViewport.addEventListener("resize", scheduleScroll);
  document.addEventListener("focusin", scheduleScroll, true);
})();
