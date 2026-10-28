(function () {
  const NATIVE_APP = "mediaBridge";
  const MEDIA_SELECTOR = "video, audio";
  const ATTACHED_MEDIA = new WeakSet();
  const STARTED_MEDIA = new WeakSet();
  const KNOWN_MEDIA = new Set();
  let lastPrimaryMedia = null;
  let lastSerializedPayload = "";
  let publishTimer = null;
  let pendingPublishReason = "init";
  let port = null;
  let connectAttempts = 0;
  const pendingDiagnostics = new Map();
  let droppedDiagnostics = 0;

  function postDiagnostic(message) {
    const payload = Object.assign({type: "diagnostic"}, message);
    payload.pageUrl = location.href;
    payload.src = payload.src || payload.currentSrc || "";
    Object.keys(payload).forEach(function (key) {
      if (typeof payload[key] === "string") payload[key] = payload[key].slice(0, ["src", "currentSrc", "pageUrl"].includes(key) ? 1024 : 256);
    });
    if (port !== null && backgroundPlaybackPolicy.state().nativePolicy) {
      port.postMessage(payload);
      return;
    }
    const key = [payload.event, payload.origin, payload.reason, payload.identity].join(":");
    if (pendingDiagnostics.has(key)) {
      payload.suppressedBeforeConnect = (pendingDiagnostics.get(key).suppressedBeforeConnect || 0) + 1;
    } else if (pendingDiagnostics.size >= 16) {
      const oldest = pendingDiagnostics.keys().next().value;
      droppedDiagnostics += 1 + (pendingDiagnostics.get(oldest).suppressedBeforeConnect || 0);
      pendingDiagnostics.delete(oldest);
    }
    pendingDiagnostics.set(key, payload);
    if (typeof browser.runtime.sendMessage === "function") {
      browser.runtime.sendMessage({type: "mediaDiagnostic", payload: payload}).then(function (accepted) {
        if (accepted && pendingDiagnostics.get(key) === payload) pendingDiagnostics.delete(key);
      }).catch(function () {});
    }
  }

  backgroundPlaybackPolicy.setDiagnosticListener(postDiagnostic);

  function connectNative() {
    try {
      connectAttempts++;
      const connectedPort = browser.runtime.connectNative(NATIVE_APP);
      port = connectedPort;
      lastSerializedPayload = "";
      if (connectAttempts > 1) postDiagnostic({event: "接続", origin: "port", reason: "再接続", elapsedMs: performance.now(), attempt: connectAttempts});
      connectedPort.onMessage.addListener(function (message) {
        if (port !== connectedPort) return;
        backgroundPlaybackPolicy.apply(message);
        if (message.type === "playbackPolicy") {
          pendingDiagnostics.forEach(function (pending) {
            connectedPort.postMessage(Object.assign({droppedBeforeConnect: droppedDiagnostics}, pending));
            droppedDiagnostics = 0;
          });
          pendingDiagnostics.clear();
        }
        if (message.type === "allowPlayback") {
          connectedPort.postMessage({type: "policyReady", commandId: message.commandId});
        }
        schedulePublish("policy:" + message.type);
      });
      connectedPort.onDisconnect.addListener(function () {
        if (port !== connectedPort) return;
        port = null;
        backgroundPlaybackPolicy.connectionLost();
        window.setTimeout(connectNative, 1000);
      });
    } catch (error) {
      postDiagnostic({event: "接続", origin: "port", reason: "接続失敗", elapsedMs: performance.now(), attempt: connectAttempts});
      console.error("[MediaBridge] connectNative error:", error);
      window.setTimeout(connectNative, 1000);
    }
  }

  function postNativeMessage(message) {
    if (port !== null) port.postMessage(message);
  }
  backgroundPlaybackPolicy.setBlockedListener(function (media, origin) {
    const metadata = readMetadata(media);
    postDiagnostic({
      type: "blocked",
      event: "遮断",
      origin: origin,
      reason: "content scriptで同期拒否",
      stopPath: "content script: muted=true,pause()",
      identity: backgroundPlaybackPolicy.identity(media),
      title: metadata.title,
      currentSrc: media.currentSrc || media.src || "",
    });
  });

  function cleanText(value) {
    if (typeof value !== "string") return "";
    return value.trim();
  }

  function readMediaSessionMetadata() {
    try {
      if (!("mediaSession" in navigator)) return null;
      return navigator.mediaSession.metadata || null;
    } catch (_error) {
      return null;
    }
  }

  function readMediaSessionPlaybackState() {
    try {
      if (!("mediaSession" in navigator)) return "";
      return navigator.mediaSession.playbackState || "";
    } catch (_error) {
      return "";
    }
  }

  function listMediaElements() {
    return Array.from(document.querySelectorAll(MEDIA_SELECTOR));
  }

  function listKnownMediaElements() {
    const mediaElements = listMediaElements();
    mediaElements.forEach((media) => KNOWN_MEDIA.add(media));
    KNOWN_MEDIA.forEach((media) => {
      if (!media.isConnected) {
        KNOWN_MEDIA.delete(media);
        if (media === lastPrimaryMedia) {
          lastPrimaryMedia = null;
        }
      }
    });
    return Array.from(KNOWN_MEDIA);
  }

  function isUsableMedia(media) {
    if (!media) return false;
    return (
      !media.ended ||
      !media.paused ||
      STARTED_MEDIA.has(media) ||
      media.currentTime > 0 ||
      (Number.isFinite(media.duration) && media.duration > 0)
    );
  }

  function pickPrimaryMedia() {
    const mediaElements = listKnownMediaElements();
    const activelyPlayingMedia = mediaElements.find((media) => !media.paused && !media.ended);
    const retainableLastPrimary =
      lastPrimaryMedia &&
      mediaElements.includes(lastPrimaryMedia) &&
      isUsableMedia(lastPrimaryMedia) &&
      !lastPrimaryMedia.ended
        ? lastPrimaryMedia
        : null;
    const picked =
      activelyPlayingMedia ||
      retainableLastPrimary ||
      mediaElements.find((media) => STARTED_MEDIA.has(media) && !media.ended) ||
      mediaElements.find((media) => media.currentTime > 0) ||
      mediaElements.find((media) => Number.isFinite(media.duration) && media.duration > 0) ||
      mediaElements[0] ||
      null;
    if (picked) {
      lastPrimaryMedia = picked;
    }
    return picked;
  }

  function readMetadata(media) {
    const metadata = readMediaSessionMetadata();
    return {
      title:
        cleanText(metadata && metadata.title) ||
        cleanText(media && media.getAttribute("data-media-title")) ||
        cleanText(media && media.getAttribute("title")) ||
        cleanText(document.title),
      artist:
        cleanText(metadata && metadata.artist) ||
        cleanText(media && media.getAttribute("data-media-artist")),
      album:
        cleanText(metadata && metadata.album) ||
        cleanText(media && media.getAttribute("data-media-album")),
    };
  }

  function readPayload() {
    const media = pickPrimaryMedia();
    const metadata = readMetadata(media);
    const playbackState = readMediaSessionPlaybackState();
    const mediaElements = listMediaElements();
    const currentSrc = cleanText(media && (media.currentSrc || media.src));
    let durationMs =
      media && Number.isFinite(media.duration) && media.duration > 0
        ? Math.round(media.duration * 1000)
        : 0;
    let positionMs =
      media && Number.isFinite(media.currentTime) && media.currentTime >= 0
        ? Math.round(media.currentTime * 1000)
        : 0;
    const hasStarted = !!media && (STARTED_MEDIA.has(media) || positionMs > 0);
    const mediaElementPlaying = !!media && !media.paused && !media.ended;
    const mediaMuted = !!media && (media.muted || media.volume === 0);
    const allowed = backgroundPlaybackPolicy.isAllowed(media);
    const isPlaying = mediaElementPlaying && !mediaMuted && allowed;
    const isActive = backgroundPlaybackPolicy.isRetained(media) || (allowed && (
      (playbackState === "playing" && !mediaMuted) ||
      (playbackState === "paused" && !!media && hasStarted && !mediaMuted) ||
      (!!media && (mediaElementPlaying || hasStarted) && !mediaMuted)));

    return {
      url: location.href,
      title: metadata.title,
      artist: metadata.artist,
      album: metadata.album,
      currentSrc: currentSrc,
      playbackId: backgroundPlaybackPolicy.identity(media),
      durationMs: durationMs,
      positionMs: positionMs,
      isPlaying: isPlaying,
      isActive: isActive,
      debugReason: pendingPublishReason,
      debugVisibility: document.visibilityState,
      debugPlaybackState: playbackState,
      debugMediaCount: mediaElements.length,
      debugKnownMediaCount: KNOWN_MEDIA.size,
      debugHasLastPrimaryMedia: !!lastPrimaryMedia,
      debugMediaPaused: !!media && media.paused,
      debugMediaEnded: !!media && media.ended,
      debugMediaReadyState: media ? media.readyState : -1,
      debugCurrentSrc: currentSrc,
    };
  }

  function publishNow() {
    if (publishTimer !== null) window.clearTimeout(publishTimer);
    publishTimer = null;
    const payload = readPayload();
    const serializedPayload = JSON.stringify(payload);
    if (serializedPayload === lastSerializedPayload) return;
    lastSerializedPayload = serializedPayload;
    console.log("[MediaBridge] payload=" + serializedPayload);
    postNativeMessage(payload);
  }

  function schedulePublish(reason) {
    pendingPublishReason = reason || pendingPublishReason;
    if (publishTimer !== null) return;
    publishTimer = window.setTimeout(publishNow, 120);
  }

  function handleMediaEvent(event) {
    const media = event.currentTarget;
    if (
      event.type === "play" ||
      event.type === "playing" ||
      event.type === "timeupdate" ||
      event.type === "seeking" ||
      event.type === "seeked"
    ) {
      STARTED_MEDIA.add(media);
    }
    if (event.type === "emptied") {
      STARTED_MEDIA.delete(media);
    }
    if (["play", "playing", "pause", "ended"].includes(event.type)) {
      pendingPublishReason = "event:" + event.type;
      publishNow();
    } else {
      schedulePublish("event:" + event.type);
    }
  }

  function attachMediaListeners(media) {
    if (ATTACHED_MEDIA.has(media)) return;
    ATTACHED_MEDIA.add(media);
    KNOWN_MEDIA.add(media);
    backgroundPlaybackPolicy.protect(media);
    [
      "loadedmetadata",
      "durationchange",
      "play",
      "playing",
      "pause",
      "ended",
      "timeupdate",
      "ratechange",
      "volumechange",
      "seeking",
      "seeked",
      "emptied",
      "stalled",
    ].forEach(function (eventName) {
      media.addEventListener(eventName, handleMediaEvent);
    });
  }

  function attachAllMediaListeners() {
    listMediaElements().forEach(attachMediaListeners);
  }

  const observer = new MutationObserver(function () {
    attachAllMediaListeners();
    listMediaElements().forEach(backgroundPlaybackPolicy.protect);
    schedulePublish("mutation");
  });

  attachAllMediaListeners();
  observer.observe(document, {
    childList: true,
    subtree: true,
    attributes: true,
    attributeFilter: ["title", "src", "autoplay", "muted"],
  });

  const titleElement = document.querySelector("title");
  if (titleElement) {
    new MutationObserver(function () {
      schedulePublish("titleMutation");
    }).observe(titleElement, {
      childList: true,
      subtree: true,
      characterData: true,
    });
  }

  window.addEventListener("pagehide", function () {
    schedulePublish("pagehide");
  });
  document.addEventListener("visibilitychange", function () {
    schedulePublish("visibility:" + document.visibilityState);
  });
  window.setInterval(function () {
    attachAllMediaListeners();
    schedulePublish("interval");
  }, 1000);

  connectNative();
  schedulePublish("init");
})();
