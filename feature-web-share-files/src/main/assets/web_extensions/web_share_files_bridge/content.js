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

  function normalizeFiles(files) {
    if (!files) return null;
    if (Array.isArray(files)) return files;
    if (typeof files.length === "number") {
      const normalized = [];
      for (let i = 0; i < files.length; i++) {
        normalized.push(files[i]);
      }
      return normalized;
    }
    if (typeof files[Symbol.iterator] === "function") {
      return Array.from(files);
    }
    return null;
  }

  function isShareDataWithFiles(data) {
    const files = normalizeFiles(data && data.files);
    return files !== null && files.length > 0;
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

  function encodeBufferToBase64(buffer) {
    const bytes = new Uint8Array(buffer);
    let binary = "";
    const chunkSize = 0x8000;
    for (let i = 0; i < bytes.length; i += chunkSize) {
      binary += String.fromCharCode.apply(
        null,
        bytes.subarray(i, Math.min(i + chunkSize, bytes.length)),
      );
    }
    return btoa(binary);
  }

  function readFileAsBase64(file) {
    return file.arrayBuffer().then(function (buffer) {
      if (buffer.byteLength !== file.size) {
        throw new DOMException("ファイルの読み込みに失敗しました", "NotAllowedError");
      }
      return encodeBufferToBase64(buffer);
    });
  }

  function encodeFiles(files) {
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
  }

  pageWin.navigator.canShare = exportFunction(function (data) {
    const files = normalizeFiles(data && data.files);
    if (files !== null && files.length > 0) {
      return validateFiles(files) === null;
    }
    if (originalCanShare) {
      return originalCanShare(data);
    }
    return true;
  }, pageWin);

  pageWin.navigator.share = exportFunction(function (data) {
    const files = normalizeFiles(data && data.files);
    if (files === null || files.length === 0) {
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

    const validationError = validateFiles(files);
    if (validationError) {
      return Promise.reject(new DOMException(validationError, "NotAllowedError"));
    }

    const requestId = createRequestId();

    return encodeFiles(files).then(function (encodedFiles) {
      const encodedError = validateEncodedFiles(encodedFiles);
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
            files: encodedFiles,
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
