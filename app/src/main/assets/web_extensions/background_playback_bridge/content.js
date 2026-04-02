// バックグラウンド再生を維持するために Page Visibility API を偽装するコンテンツスクリプト。
// ネイティブアプリからドメインごとの有効設定を受け取り、許可されたドメインでのみパッチを適用する。
(function () {
  // トップフレームのみで動作させる
  if (window !== window.top) return;

  const port = browser.runtime.connectNative('backgroundPlaybackBridge');

  port.onMessage.addListener(function (msg) {
    if (msg.enabled === true) {
      applyPatch();
    }
  });

  // 現在のホスト名をネイティブに送信して有効かどうかを問い合わせる
  port.postMessage({ hostname: window.location.hostname });

  function applyPatch() {
    // document.hidden を常に false に見せかける
    Object.defineProperty(document, 'hidden', {
      get: function () { return false; },
      configurable: true,
    });
    // document.visibilityState を常に 'visible' に見せかける
    Object.defineProperty(document, 'visibilityState', {
      get: function () { return 'visible'; },
      configurable: true,
    });
    // visibilitychange イベントの伝播を止めてサイト側のポーズ処理を無効化する
    document.addEventListener('visibilitychange', function (e) {
      e.stopImmediatePropagation();
    }, true);
  }
})();
