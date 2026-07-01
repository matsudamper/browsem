// 開発者ツール用のコンテンツスクリプト。
// フォーカスが当たっている要素（input 等）の情報をネイティブ側へ通知する。
(function () {
  // トップフレームのみで実行する
  if (window !== window.top) return;

  // ネイティブアプリとの双方向ポートを確立する
  const port = browser.runtime.connectNative('devToolsBridge');

  // ポート切断後に postMessage を呼ぶと例外になるため、切断状態を追跡する
  let disconnected = false;
  port.onDisconnect.addListener(function () {
    disconnected = true;
  });

  // input / textarea / contenteditable など、テキスト入力に類する要素のみを対象とする
  function isInputLike(el) {
    if (!el || el === document.body || el === document.documentElement) return false;
    const tag = (el.tagName || '').toUpperCase();
    if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') return true;
    if (el.isContentEditable) return true;
    return false;
  }

  // 現在フォーカスされている入力要素の情報を送信する。
  // フォーカスが入力要素でない場合は focused=false を送る。
  function report() {
    if (disconnected) return;
    const el = document.activeElement;
    if (isInputLike(el)) {
      port.postMessage({
        focused: true,
        id: el.id || '',
        tagName: (el.tagName || '').toLowerCase(),
        type: (el.getAttribute && el.getAttribute('type')) || '',
        name: el.name || '',
      });
    } else {
      port.postMessage({ focused: false });
    }
  }

  port.onMessage.addListener(function (msg) {
    // ネイティブ側からの明示的な問い合わせに応答する
    if (msg && msg.action === 'query') {
      report();
    }
  });

  // フォーカスの移動を監視して都度通知する
  document.addEventListener('focusin', report, true);
  document.addEventListener('focusout', function () {
    // focusout 直後に activeElement が body に戻るため、次のフレームで再評価する
    setTimeout(report, 0);
  }, true);

  // 接続直後に現在の状態を一度送信する
  report();
})();
