// theme-colorメタタグの値をネイティブアプリに送信するコンテンツスクリプト
(function () {
  // トップフレームのみで実行
  if (window !== window.top) return;

  function sendThemeColor() {
    const meta = document.querySelector('meta[name="theme-color"]');
    const color = meta ? meta.getAttribute("content") : null;
    console.log("[ThemeColor] url=" + location.href + " color=" + color);

    browser.runtime
      .sendNativeMessage("themeColorBridge", {
        themeColor: color ? color.trim() : null,
        url: location.href,
      })
      .catch(function (e) {
        console.error("[ThemeColor] sendNativeMessage error: " + e);
      });
  }

  // 初回ページ読み込み時に送信
  sendThemeColor();

  // bfcache（戻る/進む操作）からページが復元された際にも送信
  // bfcacheではonPageStartが発火せずJSも再実行されないため、pageshow経由で色を更新する
  window.addEventListener("pageshow", function (event) {
    if (event.persisted) {
      sendThemeColor();
    }
  });
})();
