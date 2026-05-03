// navigator.geolocation をモック位置情報で上書きするコンテンツスクリプト
// document_start で実行され、ページのスクリプトより先に geolocation を差し替える。
(function () {
  'use strict';

  // ページコンテキストの navigator.geolocation を保持（モック無効時のフォールバック用）
  const pageWin = window.wrappedJSObject;
  const origGeo = pageWin.navigator.geolocation;

  let configLoaded = false;
  let mockConfig = null;

  // 設定が届く前にページから呼ばれた getCurrentPosition を一時保留するキュー
  let pendingCurrentCalls = [];

  // アクティブな watchPosition セッション
  // key: クライアントに返す合成watchID（常に正の整数）
  // value: { success, error, options, realId: number|undefined, pending: boolean }
  //   pending=true  : config 未到着のため未処理
  //   realId 定義済み: origGeo に転送中（モック無効時）
  //   realId 未定義 : モックウォッチ（origGeo 未使用）
  const activeWatches = new Map();
  let nextWatchId = 1;

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
    } else if (origGeo) {
      origGeo.getCurrentPosition(success, error, options);
    } else if (error) {
      try { error(cloneInto({ code: 1, message: 'Geolocation not supported' }, pageWin)); } catch (_) {}
    }
  }

  // モック geolocation オブジェクトを作成してページコンテキストへ設定
  const mockGeo = cloneInto({}, pageWin);

  mockGeo.getCurrentPosition = exportFunction(function (success, error, options) {
    if (!configLoaded) {
      pendingCurrentCalls.push({ success: success, error: error, options: options });
      return;
    }
    handleGetCurrentPosition(success, error, options);
  }, pageWin);

  mockGeo.watchPosition = exportFunction(function (success, error, options) {
    // 常に合成IDを返すことでモック有効/無効切り替え時にウォッチを追跡可能にする
    const id = nextWatchId++;

    if (!configLoaded) {
      // config 未到着 → pending として積む（config 到着後に処理）
      activeWatches.set(id, { success: success, error: error, options: options, realId: undefined, pending: true });
      return id;
    }

    if (mockConfig && mockConfig.enabled) {
      activeWatches.set(id, { success: success, error: error, options: options, realId: undefined, pending: false });
      try { success(buildPosition(mockConfig.latitude, mockConfig.longitude)); } catch (_) {}
    } else {
      const realId = origGeo ? origGeo.watchPosition(success, error, options) : undefined;
      activeWatches.set(id, { success: success, error: error, options: options, realId: realId, pending: false });
    }
    return id;
  }, pageWin);

  mockGeo.clearWatch = exportFunction(function (id) {
    const entry = activeWatches.get(id);
    if (entry) {
      if (entry.realId !== undefined && origGeo) {
        origGeo.clearWatch(entry.realId);
      }
      activeWatches.delete(id);
    }
  }, pageWin);

  Object.defineProperty(pageWin.navigator, 'geolocation', {
    value: mockGeo,
    writable: false,
    configurable: true,
  });

  // ネイティブとのポートを確立して設定を要求
  const port = browser.runtime.connectNative('mockLocationBridge');
  port.postMessage({ action: 'getConfig' });

  port.onMessage.addListener(function (msg) {
    if (msg.action !== 'config' && msg.action !== 'update') return;

    const wasEnabled = mockConfig ? mockConfig.enabled : null;
    mockConfig = msg;
    configLoaded = true;

    // 設定待ちの getCurrentPosition を処理
    const currCalls = pendingCurrentCalls.splice(0);
    for (let i = 0; i < currCalls.length; i++) {
      handleGetCurrentPosition(currCalls[i].success, currCalls[i].error, currCalls[i].options);
    }

    // 設定待ちの watchPosition を処理（pending=true のエントリ）
    for (const [, entry] of activeWatches) {
      if (!entry.pending) continue;
      entry.pending = false;
      if (mockConfig.enabled) {
        try { entry.success(buildPosition(mockConfig.latitude, mockConfig.longitude)); } catch (_) {}
      } else if (origGeo) {
        entry.realId = origGeo.watchPosition(entry.success, entry.error, entry.options);
      }
    }

    // update 時: モック有効/無効の切り替えによるアクティブウォッチの移行
    if (msg.action === 'update') {
      if (mockConfig.enabled && wasEnabled === false) {
        // 無効→有効: 既存の origGeo ウォッチをキャンセルしてモック座標を配信
        for (const [, entry] of activeWatches) {
          if (entry.pending) continue;
          if (entry.realId !== undefined && origGeo) {
            origGeo.clearWatch(entry.realId);
            entry.realId = undefined;
          }
          try { entry.success(buildPosition(mockConfig.latitude, mockConfig.longitude)); } catch (_) {}
        }
      } else if (!mockConfig.enabled && wasEnabled === true) {
        // 有効→無効: モックウォッチを実 origGeo ウォッチへ切り替え
        for (const [, entry] of activeWatches) {
          if (entry.pending || entry.realId !== undefined) continue;
          if (origGeo) {
            entry.realId = origGeo.watchPosition(entry.success, entry.error, entry.options);
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
