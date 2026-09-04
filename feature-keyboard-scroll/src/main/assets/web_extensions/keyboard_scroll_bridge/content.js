// キーボード表示で visual viewport が縮んだとき、フォーカス中の入力欄を可視範囲へ運ぶ。
// Gecko 側のスクロール判断はレイアウトビューポートの扱いやアニメーションの
// タイミングに左右されるため、ページ側で明示的にスクロールさせて確実にする。
(function () {
  const visualViewport = window.visualViewport;
  if (!visualViewport) return;

  // 表示領域が変わってから安定するまでの待ち時間 (ms)。
  // キーボードのアニメーション中は resize が連続で届く。
  const SETTLE_DELAY = 120;
  // キーボードとみなす最小の縮み量 (CSS px)。
  const KEYBOARD_MIN_HEIGHT = 100;

  const isTopFrame = window === window.top;

  /**
   * キーボードで visual viewport が縮んでいるかを返す。
   *
   * レイアウトビューポートは interactive-widget の既定 (resizes-visual) では
   * 縮まないため、その差でキーボードの有無を判定できる。
   */
  function isKeyboardVisible() {
    // ピンチズーム中も visual viewport は縮む。倍率が等倍のときだけ見る。
    if (visualViewport.scale > 1) return false;
    const layoutHeight = document.documentElement.clientHeight;
    return layoutHeight - visualViewport.height > KEYBOARD_MIN_HEIGHT;
  }

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
   * フォーカスされている要素を返す。
   *
   * Web Components では外側の activeElement が shadow host になるため、
   * open な shadow root を辿って実際の入力欄まで降りる。
   */
  function focusedElement() {
    let element = document.activeElement;
    while (element && element.shadowRoot && element.shadowRoot.activeElement) {
      element = element.shadowRoot.activeElement;
    }
    return element;
  }

  /**
   * contenteditable のキャレット矩形を返す。取れなければ null。
   *
   * textarea や input の選択位置は window.getSelection() に現れないため、
   * フォームコントロールでは使えない。
   */
  function caretRect() {
    const selection = window.getSelection();
    if (!selection || selection.rangeCount === 0) return null;
    const rect = selection.getRangeAt(0).getBoundingClientRect();
    // 選択が無い要素では幅も高さも 0 の原点矩形になることがある。
    if (rect.height === 0 && rect.top === 0) return null;
    return rect;
  }

  function visibleTop() {
    return visualViewport.offsetTop;
  }

  function visibleBottom() {
    return visualViewport.offsetTop + visualViewport.height;
  }

  function isOutside(rect) {
    return rect.top < visibleTop() || rect.bottom > visibleBottom();
  }

  function scrollFocusedIntoView() {
    // URL バーを開くとページ側のフォーカスは外れるが activeElement は残る。
    // URL 編集で背後のページが勝手に動かないよう、フォーカスを持っているときだけ補正する。
    if (!document.hasFocus()) return;

    const element = focusedElement();
    if (!isScrollTarget(element)) return;

    // 子フレームでは自身の可視範囲しか測れず、親フレーム内での位置が分からない。
    // scrollIntoView は親フレームまで伝播するため、判定せずに運ぶ。
    if (!isTopFrame) {
      element.scrollIntoView({ block: "nearest", inline: "nearest" });
      return;
    }

    const elementRect = element.getBoundingClientRect();
    if (elementRect.height <= visualViewport.height) {
      if (!isOutside(elementRect)) return;
      element.scrollIntoView({ block: "nearest", inline: "nearest" });
      return;
    }

    // 可視範囲より背の高い要素は中央揃えしてもキャレットがキーボードの下に残る。
    const caret = element.isContentEditable ? caretRect() : null;
    if (caret == null) {
      // textarea や input のキャレット位置は取得できない。要素内のスクロールは
      // Gecko がキャレットへ追従させるため、下端を可視範囲に入れて任せる。
      if (elementRect.bottom <= visibleBottom()) return;
      element.scrollIntoView({ block: "end", inline: "nearest" });
      return;
    }

    if (!isOutside(caret)) return;
    // 入れ子のスクロールコンテナごと動かしてから、キャレットの残差を詰める。
    element.scrollIntoView({ block: "nearest", inline: "nearest" });
    const movedCaret = caretRect();
    if (movedCaret == null || !isOutside(movedCaret)) return;
    // 可視範囲へ入る最小限だけ動かす。大きく動かすとポップアップが閉じる。
    if (movedCaret.bottom > visibleBottom()) {
      window.scrollBy(0, movedCaret.bottom - visibleBottom());
    } else {
      window.scrollBy(0, movedCaret.top - visibleTop());
    }
  }

  /**
   * visual viewport の実測値をネイティブへ送る。
   *
   * キーボード高さが Gecko に届いているか (visual viewport が縮んでいるか) を
   * CI のログから確認するための診断。
   */
  function reportViewport(reason) {
    if (!isTopFrame) return;
    browser.runtime
      .sendNativeMessage("keyboardScrollBridge", {
        reason: reason,
        layoutHeight: document.documentElement.clientHeight,
        visualHeight: visualViewport.height,
        visualOffsetTop: visualViewport.offsetTop,
        scrollY: window.scrollY,
        scrollMaxY: document.documentElement.scrollHeight - document.documentElement.clientHeight,
      })
      .catch(function () {});
  }

  let settleTimer = 0;
  function scheduleScroll() {
    clearTimeout(settleTimer);
    settleTimer = setTimeout(scrollFocusedIntoView, SETTLE_DELAY);
  }

  // ピンチズームでも resize は届く。倍率が変わったときはユーザー操作なので触らない。
  let lastScale = visualViewport.scale;
  visualViewport.addEventListener("resize", function () {
    const scale = visualViewport.scale;
    const scaleChanged = scale !== lastScale;
    lastScale = scale;
    reportViewport("resize");
    // 倍率が変わったときも、ズームしたままの状態で寸法だけ変わったときも触らない。
    // どちらもユーザーが決めた表示位置を上書きすることになる。
    if (scaleChanged || scale > 1) return;
    scheduleScroll();
  });

  // IME の「次へ」でフォーカスだけが移る場合は resize が来ない。
  // キーボードが出ていると確認できるときだけ補正する。ページが
  // focus({ preventScroll: true }) で画面外へフォーカスするだけのケースは対象外。
  document.addEventListener(
    "focusin",
    function () {
      reportViewport("focusin");
      if (!isKeyboardVisible()) return;
      scheduleScroll();
    },
    true,
  );
})();
