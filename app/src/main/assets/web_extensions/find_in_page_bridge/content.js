// ページ内正規表現検索を実装するコンテンツスクリプト
(function () {
  // トップフレームのみで実行
  if (window !== window.top) return;

  const HIGHLIGHT_CLASS = '__find_in_page_highlight';
  const CURRENT_CLASS = '__find_in_page_current';
  const HIGHLIGHT_STYLE = 'background: #ffff00; color: #000000; border-radius: 2px; padding: 0;';
  const CURRENT_STYLE = 'background: #ff8c00; color: #000000; border-radius: 2px; padding: 0;';

  let currentIndex = -1;
  let highlights = [];

  // 既存のハイライトを全て除去してDOMを元に戻す
  function clearHighlights() {
    document.querySelectorAll('.' + HIGHLIGHT_CLASS).forEach(function (el) {
      const parent = el.parentNode;
      if (!parent) return;
      parent.replaceChild(document.createTextNode(el.textContent || ''), el);
      parent.normalize();
    });
    highlights = [];
    currentIndex = -1;
  }

  // 特殊文字をエスケープしてリテラル文字列を正規表現に変換する
  function escapeRegex(str) {
    return str.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  }

  // 指定インデックスのハイライトを「現在位置」としてスタイルを更新しスクロールする
  function focusMatch(index) {
    if (highlights.length === 0) return;
    highlights.forEach(function (el, i) {
      el.className = HIGHLIGHT_CLASS;
      el.setAttribute('style', HIGHLIGHT_STYLE);
    });
    const current = highlights[index];
    if (!current) return;
    current.className = HIGHLIGHT_CLASS + ' ' + CURRENT_CLASS;
    current.setAttribute('style', CURRENT_STYLE);
    current.scrollIntoView({ block: 'center', behavior: 'smooth' });
  }

  // DOM を走査してマッチ箇所に <mark> 要素を挿入する。
  // ハイライト挿入後に DOM が変化するため逆順に処理する。
  function applyHighlights(regex) {
    const walker = document.createTreeWalker(
      document.body,
      NodeFilter.SHOW_TEXT,
      {
        acceptNode: function (node) {
          const tag = node.parentElement && node.parentElement.tagName
            ? node.parentElement.tagName.toLowerCase()
            : '';
          // スクリプト・スタイル・非表示要素はスキップ
          if (['script', 'style', 'noscript', 'iframe', 'textarea'].indexOf(tag) !== -1) {
            return NodeFilter.FILTER_REJECT;
          }
          return NodeFilter.FILTER_ACCEPT;
        }
      }
    );

    // 先に全マッチ範囲を収集する（DOM 変更前）
    const ranges = [];
    let node;
    while ((node = walker.nextNode())) {
      const text = node.nodeValue || '';
      let match;
      // lastIndex をリセットしてから使用
      regex.lastIndex = 0;
      while ((match = regex.exec(text)) !== null) {
        if (match[0].length === 0) {
          // 空マッチで無限ループを防止
          regex.lastIndex++;
          continue;
        }
        const range = document.createRange();
        range.setStart(node, match.index);
        range.setEnd(node, match.index + match[0].length);
        ranges.push(range);
      }
    }

    // 逆順に <mark> 要素を挿入することで前方の範囲のオフセットがズレないようにする
    for (let i = ranges.length - 1; i >= 0; i--) {
      try {
        const mark = document.createElement('mark');
        mark.className = HIGHLIGHT_CLASS;
        mark.setAttribute('style', HIGHLIGHT_STYLE);
        ranges[i].surroundContents(mark);
      } catch (e) {
        // 要素をまたぐ範囲など surroundContents が失敗するケースは無視
      }
    }

    // 文書順に並んでいるので querySelectorAll で取得
    highlights = Array.from(document.querySelectorAll('.' + HIGHLIGHT_CLASS));
    return highlights.length;
  }

  // ネイティブアプリとの双方向ポートを確立
  const port = browser.runtime.connectNative('findInPageBridge');

  port.onMessage.addListener(function (msg) {
    const action = msg.action;

    if (action === 'search') {
      clearHighlights();
      const query = msg.query || '';
      if (!query) {
        port.postMessage({ current: 0, total: 0 });
        return;
      }

      let regex;
      try {
        // isRegex=true のときはユーザー入力をそのまま正規表現として扱う
        // 平文検索は大文字小文字を区別しない（Firefox デフォルトに合わせる）
        const flags = msg.isRegex ? 'g' : 'gi';
        const pattern = msg.isRegex ? query : escapeRegex(query);
        regex = new RegExp(pattern, flags);
      } catch (e) {
        // 無効な正規表現
        port.postMessage({ current: 0, total: 0, error: 'invalid_regex' });
        return;
      }

      const total = applyHighlights(regex);
      if (total > 0) {
        currentIndex = 0;
        focusMatch(0);
      }
      port.postMessage({ current: total > 0 ? 1 : 0, total: total });

    } else if (action === 'next') {
      if (highlights.length === 0) return;
      currentIndex = (currentIndex + 1) % highlights.length;
      focusMatch(currentIndex);
      port.postMessage({ current: currentIndex + 1, total: highlights.length });

    } else if (action === 'previous') {
      if (highlights.length === 0) return;
      currentIndex = (currentIndex - 1 + highlights.length) % highlights.length;
      focusMatch(currentIndex);
      port.postMessage({ current: currentIndex + 1, total: highlights.length });

    } else if (action === 'clear') {
      clearHighlights();
    }
  });

  port.onDisconnect.addListener(function () {
    // ページ遷移時にポートが切断された場合、ハイライトをクリアする
    clearHighlights();
  });
})();
