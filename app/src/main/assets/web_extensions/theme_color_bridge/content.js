// theme-colorメタタグの値をネイティブアプリに送信するコンテンツスクリプト
(function () {
  // トップフレームのみで実行
  if (window !== window.top) return;

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
})();
