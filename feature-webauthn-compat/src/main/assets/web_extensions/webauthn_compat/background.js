// content script からは拡張単位の MessageDelegate に届かないため、background 経由でアプリへ送る。
browser.runtime.onMessage.addListener((message) => {
  if (typeof message === "string") {
    browser.runtime.sendNativeMessage("browsem_webauthn_compat_log", message).catch(() => {});
    return undefined;
  }
  return browser.runtime.sendNativeMessage("browsem_webauthn_compat_credential", message);
});
