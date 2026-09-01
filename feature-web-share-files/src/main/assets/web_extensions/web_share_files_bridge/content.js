// Web Share API v2 (files) のワークアラウンド。
// Gecko が files 共有を未実装の間、ページコンテキストの navigator.share / canShare を
// exportFunction で差し替え、ファイル内容をネイティブアプリへ送って OS 共有シートを起動する。
(function () {
  "use strict";

  const NATIVE_APP = "webShareFilesBridge";
  const MAX_FILE_BYTES = 5 * 1024 * 1024;
  const MAX_TOTAL_BYTES = 10 * 1024 * 1024;
  const MAX_FILES = 10;
  const MAX_BASE64_CHARS_PER_FILE = 4 * Math.ceil(MAX_FILE_BYTES / 3);

  const pageWin = window.wrappedJSObject;
  if (typeof pageWin.navigator.share !== "function") return;

  function canShareFilesNatively() {
    if (typeof pageWin.navigator.canShare !== "function") return false;
    try {
      const file = new pageWin.File([""], "probe.txt", { type: "text/plain" });
      return pageWin.navigator.canShare({ files: [file] });
    } catch (_error) {
      return false;
    }
  }

  if (canShareFilesNatively()) return;

  const originalShare = pageWin.navigator.share.bind(pageWin.navigator);
  const originalCanShare =
    typeof pageWin.navigator.canShare === "function"
      ? pageWin.navigator.canShare.bind(pageWin.navigator)
      : null;
  const pendingNativeRequests = new Map();
  let pageShareReaderPromise = null;

  function isShareDataWithFiles(data) {
    return !!data && Array.isArray(data.files) && data.files.length > 0;
  }

  function validateFiles(files) {
    if (!Array.isArray(files) || files.length === 0) {
      return "共有するファイルがありません";
    }
    if (files.length > MAX_FILES) {
      return "共有できるファイル数の上限を超えています";
    }
    let totalBytes = 0;
    for (let i = 0; i < files.length; i++) {
      const file = files[i];
      if (!(file instanceof pageWin.File)) {
        return "無効なファイルです";
      }
      if (file.size > MAX_FILE_BYTES) {
        return "ファイルサイズが大きすぎます";
      }
      totalBytes += file.size;
      if (totalBytes > MAX_TOTAL_BYTES) {
        return "共有できる合計サイズの上限を超えています";
      }
    }
    return null;
  }

  function estimateDecodedBytes(base64) {
    let padding = 0;
    if (base64.endsWith("==")) {
      padding = 2;
    } else if (base64.endsWith("=")) {
      padding = 1;
    }
    return Math.floor((base64.length * 3) / 4) - padding;
  }

  function validateEncodedFiles(files) {
    if (!Array.isArray(files) || files.length === 0) {
      return "共有するファイルがありません";
    }
    if (files.length > MAX_FILES) {
      return "共有できるファイル数の上限を超えています";
    }
    let totalBytes = 0;
    for (let i = 0; i < files.length; i++) {
      const file = files[i];
      if (!file || typeof file.data !== "string" || file.data.length === 0) {
        return "無効なファイルです";
      }
      if (file.data.length > MAX_BASE64_CHARS_PER_FILE) {
        return "ファイルサイズが大きすぎます";
      }
      const decodedBytes = estimateDecodedBytes(file.data);
      if (decodedBytes > MAX_FILE_BYTES) {
        return "ファイルサイズが大きすぎます";
      }
      totalBytes += decodedBytes;
      if (totalBytes > MAX_TOTAL_BYTES) {
        return "共有できる合計サイズの上限を超えています";
      }
    }
    return null;
  }

  function createRequestId() {
    return (
      "share-" + Date.now().toString(36) + "-" + Math.random().toString(36).slice(2)
    );
  }

  function ensurePageShareReader() {
    if (typeof pageWin.__browsemEncodeShareFiles === "function") {
      return Promise.resolve();
    }
    if (!pageShareReaderPromise) {
      pageShareReaderPromise = new Promise(function (resolve, reject) {
        const script = document.createElement("script");
        script.src = browser.runtime.getURL("page-share-reader.js");
        script.onload = function () {
          if (typeof pageWin.__browsemEncodeShareFiles === "function") {
            resolve();
          } else {
            reject(new DOMException("共有の準備ができていません", "NotAllowedError"));
          }
        };
        script.onerror = function () {
          reject(new DOMException("共有の準備ができていません", "NotAllowedError"));
        };
        const root = document.documentElement || document.head || document;
        root.appendChild(script);
      });
    }
    return pageShareReaderPromise;
  }

  function encodeFilesInPage(files) {
    return ensurePageShareReader().then(function () {
      return pageWin.__browsemEncodeShareFiles(files);
    });
  }

  pageWin.navigator.canShare = exportFunction(function (data) {
    if (isShareDataWithFiles(data)) {
      return validateFiles(data.files) === null;
    }
    if (originalCanShare) {
      return originalCanShare(data);
    }
    return true;
  }, pageWin);

  pageWin.navigator.share = exportFunction(function (data) {
    if (!isShareDataWithFiles(data)) {
      return originalShare(data);
    }

    if (
      !pageWin.navigator.userActivation ||
      !pageWin.navigator.userActivation.isActive
    ) {
      return Promise.reject(
        new DOMException("ユーザー操作なしでは共有できません", "NotAllowedError"),
      );
    }

    if (pendingNativeRequests.size > 0) {
      return Promise.reject(
        new DOMException("共有リクエストが競合しました", "AbortError"),
      );
    }

    const validationError = validateFiles(data.files);
    if (validationError) {
      return Promise.reject(new DOMException(validationError, "NotAllowedError"));
    }

    const requestId = createRequestId();

    return encodeFilesInPage(data.files).then(function (files) {
      const encodedError = validateEncodedFiles(files);
      if (encodedError) {
        throw new DOMException(encodedError, "NotAllowedError");
      }

      return new Promise(function (resolve, reject) {
        pendingNativeRequests.set(requestId, { resolve: resolve, reject: reject });
        browser.runtime
          .sendNativeMessage(NATIVE_APP, {
            requestId: requestId,
            title: data.title || "",
            text: data.text || "",
            url: data.url || "",
            files: files,
          })
          .then(function (response) {
            pendingNativeRequests.delete(requestId);
            const result = response || {};
            if (result.success) {
              resolve(undefined);
            } else {
              reject(
                new DOMException(
                  result.error || "共有に失敗しました",
                  result.errorName || "AbortError",
                ),
              );
            }
          })
          .catch(function (error) {
            pendingNativeRequests.delete(requestId);
            reject(error);
          });
      });
    });
  }, pageWin);
})();
