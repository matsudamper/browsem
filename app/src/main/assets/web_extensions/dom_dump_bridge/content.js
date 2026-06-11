// タブメニューの「DOMをダンプ」からの要求に応じて、現在のページの
// レンダリング済み DOM をシリアライズして返すコンテンツスクリプト。
// サイト固有の不具合調査で実際の DOM 構造を確認するために使用する。
(function () {
  // トップフレームのみで実行
  if (window !== window.top) return;

  // ポートメッセージが大きくなりすぎないよう分割して送る
  const CHUNK_SIZE = 256 * 1024;

  const port = browser.runtime.connectNative("domDumpBridge");

  port.onMessage.addListener(function (msg) {
    if (!msg || msg.action !== "dump") return;
    let html;
    try {
      const doctype = document.doctype
        ? "<!DOCTYPE " + document.doctype.name + ">\n"
        : "";
      html =
        "<!-- URL: " + location.href + " -->\n" +
        "<!-- 取得日時: " + new Date().toISOString() + " -->\n" +
        doctype +
        document.documentElement.outerHTML;
    } catch (e) {
      port.postMessage({ type: "error", message: String(e) });
      return;
    }
    const total = Math.ceil(html.length / CHUNK_SIZE) || 1;
    for (let i = 0; i < total; i++) {
      port.postMessage({
        type: "chunk",
        index: i,
        total: total,
        data: html.slice(i * CHUNK_SIZE, (i + 1) * CHUNK_SIZE),
      });
    }
  });
})();
