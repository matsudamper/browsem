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

  // ページが指定していた padding-bottom。復元するために覚えておく。
  let savedPaddingBottom = null;
  let savedPaddingPriority = "";

  /**
   * キーボード分の余白を文書の下端へ反映する。
   *
   * これによりスクロール上限が伸び、文書末尾の入力欄をキーボードの上まで
   * 運べるようになる。ページのレイアウトは変えない。
   * ページ自身のインライン指定は保存して復元する。
   */
  function applyKeyboardPadding() {
    const root = document.documentElement;
    if (!root) return;

    if (keyboardHeightCss <= 0) {
      if (savedPaddingBottom === null) return;
      if (savedPaddingBottom === "") {
        root.style.removeProperty("padding-bottom");
      } else {
        root.style.setProperty("padding-bottom", savedPaddingBottom, savedPaddingPriority);
      }
      root.style.removeProperty(PADDING_PROPERTY);
      savedPaddingBottom = null;
      savedPaddingPriority = "";
      return;
    }

    if (savedPaddingBottom === null) {
      savedPaddingBottom = root.style.getPropertyValue("padding-bottom");
      savedPaddingPriority = root.style.getPropertyPriority("padding-bottom");
    }
    // 元の指定に足す。置き換えるとページのレイアウトが変わる。
    const base = savedPaddingBottom === "" ? "0px" : savedPaddingBottom;
    root.style.setProperty(PADDING_PROPERTY, keyboardHeightCss + "px");
    root.style.setProperty(
      "padding-bottom",
      "calc(" + base + " + var(" + PADDING_PROPERTY + "))",
      savedPaddingPriority,
    );
  }

  /**
   * キーボード上端の位置を、getBoundingClientRect と同じ座標系で返す。
   *
   * visual viewport はキーボードでは縮まないため自分で差し引く。
   * ピンチズーム中は倍率で割って visual viewport 側の単位へ直す。
   */
  function visibleBottom() {
    const viewport = window.visualViewport;
    if (!viewport) return document.documentElement.clientHeight - keyboardHeightCss;
    const scale = viewport.scale || 1;
    return viewport.offsetTop + viewport.height - keyboardHeightCss / scale;
  }

  function visibleTop() {
    const viewport = window.visualViewport;
    return viewport ? viewport.offsetTop : 0;
  }

  /**
   * スクロールの基準にする矩形を返す。
   *
   * 可視範囲より背の高い contenteditable では、要素の下端を揃えると
   * キャレットが画面外へ出る。キャレットが取れる場合はそちらを使う。
   */
  function targetRect(element) {
    const rect = element.getBoundingClientRect();
    if (rect.height <= visibleBottom() - visibleTop()) return rect;
    if (!element.isContentEditable) return rect;
    const selection = window.getSelection();
    if (!selection || selection.rangeCount === 0) return rect;
    const caret = selection.getRangeAt(0).getBoundingClientRect();
    if (caret.height === 0 && caret.top === 0) return rect;
    return caret;
  }

  function scrollFocusedIntoView() {
    // URL バーを開くとページ側のフォーカスは外れるが activeElement は残る。
    // URL 編集で背後のページが勝手に動かないよう、フォーカスを持っているときだけ補正する。
    if (!document.hasFocus()) return;
    if (keyboardHeightCss <= 0) return;

    const element = focusedElement();
    if (!isScrollTarget(element)) return;

    // 入れ子のスクロールコンテナや固定パネルは window.scrollBy では動かせない。
    // まず scrollIntoView でスクロール祖先を動かす。可視範囲へ入る最小限に留める。
    element.scrollIntoView({ block: "nearest", inline: "nearest" });

    // 子フレームでは親フレーム内での位置が分からない。scrollIntoView が
    // 親フレームまで伝播するので、残差の補正はトップフレームだけで行う。
    if (!isTopFrame) return;

    // 残差はキーボード境界まで詰める。html/body が overflow: hidden の
    // 固定モーダル内などでは window.scrollBy が効かないため、
    // スクロールできる祖先を優先して動かす。
    const rect = targetRect(element);
    if (rect.bottom <= visibleBottom() && rect.top >= visibleTop()) return;
    scrollByFromNearestScrollable(element, rect.bottom - visibleBottom());
  }

  /**
   * 縦方向にスクロールできる直近の祖先を返す。無ければ null。
   */
  function scrollableAncestor(element) {
    let node = element.parentElement;
    while (node) {
      const overflowY = window.getComputedStyle(node).overflowY;
      const scrollable = overflowY === "auto" || overflowY === "scroll";
      if (scrollable && node.scrollHeight > node.clientHeight) return node;
      node = node.parentElement;
    }
    return null;
  }

  /**
   * 残差分だけスクロールする。祖先が動かせるならそちらを優先する。
   */
  function scrollByFromNearestScrollable(element, delta) {
    if (delta === 0) return;
    const ancestor = scrollableAncestor(element);
    if (ancestor == null) {
      window.scrollBy(0, delta);
      return;
    }
    const before = ancestor.scrollTop;
    ancestor.scrollTop = before + delta;
    const moved = ancestor.scrollTop - before;
    // 祖先が上限に達していたら残りをページ側で詰める。
    if (moved !== delta) window.scrollBy(0, delta - moved);
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
