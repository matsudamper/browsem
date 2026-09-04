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

  /**
   * フォーカスされている要素を返す。
   *
   * Web Components では外側の activeElement が shadow host になるため、
   * open な shadow root を辿って実際の入力欄まで降りる。closed な場合は
   * host までしか辿れないが、host を運べば入力欄も可視範囲に入る。
   * 画面下部の iframe 内に入力欄がある場合も、外側からは iframe 要素しか
   * 見えないため同じ扱いにする。
   */
  function focusedElement() {
    let element = document.activeElement;
    while (element && element.shadowRoot && element.shadowRoot.activeElement) {
      element = element.shadowRoot.activeElement;
    }
    return element;
  }

  function isScrollTarget(element) {
    if (isTextEntry(element)) return true;
    // 子フレーム内の入力欄は外側から見えない。フレームごと運ぶ。
    return element != null && element.tagName === "IFRAME";
  }

  /**
   * スクロールの基準にする矩形を返す。
   *
   * 可視範囲より背の高い textarea や contenteditable では、要素全体を
   * 基準にするとキャレットがキーボードの下に残る。選択範囲が取れる場合は
   * そちらを優先する。
   */
  function targetRect(element) {
    const elementRect = element.getBoundingClientRect();
    if (elementRect.height <= visualViewport.height) return elementRect;

    const selection = window.getSelection();
    if (!selection || selection.rangeCount === 0) return elementRect;
    const selectionRect = selection.getRangeAt(0).getBoundingClientRect();
    // 折りたたまれた選択では幅も高さも 0 になることがある。
    if (selectionRect.height === 0 && selectionRect.top === 0) return elementRect;
    return selectionRect;
  }

  function scrollFocusedIntoView() {
    const element = focusedElement();
    if (!isScrollTarget(element)) return;

    // 子フレームでは自身の可視範囲しか測れず、親フレーム内での位置が分からない。
    // scrollIntoView は親フレームまで伝播するため、判定せずに運ぶ。
    if (isTopFrame) {
      const rect = targetRect(element);
      const visibleTop = visualViewport.offsetTop;
      const visibleBottom = visibleTop + visualViewport.height;
      if (rect.top >= visibleTop && rect.bottom <= visibleBottom) return;
    }

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
})();
