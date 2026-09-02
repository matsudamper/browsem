// ページコンテキストで File を base64 化する。
// exportFunction / content script 内の FileReader や arrayBuffer は
// ページ由来の File を正しく読めないため、外部スクリプトとしてページへ注入する。
(function () {
  "use strict";

  if (typeof window.__browsemEncodeShareFiles === "function") return;

  function readFileAsBase64(file) {
    return new Promise(function (resolve, reject) {
      const reader = new FileReader();
      reader.onload = function () {
        const result = reader.result;
        if (typeof result !== "string") {
          reject(new DOMException("ファイルの読み込みに失敗しました", "NotAllowedError"));
          return;
        }
        const commaIndex = result.indexOf(",");
        resolve(commaIndex >= 0 ? result.slice(commaIndex + 1) : result);
      };
      reader.onerror = function () {
        reject(new DOMException("ファイルの読み込みに失敗しました", "NotAllowedError"));
      };
      reader.readAsDataURL(file);
    });
  }

  window.__browsemEncodeShareFiles = function (files) {
    return Promise.all(
      files.map(function (file) {
        return readFileAsBase64(file).then(function (base64) {
          return {
            name: file.name || "shared",
            type: file.type || "application/octet-stream",
            data: base64,
          };
        });
      }),
    );
  };
})();
