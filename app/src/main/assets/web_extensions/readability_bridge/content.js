// Readability を使って記事を抽出し、ネイティブアプリに送信するコンテンツスクリプト
(function () {
  // トップフレームのみで実行
  if (window !== window.top) return;

  // ネイティブアプリとの双方向ポートを確立
  var port = browser.runtime.connectNative("readabilityBridge");

  port.onMessage.addListener(function (msg) {
    if (msg.action !== "extract") return;
    try {
      var doc = document.cloneNode(true);
      var article = new Readability(doc).parse();
      if (article) {
        port.postMessage({
          success: true,
          title: article.title || "",
          byline: article.byline || "",
          content: article.content || ""
        });
      } else {
        port.postMessage({ success: false, error: "parse failed" });
      }
    } catch (e) {
      port.postMessage({ success: false, error: String(e) });
    }
  });
})();
