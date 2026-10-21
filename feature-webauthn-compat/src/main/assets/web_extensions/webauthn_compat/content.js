// GeckoView の UVPAA 判定が実際の Credential Manager 対応状況と一致しないため、
// ページコンテキストでは platform authenticator を利用可能として扱う。
(function () {
  "use strict";

  const pageWin = window.wrappedJSObject;
  const publicKeyCredential = pageWin.PublicKeyCredential;
  if (typeof publicKeyCredential !== "function") return;

  publicKeyCredential.isUserVerifyingPlatformAuthenticatorAvailable = exportFunction(
    function () {
      return pageWin.Promise.resolve(true);
    },
    pageWin,
  );

  // GeckoView は Credential Manager から候補が返らないと Play の特権 FIDO2 API へ回し、
  // 特権を持たないアプリではその失敗が処理されず Promise が止まる。
  // allowCredentials で絞り込むと候補が返らないことがあるため、指定を外して要求する。
  const credentials = pageWin.navigator.credentials;
  if (!credentials) return;
  const originalGet = credentials.get;
  credentials.get = exportFunction(
    function (options) {
      const pageOptions = options?.wrappedJSObject;
      const publicKey = pageOptions?.publicKey;
      if (!publicKey?.allowCredentials) {
        return originalGet.call(credentials, pageOptions);
      }
      const publicKeyWithoutAllowCredentials = pageWin.Object.assign(new pageWin.Object(), publicKey);
      delete publicKeyWithoutAllowCredentials.allowCredentials;
      const optionsWithoutAllowCredentials = pageWin.Object.assign(new pageWin.Object(), pageOptions);
      optionsWithoutAllowCredentials.publicKey = publicKeyWithoutAllowCredentials;
      return originalGet.call(credentials, optionsWithoutAllowCredentials);
    },
    pageWin,
  );
})();
