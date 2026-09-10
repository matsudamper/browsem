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
})();
