(function () {
  if (window !== window.top) return;

  const NATIVE_APP = "mediaBridge";
  const MEDIA_SELECTOR = "video, audio";
  const ATTACHED_MEDIA = new WeakSet();
  const STARTED_MEDIA = new WeakSet();
  let lastSerializedPayload = "";
  let publishTimer = null;

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

  function listMediaElements() {
    return Array.from(document.querySelectorAll(MEDIA_SELECTOR));
  }

  function pickPrimaryMedia() {
    const mediaElements = listMediaElements();
    return (
      mediaElements.find((media) => !media.paused && !media.ended) ||
      mediaElements.find((media) => STARTED_MEDIA.has(media) && !media.ended) ||
      mediaElements.find((media) => media.currentTime > 0) ||
      mediaElements.find((media) => Number.isFinite(media.duration) && media.duration > 0) ||
      mediaElements[0] ||
      null
    );
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
    const durationMs =
      media && Number.isFinite(media.duration) && media.duration > 0
        ? Math.round(media.duration * 1000)
        : 0;
    const positionMs =
      media && Number.isFinite(media.currentTime) && media.currentTime >= 0
        ? Math.round(media.currentTime * 1000)
        : 0;
    const hasStarted = !!media && (STARTED_MEDIA.has(media) || positionMs > 0);
    const isPlaying = !!media && !media.paused && !media.ended;
    const isActive = !!media && (isPlaying || hasStarted);

    return {
      url: location.href,
      title: metadata.title,
      artist: metadata.artist,
      album: metadata.album,
      durationMs: durationMs,
      positionMs: positionMs,
      isPlaying: isPlaying,
      isActive: isActive,
    };
  }

  function publishNow() {
    publishTimer = null;
    const payload = readPayload();
    const serializedPayload = JSON.stringify(payload);
    if (serializedPayload === lastSerializedPayload) return;
    lastSerializedPayload = serializedPayload;
    browser.runtime.sendNativeMessage(NATIVE_APP, payload).catch(function (error) {
      console.error("[MediaBridge] sendNativeMessage error:", error);
    });
  }

  function schedulePublish() {
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
    schedulePublish();
  }

  function attachMediaListeners(media) {
    if (ATTACHED_MEDIA.has(media)) return;
    ATTACHED_MEDIA.add(media);
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
    schedulePublish();
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
    new MutationObserver(schedulePublish).observe(titleElement, {
      childList: true,
      subtree: true,
      characterData: true,
    });
  }

  window.addEventListener("pagehide", schedulePublish);
  document.addEventListener("visibilitychange", schedulePublish);
  window.setInterval(function () {
    attachAllMediaListeners();
    schedulePublish();
  }, 1000);

  schedulePublish();
})();
