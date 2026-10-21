// GeckoView の UVPAA 判定が実際の Credential Manager 対応状況と一致しないため、
// ページコンテキストでは platform authenticator を利用可能として扱う。
(function () {
  "use strict";

  const pageWin = window.wrappedJSObject;

  function log(message) {
    browser.runtime.sendMessage(`${location.href} ${message}`).catch(() => {});
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

  // GeckoView は Credential Manager から候補が返らないと Play の特権 FIDO2 API へ回し、
  // 特権を持たないアプリではその失敗が処理されず Promise が止まる。
  // allowCredentials で絞り込むと候補が返らないことがあるため、指定を外して要求する。
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
      let requestOptions = pageOptions;
      if (publicKey?.allowCredentials) {
        const publicKeyWithoutAllowCredentials = pageWin.Object.assign(new pageWin.Object(), publicKey);
        delete publicKeyWithoutAllowCredentials.allowCredentials;
        requestOptions = pageWin.Object.assign(new pageWin.Object(), pageOptions);
        requestOptions.publicKey = publicKeyWithoutAllowCredentials;
        log(`get#${requestId} allowCredentials removed: options=${describe(requestOptions)}`);
      }
      let result;
      try {
        result = originalGet.call(credentials, requestOptions);
      } catch (error) {
        log(`get#${requestId} threw synchronously: ${error}`);
        throw error;
      }
      result.then(
        exportFunction(function (credential) {
          log(
            `get#${requestId} resolved after ${Date.now() - startedAt}ms: ` +
              `type=${credential?.type} id=${credential?.id} ` +
              `authenticatorAttachment=${credential?.authenticatorAttachment}`,
          );
        }, pageWin),
        exportFunction(function (error) {
          log(
            `get#${requestId} rejected after ${Date.now() - startedAt}ms: ` +
              `${error?.name}: ${error?.message}`,
          );
        }, pageWin),
      );
      return result;
    },
    pageWin,
  );
})();
