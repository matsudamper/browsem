// キーボード表示中に、文書の下端へキーボード分の余白を足してスクロール余地を作り、
// フォーカス中の入力欄を可視範囲へ運ぶ。
//
// Gecko は onKeyboardHeight を受け取っても visual viewport を縮めないため、
// 文書末尾の入力欄はスクロール上限に阻まれてキーボードの上まで来られない。
// GeckoView を物理的に縮めると Gecko がポップアップを閉じてしまうので、
// リサイズを伴わないページ側の余白で解決する。
(function () {
  // 表示領域が変わってから安定するまでの待ち時間 (ms)。
  const SETTLE_DELAY = 120;
  // 余白を足す要素に付ける印。ページ側の style を壊さずに戻せるようにする。
  const PADDING_PROPERTY = "--browsem-keyboard-padding";

  // 文字入力を受け付ける input の type。checkbox や button などキーボードを
  // 出さないものを含めると、フォーカスが移っただけでスクロールしてしまう。
  const TEXT_INPUT_TYPES = [
    "text",
    "search",
    "url",
    "tel",
    "email",
    "password",
    "number",
    "date",
    "datetime-local",
    "month",
    "time",
    "week",
  ];

  const isTopFrame = window === window.top;
  let keyboardHeightCss = 0;
  let settleTimer = 0;

  function isTextEntry(element) {
    if (!element) return false;
    if (element.isContentEditable) return true;
    const tagName = element.tagName;
    if (tagName === "TEXTAREA") return true;
    // select はキーボードを出さない。対象に含めると、ドロップダウンを開いた
    // ときの focusin でスクロールしてしまい、開いたばかりの選択肢が閉じる。
    if (tagName !== "INPUT") return false;
    return TEXT_INPUT_TYPES.includes(element.type);
  }

  function isScrollTarget(element) {
    if (isTextEntry(element)) return true;
    if (element == null) return false;
    // 子フレーム内の入力欄は外側から見えない。フレームごと運ぶ。
    if (element.tagName === "IFRAME") return true;
    // closed な shadow root では中の入力欄まで辿れない。カスタム要素
    // (タグ名にハイフンを含む) にフォーカスがあるなら host ごと運ぶ。
    return element.tagName.includes("-");
  }

  /**
   * フォーカスされている要素を返す。open な shadow root は辿る。
   */
  function focusedElement() {
    let element = document.activeElement;
    while (element && element.shadowRoot && element.shadowRoot.activeElement) {
      element = element.shadowRoot.activeElement;
    }
    return element;
  }

  /**
   * キーボード分の余白を文書の下端へ反映する。
   *
   * これによりスクロール上限が伸び、文書末尾の入力欄をキーボードの上まで
   * 運べるようになる。ページのレイアウトは変えない。
   */
  function applyKeyboardPadding() {
    const root = document.documentElement;
    if (!root) return;
    if (keyboardHeightCss <= 0) {
      root.style.removeProperty("padding-bottom");
      root.style.removeProperty(PADDING_PROPERTY);
      return;
    }
    root.style.setProperty(PADDING_PROPERTY, keyboardHeightCss + "px");
    root.style.setProperty("padding-bottom", "var(" + PADDING_PROPERTY + ")");
  }

  function visibleBottom() {
    // visual viewport は縮まないため、キーボード上端は自分で計算する。
    return document.documentElement.clientHeight - keyboardHeightCss;
  }

  function scrollFocusedIntoView() {
    // URL バーを開くとページ側のフォーカスは外れるが activeElement は残る。
    // URL 編集で背後のページが勝手に動かないよう、フォーカスを持っているときだけ補正する。
    if (!document.hasFocus()) return;
    if (keyboardHeightCss <= 0) return;

    const element = focusedElement();
    if (!isScrollTarget(element)) return;

    // 子フレームでは親フレーム内での位置が分からない。
    // scrollIntoView は親フレームまで伝播するため、判定せずに運ぶ。
    if (!isTopFrame) {
      element.scrollIntoView({ block: "nearest", inline: "nearest" });
      return;
    }

    const rect = element.getBoundingClientRect();
    if (rect.bottom <= visibleBottom() && rect.top >= 0) return;
    // 可視範囲へ入る最小限だけ動かす。大きく動かすとポップアップが閉じやすい。
    window.scrollBy(0, rect.bottom - visibleBottom());
  }

  function scheduleScroll() {
    clearTimeout(settleTimer);
    settleTimer = setTimeout(scrollFocusedIntoView, SETTLE_DELAY);
  }

  function onKeyboardHeightChanged(heightPx) {
    const next = heightPx / (window.devicePixelRatio || 1);
    if (next === keyboardHeightCss) return;
    keyboardHeightCss = next;
    applyKeyboardPadding();
    scheduleScroll();
  }

  const port = browser.runtime.connectNative("keyboardScrollBridge");
  port.onMessage.addListener(function (message) {
    if (!message || typeof message.keyboardHeightPx !== "number") return;
    onKeyboardHeightChanged(message.keyboardHeightPx);
  });

  // キーボードが出たままフォーカスだけが移る場合 (IME の「次へ」など) も補正する。
  document.addEventListener(
    "focusin",
    function () {
      if (keyboardHeightCss <= 0) return;
      scheduleScroll();
    },
    true,
  );
})();
