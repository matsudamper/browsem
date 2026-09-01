// Web Share API v2 (files) のワークアラウンド。
// Gecko が files 共有を未実装の間、ページコンテキストで navigator.share / canShare を
// ポリフィルし、ファイル内容をネイティブアプリへ送って OS 共有シートを起動する。
(function () {
  "use strict";

  const NATIVE_APP = "webShareFilesBridge";
  const PAGE_MESSAGE_TYPE = "browsem-web-share-files-request";
  const PAGE_RESPONSE_TYPE = "browsem-web-share-files-response";
  const MAX_FILE_BYTES = 5 * 1024 * 1024;
  const MAX_TOTAL_BYTES = 10 * 1024 * 1024;
  const MAX_FILES = 10;

  const pendingPageRequests = new Map();

  function injectPagePolyfill() {
    const script = document.createElement("script");
    script.textContent =
      "(" +
      function (
        pageMessageType,
        pageResponseType,
        maxFileBytes,
        maxTotalBytes,
        maxFiles,
      ) {
        "use strict";

        if (typeof navigator.share !== "function") return;

        function canShareFilesNatively() {
          if (typeof navigator.canShare !== "function") return false;
          try {
            const file = new File([""], "probe.txt", { type: "text/plain" });
            return navigator.canShare({ files: [file] });
          } catch (_error) {
            return false;
          }
        }

        if (canShareFilesNatively()) return;

        const originalShare = navigator.share.bind(navigator);
        const originalCanShare =
          typeof navigator.canShare === "function"
            ? navigator.canShare.bind(navigator)
            : null;
        const pendingRequests = new Map();

        function isShareDataWithFiles(data) {
          return !!data && Array.isArray(data.files) && data.files.length > 0;
        }

        function validateFiles(files) {
          if (!Array.isArray(files) || files.length === 0) {
            return "共有するファイルがありません";
          }
          if (files.length > maxFiles) {
            return "共有できるファイル数の上限を超えています";
          }
          let totalBytes = 0;
          for (let i = 0; i < files.length; i++) {
            const file = files[i];
            if (!(file instanceof File)) {
              return "無効なファイルです";
            }
            if (file.size > maxFileBytes) {
              return "ファイルサイズが大きすぎます";
            }
            totalBytes += file.size;
            if (totalBytes > maxTotalBytes) {
              return "共有できる合計サイズの上限を超えています";
            }
          }
          return null;
        }

        function readFileAsBase64(file) {
          return new Promise(function (resolve, reject) {
            const reader = new FileReader();
            reader.onload = function () {
              const result = reader.result;
              if (typeof result !== "string") {
                reject(new Error("ファイルの読み込みに失敗しました"));
                return;
              }
              const commaIndex = result.indexOf(",");
              resolve(commaIndex >= 0 ? result.slice(commaIndex + 1) : result);
            };
            reader.onerror = function () {
              reject(reader.error || new Error("ファイルの読み込みに失敗しました"));
            };
            reader.readAsDataURL(file);
          });
        }

        function shareFilesViaBridge(data) {
          const validationError = validateFiles(data.files);
          if (validationError) {
            return Promise.reject(new DOMException(validationError, "NotAllowedError"));
          }

          const requestId =
            "share-" + Date.now().toString(36) + "-" + Math.random().toString(36).slice(2);

          return new Promise(function (resolve, reject) {
            pendingRequests.set(requestId, { resolve: resolve, reject: reject });

            Promise.all(
              data.files.map(function (file) {
                return readFileAsBase64(file).then(function (base64) {
                  return {
                    name: file.name || "shared",
                    type: file.type || "application/octet-stream",
                    data: base64,
                  };
                });
              }),
            )
              .then(function (files) {
                window.postMessage(
                  {
                    type: pageMessageType,
                    requestId: requestId,
                    title: data.title || "",
                    text: data.text || "",
                    url: data.url || "",
                    files: files,
                  },
                  "*",
                );
              })
              .catch(function (error) {
                pendingRequests.delete(requestId);
                reject(error);
              });
          });
        }

        window.addEventListener("message", function (event) {
          if (event.source !== window) return;
          const payload = event.data;
          if (!payload || payload.type !== pageResponseType) return;
          const pending = pendingRequests.get(payload.requestId);
          if (!pending) return;
          pendingRequests.delete(payload.requestId);
          if (payload.success) {
            pending.resolve();
          } else {
            pending.reject(
              new DOMException(
                payload.error || "共有に失敗しました",
                payload.errorName || "AbortError",
              ),
            );
          }
        });

        navigator.canShare = function (data) {
          if (isShareDataWithFiles(data)) {
            return validateFiles(data.files) === null;
          }
          if (originalCanShare) {
            return originalCanShare(data);
          }
          return true;
        };

        navigator.share = function (data) {
          if (isShareDataWithFiles(data)) {
            return shareFilesViaBridge(data || {});
          }
          return originalShare(data);
        };
      }.toString() +
      ")(" +
      JSON.stringify(PAGE_MESSAGE_TYPE) +
      "," +
      JSON.stringify(PAGE_RESPONSE_TYPE) +
      "," +
      MAX_FILE_BYTES +
      "," +
      MAX_TOTAL_BYTES +
      "," +
      MAX_FILES +
      ");";

    const root = document.documentElement || document.head || document;
    root.appendChild(script);
    script.remove();
  }

  function respondToPage(requestId, success, error, errorName) {
    window.postMessage(
      {
        type: PAGE_RESPONSE_TYPE,
        requestId: requestId,
        success: success,
        error: error || "",
        errorName: errorName || "AbortError",
      },
      "*",
    );
  }

  window.addEventListener("message", function (event) {
    if (event.source !== window) return;
    const payload = event.data;
    if (!payload || payload.type !== PAGE_MESSAGE_TYPE) return;
    if (!payload.requestId) return;

    pendingPageRequests.set(payload.requestId, true);
    browser.runtime
      .sendNativeMessage(NATIVE_APP, {
        requestId: payload.requestId,
        title: payload.title || "",
        text: payload.text || "",
        url: payload.url || "",
        files: payload.files || [],
      })
      .then(function (response) {
        pendingPageRequests.delete(payload.requestId);
        const result = response || {};
        respondToPage(
          payload.requestId,
          !!result.success,
          result.error || "",
          result.errorName || "AbortError",
        );
      })
      .catch(function (error) {
        pendingPageRequests.delete(payload.requestId);
        respondToPage(
          payload.requestId,
          false,
          String(error),
          "AbortError",
        );
      });
  });

  injectPagePolyfill();
})();
