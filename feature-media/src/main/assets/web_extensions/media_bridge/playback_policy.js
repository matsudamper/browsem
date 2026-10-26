const backgroundPlaybackPolicy = (function () {
  const page = window.wrappedJSObject;
  const nativePrototype = HTMLMediaElement.prototype;
  const nativePlay = nativePrototype.play;
  const nativePause = nativePrototype.pause;
  const nativeMuted = Object.getOwnPropertyDescriptor(nativePrototype, "muted");
  const records = new WeakMap();
  const endedMedia = new WeakSet();
  const forcedMuted = new Map();
  const pendingInitialPlayback = new Set();
  const documentId = Array.from(crypto.getRandomValues(new Uint32Array(4))).join("-");
  let serial = 0;
  let inBackground = null;
  let receivedNativePolicy = false;
  let visibleFallback = false;
  const initialPolicyDeadline = performance.now() + 250;
  let continuation = null;
  let retainedPlayback = null;
  let commandExpiresAt = 0;
  let onBlocked = function () {};
  let onDiagnostic = function () {};
  const startedAt = performance.now();
  let commandIssuedAt = 0;
  let resolveReady;
  const ready = new page.Promise(exportFunction(function (resolve) { resolveReady = resolve; }, page));

  function mediaTitle(media) {
    const metadata = navigator.mediaSession && navigator.mediaSession.metadata;
    return (metadata && metadata.title) || media.getAttribute("data-media-title") ||
      media.getAttribute("title") || "";
  }

  function describe(media, id) {
    return {
      media: media,
      id: id,
      src: media.src || media.currentSrc || "",
      srcObject: media.srcObject,
      title: mediaTitle(media),
      position: media.currentTime,
      duration: media.duration,
    };
  }

  function matches(previous, media) {
    if (!previous || previous.media !== media || media.ended) return false;
    const current = describe(media, previous.id);
    if (previous.title && current.title && previous.title !== current.title) return false;
    if (previous.src === current.src && previous.srcObject === current.srcObject) return true;
    // URL が変わった画質変更は、同じ要素で再生位置と長さを維持したときだけ継続する。
    return previous.srcObject === current.srcObject && previous.position > 1 &&
      Math.abs(previous.position - current.position) <= 1 &&
      Number.isFinite(previous.duration) && previous.duration > 0 &&
      Math.abs(previous.duration - current.duration) <= 1;
  }

  function identity(media) {
    if (!media) return "";
    if (endedMedia.has(media)) {
      if (media.ended) return records.has(media) ? records.get(media).id : "";
      endedMedia.delete(media);
      records.delete(media);
    }
    if (matches(continuation, media)) return continuation.id;
    if (matches(retainedPlayback, media)) return retainedPlayback.id;
    const previous = records.get(media);
    if (matches(previous, media)) return previous.id;
    const record = describe(media, documentId + ":" + (++serial));
    records.set(media, record);
    return record.id;
  }

  function restoreMuted(media) {
    if (!forcedMuted.has(media)) return;
    const muted = forcedMuted.get(media);
    forcedMuted.delete(media);
    nativeMuted.set.call(media, muted);
  }

  function permit(media, consumeCommand) {
    if (inBackground === false) return true;
    if (inBackground === null) return false;
    if (matches(continuation, media)) return true;
    if (consumeCommand && performance.now() <= commandExpiresAt && commandExpiresAt > 0) {
      commandExpiresAt = 0;
      const record = describe(media, documentId + ":" + (++serial));
      records.set(media, record);
      continuation = record;
      retainedPlayback = record;
      return true;
    }
    return false;
  }

  function silence(media, report, origin) {
    if (inBackground === null && !media.paused && !media.ended) pendingInitialPlayback.add(media);
    if (!forcedMuted.has(media)) forcedMuted.set(media, nativeMuted.get.call(media));
    nativeMuted.set.call(media, true);
    nativePause.call(media);
    if (report && inBackground === true) onBlocked(media, origin || "content script");
  }

  function reasonFor(media) {
    if (matches(continuation, media)) return "閉じた時点または通知操作で許可したidentityに一致";
    if (commandExpiresAt > 0 && performance.now() <= commandExpiresAt) return "通知操作の猶予内";
    return "許可のない背景再生";
  }

  function diagnostic(event, media, origin, reason) {
    onDiagnostic({
      event: event,
      origin: origin,
      reason: reason,
      identity: media ? identity(media) : "",
      src: media ? (media.currentSrc || media.src || "").slice(0, 1024) : "",
      elapsedMs: performance.now() - startedAt,
      commandElapsedMs: commandIssuedAt ? performance.now() - commandIssuedAt : null,
      nativePolicy: receivedNativePolicy,
      inBackground: inBackground,
    });
  }

  function protect(media) {
    if (permit(media, false)) {
      if (inBackground === false) restoreMuted(media);
    } else {
      silence(media, !media.paused && !media.ended);
    }
  }

  function play(media) {
    if (inBackground === null) return ready.then(exportFunction(function () { return play(media); }, page));
    const reason = reasonFor(media);
    if (!permit(media, true)) {
      silence(media, true, "ページの play()");
      return page.Promise.reject(new page.DOMException("Background playback is blocked", "NotAllowedError"));
    }
    if (inBackground) diagnostic("ページ許可", media, "ページの play()", reason);
    restoreMuted(media);
    if (continuation && continuation.media === media) {
      continuation = describe(media, continuation.id);
      records.set(media, continuation);
    }
    return new page.Promise(exportFunction(function (resolve, reject) {
      nativePlay.call(media).then(resolve, reject);
    }, page));
  }

  const pagePrototype = page.HTMLMediaElement.prototype;
  Object.defineProperty(pagePrototype, "play", {
    configurable: true,
    writable: true,
    value: exportFunction(function () {
      return play(XPCNativeWrapper(this));
    }, page),
  });

  ["muted", "volume", "src", "srcObject"].forEach(function (name) {
    const nativeDescriptor = Object.getOwnPropertyDescriptor(nativePrototype, name);
    const pageDescriptor = Object.getOwnPropertyDescriptor(pagePrototype, name);
    if (!nativeDescriptor || !nativeDescriptor.set || !pageDescriptor) return;
    Object.defineProperty(pagePrototype, name, {
      configurable: pageDescriptor.configurable,
      enumerable: pageDescriptor.enumerable,
      get: pageDescriptor.get,
      set: exportFunction(function (value) {
        const media = XPCNativeWrapper(this);
        if ((name === "muted" && !value) || (name === "volume" && value > 0)) {
          const reason = reasonFor(media);
          const origin = name === "muted" ? "アンミュート" : "音量変更";
          if (!permit(media, true)) {
            silence(media, true, origin);
            if (name === "muted") {
              forcedMuted.set(media, !!value);
              return;
            }
          } else {
            if (inBackground) diagnostic("ページ許可", media, origin, reason);
            restoreMuted(media);
          }
        } else if (name === "muted" && forcedMuted.has(media)) {
          forcedMuted.set(media, !!value);
        }
        nativeDescriptor.set.call(media, value);
        if (name === "src" || name === "srcObject") protect(media);
      }, page),
    });
  });

  function handleEvent(event) {
    const media = event.target;
    if (!(media instanceof HTMLMediaElement)) return;
    if (event.type === "ended") {
      if (continuation && continuation.media === media) continuation = null;
      if (retainedPlayback && retainedPlayback.media === media) retainedPlayback = null;
      endedMedia.add(media);
      protect(media);
    } else if (event.type === "play" || event.type === "playing" || event.type === "volumechange") {
      const reason = reasonFor(media);
      if (!permit(media, true)) silence(media, true, "event:" + event.type);
      else {
        if (inBackground && event.type !== "volumechange") diagnostic("ページ許可", media, "event:" + event.type, reason);
        restoreMuted(media);
      }
    } else {
      protect(media);
    }
    if (!media.paused && !media.muted && media.volume > 0) {
      const id = identity(media);
      const record = describe(media, id);
      records.set(media, record);
      if (continuation && continuation.id === id) continuation = record;
    }
  }

  ["play", "playing", "volumechange", "ended", "timeupdate", "loadedmetadata", "emptied"].forEach(function (name) {
    document.addEventListener(name, handleEvent, true);
  });

  function applyVisibleFallback() {
    if (receivedNativePolicy || performance.now() < initialPolicyDeadline) return;
    visibleFallback = true;
    inBackground = document.visibilityState !== "visible";
    diagnostic("フォールバック", null, "visibility", "初回policy未受信:" + document.visibilityState);
    if (!inBackground) {
      Array.from(forcedMuted.keys()).forEach(restoreMuted);
      pendingInitialPlayback.forEach(function (media) {
        nativePlay.call(media).catch(function () {});
      });
      pendingInitialPlayback.clear();
      resolveReady();
    } else {
      document.querySelectorAll("video, audio").forEach(protect);
    }
  }

  window.setTimeout(applyVisibleFallback, 250);
  document.addEventListener("visibilitychange", applyVisibleFallback, true);

  return {
    identity: identity,
    protect: protect,
    isAllowed: function (media) { return !!media && permit(media, false); },
    isRetained: function (media) { return !!media && matches(retainedPlayback, media); },
    setBlockedListener: function (listener) { onBlocked = listener; },
    setDiagnosticListener: function (listener) { onDiagnostic = listener; },
    connectionLost: function () {
      if (!receivedNativePolicy) {
        applyVisibleFallback();
        return;
      }
      inBackground = true;
      continuation = null;
      commandExpiresAt = 0;
      document.querySelectorAll("video, audio").forEach(function (media) { silence(media, false); });
    },
    state: function () { return {inBackground: inBackground, visibleFallback: visibleFallback, nativePolicy: receivedNativePolicy}; },
    apply: function (message) {
      if (message.type === "playbackPolicy") {
        const firstPolicy = !receivedNativePolicy;
        receivedNativePolicy = true;
        visibleFallback = false;
        const wasInBackground = inBackground;
        inBackground = message.inBackground;
        commandExpiresAt = 0;
        if (!inBackground) {
          continuation = null;
          retainedPlayback = null;
          Array.from(forcedMuted.keys()).forEach(restoreMuted);
          pendingInitialPlayback.forEach(function (media) {
            nativePlay.call(media).catch(function () {});
          });
        } else if (wasInBackground !== true) {
          continuation = null;
          retainedPlayback = null;
          document.querySelectorAll("video, audio").forEach(function (media) {
            if (identity(media) === message.activePlaybackId) {
              retainedPlayback = describe(media, message.activePlaybackId);
            }
            if (!media.paused && !media.ended && !media.muted && media.volume > 0 &&
                identity(media) === message.continuationId) {
              continuation = describe(media, message.continuationId);
              retainedPlayback = continuation;
            }
          });
        }
        document.querySelectorAll("video, audio").forEach(protect);
        pendingInitialPlayback.clear();
        resolveReady();
        if (firstPolicy) diagnostic("初回policy", null, "native", "playbackPolicy受信");
      } else if (message.type === "allowPlayback") {
        commandIssuedAt = performance.now();
        commandExpiresAt = performance.now() + message.windowMs;
      } else if (message.type === "cancelPlaybackCommand") {
        commandExpiresAt = 0;
      } else if (message.type === "stopPlayback") {
        if (continuation) retainedPlayback = continuation;
        continuation = null;
        commandExpiresAt = 0;
        document.querySelectorAll("video, audio").forEach(function (media) { silence(media, false); });
      }
    },
  };
})();
