(function () {
  let port = null;
  let connecting = null;
  const pending = new Map();
  let dropped = 0;

  function connect() {
    try {
      const connected = browser.runtime.connectNative("mediaBridge");
      connecting = connected;
      connected.onDisconnect.addListener(function () {
        if (connecting !== connected) return;
        port = null;
        connecting = null;
        setTimeout(connect, 1000);
      });
      connected.onMessage.addListener(function (message) {
        if (message.type !== "diagnosticsReady" || connecting !== connected) return;
        port = connected;
        pending.forEach(function (payload) {
          connected.postMessage(Object.assign({relayDropped: dropped}, payload));
          dropped = 0;
        });
        pending.clear();
      });
    } catch (_error) {
      port = null;
      setTimeout(connect, 1000);
    }
  }

  browser.runtime.onMessage.addListener(function (message) {
    if (message.type !== "mediaDiagnostic") return;
    const payload = message.payload;
    if (port !== null) {
      try {
        port.postMessage(payload);
        return Promise.resolve(true);
      } catch (_error) {
        port = null;
        setTimeout(connect, 1000);
      }
    }
    const key = [payload.pageUrl, payload.event, payload.origin, payload.identity, payload.reason].join(":");
    if (pending.has(key)) payload.suppressedBeforeConnect = (pending.get(key).suppressedBeforeConnect || 0) + 1;
    else if (pending.size >= 32) {
      const oldest = pending.keys().next().value;
      dropped += 1 + (pending.get(oldest).suppressedBeforeConnect || 0);
      pending.delete(oldest);
    }
    pending.set(key, payload);
    return Promise.resolve(true);
  });
  connect();
})();
