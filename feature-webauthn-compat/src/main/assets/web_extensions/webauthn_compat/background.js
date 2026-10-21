// content script からは拡張単位の MessageDelegate に届かないため、background 経由でアプリへ送る。
browser.runtime.onMessage.addListener((message) => {
  browser.runtime.sendNativeMessage("browsem_webauthn_compat_log", message).catch(() => {});
});
