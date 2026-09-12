from pathlib import Path


path = Path("app/src/main/assets/web_extensions/page_translation_bridge/content.js")
text = path.read_text()

replacements = [
    (
        "  const HANDSHAKE_RETRY_MS = 1000;\n",
        "  const NATIVE_RECONNECT_DELAY_MS = 1000;\n",
    ),
    (
        "  let nativeHandshakePending = false;\n",
        "  let nativeReconnectTimer = null;\n",
    ),
    (
        """  function connect() {
    if (port !== null) return;
    try {
      port = browser.runtime.connectNative(NATIVE_APP_ID);
    } catch (error) {
      port = null;
      waitForNative();
      return;
    }
    port.onMessage.addListener(onNativeMessage);
    port.onDisconnect.addListener(function () {
      port = null;
      stopObserver();
      waitForNative();
    });
  }

  function scheduleNativeHandshake() {
    // MessageDelegate の登録が document_start より後になることがあるため、表示中だけ再試行する。
    if (document.visibilityState !== 'visible') return;
    setTimeout(waitForNative, HANDSHAKE_RETRY_MS);
  }

  function waitForNative() {
    if (document.visibilityState !== 'visible' || port !== null || nativeHandshakePending) return;
    nativeHandshakePending = true;
    browser.runtime.sendNativeMessage(NATIVE_APP_ID, {
      action: 'ready',
      documentId: documentId,
    }).then(
      function (response) {
        nativeHandshakePending = false;
        if (response && response.connect) {
          handshakeRetryCount = 0;
          connect();
          return;
        }
        scheduleNativeHandshake();
      },
      function () {
        nativeHandshakePending = false;
        scheduleNativeHandshake();
      },
    );
  }

  function retryNativeHandshakeWhenVisible() {
    if (document.visibilityState !== 'visible' || port !== null) return;
    waitForNative();
  }

  document.addEventListener('visibilitychange', retryNativeHandshakeWhenVisible);
  window.addEventListener('pageshow', retryNativeHandshakeWhenVisible);

  waitForNative();
""",
        """  function scheduleNativeReconnect() {
    if (document.visibilityState !== 'visible' || port !== null || nativeReconnectTimer !== null) return;
    nativeReconnectTimer = setTimeout(function () {
      nativeReconnectTimer = null;
      connect();
    }, NATIVE_RECONNECT_DELAY_MS);
  }

  function connect() {
    if (document.visibilityState !== 'visible' || port !== null) return;
    let connectedPort;
    try {
      connectedPort = browser.runtime.connectNative(NATIVE_APP_ID);
    } catch (error) {
      scheduleNativeReconnect();
      return;
    }
    port = connectedPort;
    connectedPort.onMessage.addListener(onNativeMessage);
    connectedPort.onDisconnect.addListener(function () {
      if (port !== connectedPort) return;
      port = null;
      stopObserver();
      scheduleNativeReconnect();
    });
  }

  function connectWhenVisible() {
    if (document.visibilityState !== 'visible') return;
    connect();
  }

  document.addEventListener('visibilitychange', connectWhenVisible);
  window.addEventListener('pageshow', connectWhenVisible);

  connect();
""",
    ),
]

for old, new in replacements:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"expected exactly one match, found {count}: {old[:120]!r}")
    text = text.replace(old, new, 1)

if "sendNativeMessage" in text:
    raise RuntimeError("sendNativeMessage handshake still remains")

path.write_text(text)
