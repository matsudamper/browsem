from pathlib import Path


path = Path("app/src/main/assets/web_extensions/page_translation_bridge/content.js")
text = path.read_text()

replacements = [
    (
        """  const HANDSHAKE_RETRY_MIN_MS = 500;
  const HANDSHAKE_RETRY_MAX_MS = 30000;
  const HANDSHAKE_MAX_RETRY_COUNT = 5;
""",
        """  const HANDSHAKE_RETRY_MS = 1000;
""",
    ),
    (
        """  let nativeHandshakePending = false;
  let handshakeRetryCount = 0;
""",
        """  let nativeHandshakePending = false;
""",
    ),
    (
        """    port.onDisconnect.addListener(function () {
      port = null;
      stopObserver();
      // 一度つながった相手なので、切断後は改めて上限まで再試行してよい
      handshakeRetryCount = 0;
      waitForNative();
    });
""",
        """    port.onDisconnect.addListener(function () {
      port = null;
      stopObserver();
      waitForNative();
    });
""",
    ),
    (
        """  function scheduleNativeHandshake() {
    // ネイティブ側は表示中セッションへ先に MessageDelegate を登録する。
    // Compose と content script の開始順序の差を吸収するため、有限回だけ再試行する。
    if (handshakeRetryCount >= HANDSHAKE_MAX_RETRY_COUNT) return;
    const delay = Math.min(
      HANDSHAKE_RETRY_MIN_MS * Math.pow(2, handshakeRetryCount),
      HANDSHAKE_RETRY_MAX_MS,
    );
    handshakeRetryCount += 1;
    setTimeout(waitForNative, delay);
  }

  function waitForNative() {
    if (port !== null || nativeHandshakePending) return;
""",
        """  function scheduleNativeHandshake() {
    // MessageDelegate の登録が document_start より後になることがあるため、表示中だけ再試行する。
    if (document.visibilityState !== 'visible') return;
    setTimeout(waitForNative, HANDSHAKE_RETRY_MS);
  }

  function waitForNative() {
    if (document.visibilityState !== 'visible' || port !== null || nativeHandshakePending) return;
""",
    ),
    (
        """  function retryNativeHandshakeWhenVisible() {
    if (document.visibilityState !== 'visible' || port !== null) return;
    handshakeRetryCount = 0;
    waitForNative();
  }
""",
        """  function retryNativeHandshakeWhenVisible() {
    if (document.visibilityState !== 'visible' || port !== null) return;
    waitForNative();
  }
""",
    ),
]

for old, new in replacements:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"expected exactly one match, found {count}: {old[:120]!r}")
    text = text.replace(old, new, 1)

path.write_text(text)
