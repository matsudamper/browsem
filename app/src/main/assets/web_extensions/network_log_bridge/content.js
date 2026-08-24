// ネットワークログのタブ対応付け用コンテンツスクリプト。
// webRequest の tabId とネイティブの GeckoSession を結び付けるため、
// 自分のタブ ID をバックグラウンドから受け取ってネイティブへ通知する。
(function () {
  // トップフレームのみで実行する
  if (window !== window.top) return;

  const port = browser.runtime.connectNative('networkLogTabBridge');

  browser.runtime.sendMessage({ action: 'whoami' }).then(function (response) {
    if (!response || typeof response.tabId !== 'number') return;
    try {
      port.postMessage({ action: 'tabId', tabId: response.tabId });
    } catch (ignoredPortError) {
      // ポート切断後は送信できないため無視する
    }
  }).catch(function () {
    // バックグラウンドが未起動の場合等は対応付けを諦める（全タブ表示にフォールバックする）
  });
})();
