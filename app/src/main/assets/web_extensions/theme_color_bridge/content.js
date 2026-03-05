// theme-colorメタタグの値をネイティブアプリに送信するコンテンツスクリプト
(function () {
  const meta = document.querySelector('meta[name="theme-color"]');
  const color = meta ? meta.getAttribute("content") : null;
  browser.runtime.sendNativeMessage("themeColorBridge", {
    themeColor: color ? color.trim() : null,
  });
})();
