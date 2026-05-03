// navigator.geolocation をモック位置情報で上書きするコンテンツスクリプト
// document_start で実行され、ページのスクリプトより先に geolocation を差し替える。
(function () {
  'use strict';

  // ページコンテキストの navigator.geolocation を保持（モック無効時のフォールバック用）
  var pageWin = window.wrappedJSObject;
  var origGeo = pageWin.navigator.geolocation;

  // 設定が届く前にページから呼ばれた getCurrentPosition/watchPosition を一時保留するキュー
  var pendingCalls = [];
  var configLoaded = false;
  var mockConfig = null;

  function buildPosition(lat, lng) {
    return cloneInto(
      {
        coords: {
          latitude: lat,
          longitude: lng,
          accuracy: 10,
          altitude: null,
          altitudeAccuracy: null,
          heading: null,
          speed: null,
        },
        timestamp: Date.now(),
      },
      pageWin
    );
  }

  function handleGetCurrentPosition(success, error, options) {
    if (mockConfig && mockConfig.enabled) {
      try {
        success(buildPosition(mockConfig.latitude, mockConfig.longitude));
      } catch (e) {
        if (error) {
          try { error(cloneInto({ code: 2, message: 'mock error' }, pageWin)); } catch (_) {}
        }
      }
    } else {
      if (origGeo) {
        origGeo.getCurrentPosition(success, error, options);
      } else if (error) {
        try { error(cloneInto({ code: 1, message: 'Geolocation not supported' }, pageWin)); } catch (_) {}
      }
    }
  }

  // モック geolocation オブジェクトを作成してページコンテキストへ設定
  var mockGeo = cloneInto({}, pageWin);

  mockGeo.getCurrentPosition = exportFunction(function (success, error, options) {
    if (!configLoaded) {
      pendingCalls.push({ type: 'current', success: success, error: error, options: options });
      return;
    }
    handleGetCurrentPosition(success, error, options);
  }, pageWin);

  mockGeo.watchPosition = exportFunction(function (success, error, options) {
    if (!configLoaded) {
      var id = -(Math.floor(Math.random() * 1000000) + 1);
      pendingCalls.push({ type: 'watch', success: success, error: error, options: options, id: id });
      return id;
    }
    if (mockConfig && mockConfig.enabled) {
      var watchId = Math.floor(Math.random() * 1000000) + 1;
      try {
        success(buildPosition(mockConfig.latitude, mockConfig.longitude));
      } catch (_) {}
      return watchId;
    } else {
      return origGeo ? origGeo.watchPosition(success, error, options) : -1;
    }
  }, pageWin);

  mockGeo.clearWatch = exportFunction(function (id) {
    // 実 geolocation の clearWatch は正の ID のみ対象
    if (id > 0 && origGeo) {
      origGeo.clearWatch(id);
    }
  }, pageWin);

  Object.defineProperty(pageWin.navigator, 'geolocation', {
    value: mockGeo,
    writable: false,
    configurable: true,
  });

  // ネイティブとのポートを確立して設定を要求
  var port = browser.runtime.connectNative('mockLocationBridge');
  port.postMessage({ action: 'getConfig' });

  port.onMessage.addListener(function (msg) {
    if (msg.action === 'config' || msg.action === 'update') {
      mockConfig = msg;
      configLoaded = true;

      // 保留中のリクエストを処理
      var calls = pendingCalls.splice(0);
      for (var i = 0; i < calls.length; i++) {
        var call = calls[i];
        if (call.type === 'current') {
          handleGetCurrentPosition(call.success, call.error, call.options);
        } else if (call.type === 'watch') {
          if (mockConfig && mockConfig.enabled) {
            try { call.success(buildPosition(mockConfig.latitude, mockConfig.longitude)); } catch (_) {}
          } else if (origGeo) {
            origGeo.watchPosition(call.success, call.error, call.options);
          }
        }
      }
    }
  });

  port.onDisconnect.addListener(function () {
    configLoaded = false;
    mockConfig = null;
  });
})();
