(function () {
  if (window !== window.top) return;

  const NATIVE_APP = "mediaBridge";
  const MEDIA_SELECTOR = "video, audio";
  const ATTACHED_MEDIA = new WeakSet();
  const STARTED_MEDIA = new WeakSet();
  const KNOWN_MEDIA = new Set();
  let lastPrimaryMedia = null;
  let lastSerializedPayload = "";
  let publishTimer = null;
  let pendingPublishReason = "init";

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
      if (!media.isConnected && media !== lastPrimaryMedia) {
        KNOWN_MEDIA.delete(media);
      }
    });
    if (lastPrimaryMedia) {
      KNOWN_MEDIA.add(lastPrimaryMedia);
    }
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
    const isPlaying =
      playbackState === "playing" ||
      (playbackState !== "paused" && mediaElementPlaying);
    const isActive =
      playbackState === "playing" ||
      (playbackState === "paused" && !!media && hasStarted) ||
      (!!media && (mediaElementPlaying || hasStarted));

    return {
      url: location.href,
      title: metadata.title,
      artist: metadata.artist,
      album: metadata.album,
      currentSrc: currentSrc,
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
    publishTimer = null;
    const payload = readPayload();
    const serializedPayload = JSON.stringify(payload);
    if (serializedPayload === lastSerializedPayload) return;
    lastSerializedPayload = serializedPayload;
    console.log("[MediaBridge] payload=" + serializedPayload);
    browser.runtime.sendNativeMessage(NATIVE_APP, payload).catch(function (error) {
      console.error("[MediaBridge] sendNativeMessage error:", error);
    });
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
    schedulePublish("event:" + event.type);
  }

  function attachMediaListeners(media) {
    if (ATTACHED_MEDIA.has(media)) return;
    ATTACHED_MEDIA.add(media);
    KNOWN_MEDIA.add(media);
    [
      "loadedmetadata",
      "durationchange",
      "play",
      "playing",
      "pause",
      "ended",
      "timeupdate",
      "ratechange",
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
    schedulePublish("mutation");
  });

  attachAllMediaListeners();
  observer.observe(document.documentElement, {
    childList: true,
    subtree: true,
    attributes: true,
    attributeFilter: ["title", "src"],
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

  schedulePublish("init");
})();
