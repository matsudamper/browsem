// chrome.cast API シムをページコンテキストに注入し、
// ネイティブ Cast SDK とのブリッジを提供するコンテンツスクリプト。
// YouTube 等が chrome.cast API を検出してキャストボタンを表示できるようにする。
(function () {
  if (window !== window.top) return;

  // ページコンテキストに注入するスクリプト
  var shimCode = "(" + function () {
    // chrome 名前空間の確保
    if (!window.chrome) window.chrome = {};
    if (!window.chrome.cast) window.chrome.cast = {};
    if (!window.chrome.cast.media) window.chrome.cast.media = {};

    // 列挙型定数
    chrome.cast.AutoJoinPolicy = {
      TAB_AND_ORIGIN_SCOPED: "tab_and_origin_scoped",
      ORIGIN_SCOPED: "origin_scoped",
      PAGE_SCOPED: "page_scoped"
    };
    chrome.cast.DefaultActionPolicy = {
      CREATE_SESSION: "create_session",
      CAST_THIS_TAB: "cast_this_tab"
    };
    chrome.cast.ReceiverAvailability = {
      AVAILABLE: "available",
      UNAVAILABLE: "unavailable"
    };
    chrome.cast.SessionStatus = {
      CONNECTED: "connected",
      DISCONNECTED: "disconnected",
      STOPPED: "stopped"
    };
    chrome.cast.ReceiverType = {
      CAST: "cast"
    };
    chrome.cast.Capability = {
      VIDEO_OUT: "video_out",
      AUDIO_OUT: "audio_out"
    };
    chrome.cast.ErrorCode = {
      CANCEL: "cancel",
      TIMEOUT: "timeout",
      API_NOT_INITIALIZED: "api_not_initialized",
      INVALID_PARAMETER: "invalid_parameter",
      EXTENSION_NOT_COMPATIBLE: "extension_not_compatible",
      EXTENSION_MISSING: "extension_missing",
      RECEIVER_UNAVAILABLE: "receiver_unavailable",
      SESSION_ERROR: "session_error",
      CHANNEL_ERROR: "channel_error",
      LOAD_MEDIA_FAILED: "load_media_failed"
    };
    chrome.cast.media.StreamType = {
      BUFFERED: "BUFFERED",
      LIVE: "LIVE",
      OTHER: "OTHER"
    };
    chrome.cast.media.PlayerState = {
      IDLE: "IDLE",
      PLAYING: "PLAYING",
      PAUSED: "PAUSED",
      BUFFERING: "BUFFERING"
    };
    chrome.cast.media.MetadataType = {
      GENERIC: 0, MOVIE: 1, TV_SHOW: 2, MUSIC_TRACK: 3, PHOTO: 4
    };

    // エラークラス
    chrome.cast.Error = function (code, description, details) {
      this.code = code;
      this.description = description || "";
      this.details = details || null;
    };

    // Receiver
    chrome.cast.Receiver = function (label, friendlyName, capabilities) {
      this.label = label || "";
      this.friendlyName = friendlyName || "";
      this.capabilities = capabilities || [];
      this.receiverType = chrome.cast.ReceiverType.CAST;
      this.volume = null;
    };

    // Volume
    chrome.cast.Volume = function (level, muted) {
      this.level = level;
      this.muted = muted;
    };

    // SessionRequest
    chrome.cast.SessionRequest = function (appId, capabilities) {
      this.appId = appId;
      this.capabilities = capabilities || [chrome.cast.Capability.VIDEO_OUT];
    };

    // ApiConfig
    chrome.cast.ApiConfig = function (sessionRequest, sessionListener, receiverListener, autoJoinPolicy) {
      this.sessionRequest = sessionRequest;
      this.sessionListener = sessionListener || null;
      this.receiverListener = receiverListener || null;
      this.autoJoinPolicy = autoJoinPolicy || chrome.cast.AutoJoinPolicy.TAB_AND_ORIGIN_SCOPED;
    };

    // Image
    chrome.cast.Image = function (url) {
      this.url = url;
      this.height = null;
      this.width = null;
    };

    // メディアメタデータ
    chrome.cast.media.GenericMediaMetadata = function () {
      this.metadataType = chrome.cast.media.MetadataType.GENERIC;
      this.title = null;
      this.subtitle = null;
      this.images = [];
    };

    // MediaInfo
    chrome.cast.media.MediaInfo = function (contentId, contentType) {
      this.contentId = contentId;
      this.contentType = contentType;
      this.metadata = null;
      this.duration = null;
      this.streamType = chrome.cast.media.StreamType.BUFFERED;
      this.customData = null;
    };

    // LoadRequest
    chrome.cast.media.LoadRequest = function (mediaInfo) {
      this.media = mediaInfo;
      this.autoplay = true;
      this.currentTime = null;
      this.customData = null;
    };

    // SeekRequest
    chrome.cast.media.SeekRequest = function () {
      this.currentTime = null;
      this.resumeState = null;
    };

    // Media オブジェクト
    chrome.cast.media.Media = function (sessionId, mediaSessionId) {
      this.sessionId = sessionId;
      this.mediaSessionId = mediaSessionId;
      this.media = null;
      this.playbackRate = 1;
      this.playerState = chrome.cast.media.PlayerState.IDLE;
      this.volume = new chrome.cast.Volume(1, false);
      this.currentTime = 0;
      this._updateListeners = [];
    };
    chrome.cast.media.Media.prototype.addUpdateListener = function (listener) {
      this._updateListeners.push(listener);
    };
    chrome.cast.media.Media.prototype.removeUpdateListener = function (listener) {
      var idx = this._updateListeners.indexOf(listener);
      if (idx >= 0) this._updateListeners.splice(idx, 1);
    };
    chrome.cast.media.Media.prototype.play = function (req, onSuccess, onError) {
      window.postMessage({ __castBridge: true, action: "mediaPlay" }, "*");
      if (onSuccess) setTimeout(onSuccess, 0);
    };
    chrome.cast.media.Media.prototype.pause = function (req, onSuccess, onError) {
      window.postMessage({ __castBridge: true, action: "mediaPause" }, "*");
      if (onSuccess) setTimeout(onSuccess, 0);
    };
    chrome.cast.media.Media.prototype.seek = function (seekReq, onSuccess, onError) {
      window.postMessage({ __castBridge: true, action: "mediaSeek", currentTime: seekReq.currentTime }, "*");
      if (onSuccess) setTimeout(onSuccess, 0);
    };
    chrome.cast.media.Media.prototype.stop = function (req, onSuccess, onError) {
      window.postMessage({ __castBridge: true, action: "mediaStop" }, "*");
      if (onSuccess) setTimeout(onSuccess, 0);
    };
    chrome.cast.media.Media.prototype.getEstimatedTime = function () {
      return this.currentTime;
    };

    // Session オブジェクト
    chrome.cast.Session = function (sessionId, appId, displayName, appImages, receiver) {
      this.sessionId = sessionId || "";
      this.appId = appId || "";
      this.displayName = displayName || "";
      this.appImages = appImages || [];
      this.receiver = receiver || new chrome.cast.Receiver("", "", []);
      this.media = [];
      this.status = chrome.cast.SessionStatus.CONNECTED;
      this.transportId = sessionId || "";
      this._updateListeners = [];
      this._messageListeners = {};
    };
    chrome.cast.Session.prototype.addUpdateListener = function (listener) {
      this._updateListeners.push(listener);
    };
    chrome.cast.Session.prototype.removeUpdateListener = function (listener) {
      var idx = this._updateListeners.indexOf(listener);
      if (idx >= 0) this._updateListeners.splice(idx, 1);
    };
    chrome.cast.Session.prototype.addMessageListener = function (namespace, listener) {
      if (!this._messageListeners[namespace]) {
        this._messageListeners[namespace] = [];
      }
      this._messageListeners[namespace].push(listener);
      window.postMessage({ __castBridge: true, action: "addMessageListener", namespace: namespace }, "*");
    };
    chrome.cast.Session.prototype.removeMessageListener = function (namespace, listener) {
      if (this._messageListeners[namespace]) {
        var idx = this._messageListeners[namespace].indexOf(listener);
        if (idx >= 0) this._messageListeners[namespace].splice(idx, 1);
      }
    };
    chrome.cast.Session.prototype.sendMessage = function (namespace, message, onSuccess, onError) {
      var msgStr = typeof message === "string" ? message : JSON.stringify(message);
      window.postMessage({
        __castBridge: true,
        action: "sendMessage",
        namespace: namespace,
        message: msgStr
      }, "*");
      if (onSuccess) setTimeout(onSuccess, 0);
    };
    chrome.cast.Session.prototype.stop = function (onSuccess, onError) {
      window.postMessage({ __castBridge: true, action: "stopSession" }, "*");
      if (onSuccess) setTimeout(onSuccess, 0);
    };
    chrome.cast.Session.prototype.leave = function (onSuccess, onError) {
      this.stop(onSuccess, onError);
    };
    chrome.cast.Session.prototype.loadMedia = function (loadRequest, onSuccess, onError) {
      var mediaInfo = loadRequest.media;
      window.postMessage({
        __castBridge: true,
        action: "loadMedia",
        contentId: mediaInfo.contentId,
        contentType: mediaInfo.contentType || "video/mp4",
        autoplay: loadRequest.autoplay !== false,
        currentTime: loadRequest.currentTime || 0
      }, "*");
      var media = new chrome.cast.media.Media(this.sessionId, 1);
      media.media = mediaInfo;
      this.media = [media];
      if (onSuccess) setTimeout(function () { onSuccess(media); }, 0);
    };
    chrome.cast.Session.prototype.addMediaListener = function () {};
    chrome.cast.Session.prototype.removeMediaListener = function () {};
    chrome.cast.Session.prototype.setReceiverVolumeLevel = function (level, onSuccess) {
      if (onSuccess) setTimeout(onSuccess, 0);
    };
    chrome.cast.Session.prototype.setReceiverMuted = function (muted, onSuccess) {
      if (onSuccess) setTimeout(onSuccess, 0);
    };

    // 内部状態
    var apiConfig = null;
    var currentSession = null;
    var requestSessionPending = false;

    // メインAPI
    chrome.cast.initialize = function (config, onSuccess, onError) {
      apiConfig = config;
      chrome.cast.isAvailable = true;
      if (onSuccess) setTimeout(onSuccess, 0);
      // receiverListener に AVAILABLE を通知してキャストボタンを表示させる
      if (config && config.receiverListener) {
        setTimeout(function () {
          config.receiverListener(chrome.cast.ReceiverAvailability.AVAILABLE);
        }, 100);
      }
    };

    chrome.cast.requestSession = function (onSuccess, onError, sessionRequest) {
      if (requestSessionPending) {
        if (onError) onError(new chrome.cast.Error(chrome.cast.ErrorCode.CANCEL));
        return;
      }
      requestSessionPending = true;
      var appId = (sessionRequest && sessionRequest.appId) ||
        (apiConfig && apiConfig.sessionRequest && apiConfig.sessionRequest.appId) || "";
      window.postMessage({
        __castBridge: true,
        action: "requestSession",
        appId: appId
      }, "*");

      // ネイティブからの結果を待つ
      var handler = function (event) {
        var data = event.data;
        if (!data || !data.__castBridge || data.action !== "sessionResult") return;
        window.removeEventListener("message", handler);
        requestSessionPending = false;
        if (data.success) {
          var receiver = new chrome.cast.Receiver(
            data.deviceName || "", data.deviceName || "",
            [chrome.cast.Capability.VIDEO_OUT]
          );
          var session = new chrome.cast.Session(
            data.sessionId || "", appId, data.deviceName || "", [], receiver
          );
          currentSession = session;
          if (onSuccess) onSuccess(session);
          if (apiConfig && apiConfig.sessionListener) {
            apiConfig.sessionListener(session);
          }
        } else {
          if (onError) onError(new chrome.cast.Error(
            data.errorCode || chrome.cast.ErrorCode.CANCEL,
            data.errorMessage || ""
          ));
        }
      };
      window.addEventListener("message", handler);
    };

    chrome.cast.setCustomReceivers = function () {};
    chrome.cast.unescape = function (str) { return str; };
    chrome.cast.isAvailable = true;
    chrome.cast.VERSION = [1, 2, 0, 0];

    // ネイティブからのメッセージ受信（セッション状態変更等）
    window.addEventListener("message", function (event) {
      var data = event.data;
      if (!data || !data.__castBridge) return;
      if (data.action === "messageReceived" && currentSession) {
        var listeners = currentSession._messageListeners[data.namespace];
        if (listeners) {
          listeners.forEach(function (listener) {
            try { listener(data.namespace, data.message); } catch (e) {}
          });
        }
      } else if (data.action === "sessionEnded") {
        if (currentSession) {
          currentSession.status = chrome.cast.SessionStatus.STOPPED;
          currentSession._updateListeners.forEach(function (listener) {
            try { listener(false); } catch (e) {}
          });
          currentSession = null;
        }
        requestSessionPending = false;
      }
    });

    // __onGCastApiAvailable のインターセプト
    // cast_sender.js がロードされた場合に false で呼ばれる可能性があるため、
    // 常に true で呼ぶようにインターセプトする
    var existingCallback = window.__onGCastApiAvailable;
    var storedCallbacks = [];
    if (typeof existingCallback === "function") {
      storedCallbacks.push(existingCallback);
    }

    Object.defineProperty(window, "__onGCastApiAvailable", {
      get: function () {
        // cast_sender.js がこの関数を呼ぶ時、常に true を渡すラッパーを返す
        return function () {
          storedCallbacks.forEach(function (cb) {
            try { cb(true); } catch (e) {}
          });
        };
      },
      set: function (fn) {
        if (typeof fn === "function") {
          storedCallbacks.push(fn);
          // 設定直後に true で呼び出す
          setTimeout(function () { fn(true); }, 0);
        }
      },
      configurable: true,
      enumerable: true
    });
  } + ")();";

  // ページコンテキストにスクリプトを注入
  var script = document.createElement("script");
  script.textContent = shimCode;
  (document.head || document.documentElement).appendChild(script);
  script.remove();

  // ネイティブ通信用のポート接続
  var port = browser.runtime.connectNative("castBridge");

  // ページからの postMessage アクションをネイティブポートに転送
  window.addEventListener("message", function (event) {
    if (event.source !== window) return;
    var data = event.data;
    if (!data || !data.__castBridge) return;
    // ブリッジマーカーを除去してネイティブに送信
    var msg = {};
    for (var key in data) {
      if (key !== "__castBridge") msg[key] = data[key];
    }
    port.postMessage(msg);
  });

  // ネイティブポートからの応答をページに転送
  port.onMessage.addListener(function (msg) {
    msg.__castBridge = true;
    window.postMessage(msg, "*");
  });
})();
