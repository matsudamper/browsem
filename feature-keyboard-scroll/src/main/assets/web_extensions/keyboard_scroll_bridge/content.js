// キーボード表示で表示領域が縮んだとき、フォーカス中の入力欄を可視範囲へ運ぶ。
// Gecko 側のスクロール判断はレイアウトビューポートの扱いやアニメーションの
// タイミングに左右されるため、ページ側で明示的にスクロールさせて確実にする。
(function () {
  const visualViewport = window.visualViewport;
  if (!visualViewport) return;

  // 表示領域が変わってから安定するまでの待ち時間 (ms)。
  // キーボードのアニメーション中は resize が連続で届く。
  const SETTLE_DELAY = 120;

  const isTopFrame = window === window.top;

  function isTextEntry(element) {
    if (!element) return false;
    if (element.isContentEditable) return true;
    const tagName = element.tagName;
    return tagName === "INPUT" || tagName === "TEXTAREA" || tagName === "SELECT";
  }

  function isOutsideVisibleArea(element) {
    // 子フレームでは自身の可視範囲しか測れず、親フレーム内での位置が分からない。
    // scrollIntoView は親フレームまで伝播するため、判定せずに運ぶ。
    if (!isTopFrame) return true;

    const rect = element.getBoundingClientRect();
    // getBoundingClientRect はレイアウトビューポート基準。
    // 可視範囲は visualViewport のオフセットと高さで表す。
    const visibleTop = visualViewport.offsetTop;
    const visibleBottom = visibleTop + visualViewport.height;
    return rect.top < visibleTop || rect.bottom > visibleBottom;
  }

  function scrollFocusedIntoView() {
    const element = document.activeElement;
    if (!isTextEntry(element)) return;
    if (!isOutsideVisibleArea(element)) return;
    element.scrollIntoView({ block: "center", inline: "nearest" });
  }

  let settleTimer = 0;
  function scheduleScroll() {
    clearTimeout(settleTimer);
    settleTimer = setTimeout(scrollFocusedIntoView, SETTLE_DELAY);
  }

  // focusin では補正しない。ページが focus({ preventScroll: true }) で
  // 画面外の入力欄へフォーカスするだけのケースまで動かしてしまうため。
  // 表示領域が変わったときだけ補正する。
  visualViewport.addEventListener("resize", scheduleScroll);

  // 読み込みが遅いページでは、注入前にタップと IME 表示が終わっていることがある。
  // その場合は以降のイベントが来ないため、注入時にも一度だけ確認する。
  scheduleScroll();
})();
