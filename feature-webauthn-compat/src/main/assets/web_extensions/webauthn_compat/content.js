// GeckoView の UVPAA 判定が実際の Credential Manager 対応状況と一致しないため、
// ページコンテキストでは platform authenticator を利用可能として扱う。
(function () {
  "use strict";

  const pageWin = window.wrappedJSObject;

  function log(message) {
    browser.runtime.sendMessage(`${message}\n${location.href}`).catch(() => {});
  }

  function bufferToBase64Url(buffer) {
    const bytes = pageWin.ArrayBuffer.isView(buffer)
      ? new pageWin.Uint8Array(buffer.buffer, buffer.byteOffset, buffer.byteLength)
      : new pageWin.Uint8Array(buffer);
    let binary = "";
    for (let i = 0; i < bytes.length; i++) {
      binary += String.fromCharCode(bytes[i]);
    }
    return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
  }

  function describe(value) {
    try {
      return JSON.stringify(value, (key, item) => {
        if (
          Object.prototype.toString.call(item) === "[object ArrayBuffer]" ||
          pageWin.ArrayBuffer.isView(item)
        ) {
          return `base64url:${bufferToBase64Url(item)}`;
        }
        return item;
      });
    } catch (error) {
      return `describe failed: ${error}`;
    }
  }

  const publicKeyCredential = pageWin.PublicKeyCredential;
  if (typeof publicKeyCredential !== "function") {
    log("PublicKeyCredential is not available");
    return;
  }

  publicKeyCredential.isUserVerifyingPlatformAuthenticatorAvailable = exportFunction(
    function () {
      log("isUserVerifyingPlatformAuthenticatorAvailable called, returning true");
      return pageWin.Promise.resolve(true);
    },
    pageWin,
  );

  function base64UrlToBuffer(base64Url) {
    const base64 = base64Url.replace(/-/g, "+").replace(/_/g, "/");
    const binary = atob(base64 + "=".repeat((4 - (base64.length % 4)) % 4));
    const bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) {
      bytes[i] = binary.charCodeAt(i);
    }
    return bytes.buffer;
  }

  function toRequestJson(publicKey) {
    const allowCredentials = [];
    const pageAllowCredentials = publicKey.allowCredentials ?? [];
    for (let i = 0; i < pageAllowCredentials.length; i++) {
      const credential = pageAllowCredentials[i];
      allowCredentials.push({
        type: credential.type,
        id: bufferToBase64Url(credential.id),
        transports: credential.transports ? Array.from(credential.transports) : undefined,
      });
    }
    return JSON.stringify({
      challenge: bufferToBase64Url(publicKey.challenge),
      timeout: publicKey.timeout,
      rpId: publicKey.rpId ?? location.hostname,
      allowCredentials,
      userVerification: publicKey.userVerification,
    });
  }

  function toPageCredential(credential) {
    const response = credential.response;
    const clientExtensionResults = credential.clientExtensionResults ?? {};
    return cloneInto(
      {
        id: credential.id,
        rawId: base64UrlToBuffer(credential.rawId),
        type: credential.type,
        authenticatorAttachment: credential.authenticatorAttachment ?? null,
        response: {
          clientDataJSON: base64UrlToBuffer(response.clientDataJSON),
          authenticatorData: base64UrlToBuffer(response.authenticatorData),
          signature: base64UrlToBuffer(response.signature),
          userHandle: response.userHandle ? base64UrlToBuffer(response.userHandle) : null,
        },
        getClientExtensionResults() {
          return cloneInto(clientExtensionResults, pageWin);
        },
      },
      pageWin,
      { cloneFunctions: true },
    );
  }

  // GeckoView は Credential Manager から候補が返らないと Play の特権 FIDO2 API へ回し、
  // 特権を持たないアプリではその失敗が処理されず Promise が止まる。
  // パスキーの取得は GeckoView を通さず、アプリから Credential Manager を直接呼ぶ。
  const credentials = pageWin.navigator.credentials;
  if (!credentials) {
    log("navigator.credentials is not available");
    return;
  }
  const originalGet = credentials.get;
  let nextRequestId = 1;
  credentials.get = exportFunction(
    function (options) {
      const requestId = nextRequestId++;
      const startedAt = Date.now();
      const pageOptions = options?.wrappedJSObject;
      log(`get#${requestId} called: options=${describe(pageOptions)}`);
      const publicKey = pageOptions?.publicKey;
      if (!publicKey) {
        return originalGet.call(credentials, pageOptions);
      }
      const requestJson = toRequestJson(publicKey);
      log(`get#${requestId} request to native: ${requestJson}`);
      return new pageWin.Promise(
        exportFunction(function (resolve, reject) {
          browser.runtime
            .sendMessage({ requestJson, origin: location.origin })
            .then(
              (response) => {
                const elapsed = Date.now() - startedAt;
                if (response?.fallback) {
                  log(`get#${requestId} native unavailable, falling back to GeckoView`);
                  originalGet.call(credentials, pageOptions).then(resolve, reject);
                  return;
                }
                if (!response?.credential) {
                  log(
                    `get#${requestId} rejected after ${elapsed}ms: ` +
                      `${response?.errorType}: ${response?.errorMessage}`,
                  );
                  reject(
                    new pageWin.DOMException(
                      `${response?.errorType}: ${response?.errorMessage}`,
                      "NotAllowedError",
                    ),
                  );
                  return;
                }
                log(
                  `get#${requestId} resolved after ${elapsed}ms: ` +
                    `id=${response.credential.id} ` +
                    `authenticatorAttachment=${response.credential.authenticatorAttachment}`,
                );
                resolve(toPageCredential(response.credential));
              },
              (error) => {
                log(`get#${requestId} native bridge failed: ${error}`);
                reject(new pageWin.DOMException(String(error), "UnknownError"));
              },
            );
        }, pageWin),
      );
    },
    pageWin,
  );
})();
