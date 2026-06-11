// navigator.geolocation をサイトごとの設定（mock/deny/real）に応じて差し替えるコンテンツスクリプト
// document_start で実行され、ページのスクリプトより先に geolocation を差し替える。
(function () {
  'use strict';

  // ページコンテキストの navigator.geolocation を保持（real モード時のフォールバック用）
  const pageWin = window.wrappedJSObject;
  const origGeo = pageWin.navigator.geolocation;

  let configLoaded = false;
  // ネイティブから受信した設定。{ mode: 'mock'|'deny'|'real', latitude, longitude }
  let geoConfig = null;

  // ページが位置情報を要求したことをネイティブへ一度だけ通知するためのフラグ
  let requestNotified = false;

  // 設定が届く前にページから呼ばれた getCurrentPosition を一時保留するキュー
  let pendingCurrentCalls = [];

  // アクティブな watchPosition セッション
  // key: クライアントに返す合成watchID（常に正の整数）
  // value: { success, error, options, realId: number|undefined, pending: boolean }
  //   pending=true  : config 未到着のため未処理
  //   realId 定義済み: origGeo に転送中（real モード時）
  //   realId 未定義 : モック/拒否ウォッチ（origGeo 未使用）
  const activeWatches = new Map();
  let nextWatchId = 1;

  function currentMode() {
    return geoConfig ? geoConfig.mode : 'mock';
  }

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

  // PERMISSION_DENIED(code: 1) のエラーを通知する
  function notifyDenied(error) {
    if (!error) return;
    try { error(cloneInto({ code: 1, message: 'User denied Geolocation' }, pageWin)); } catch (_) {}
  }

  // 元の geolocation が存在しない環境でのエラーを通知する
  function notifyUnsupported(error) {
    if (!error) return;
    try { error(cloneInto({ code: 2, message: 'Geolocation not supported' }, pageWin)); } catch (_) {}
  }

  // ページが位置情報を要求したことをネイティブへ通知する（ページごとに一度だけ）
  function notifyRequested() {
    if (requestNotified) return;
    requestNotified = true;
    try { port.postMessage({ action: 'geolocationRequested' }); } catch (_) {}
  }

  function handleGetCurrentPosition(success, error, options) {
    const mode = currentMode();
    if (mode === 'mock') {
      try {
        success(buildPosition(geoConfig.latitude, geoConfig.longitude));
      } catch (e) {
        if (error) {
          try { error(cloneInto({ code: 2, message: 'mock error' }, pageWin)); } catch (_) {}
        }
      }
    } else if (mode === 'deny') {
      notifyDenied(error);
    } else if (origGeo) {
      origGeo.getCurrentPosition(success, error, options);
    } else {
      notifyUnsupported(error);
    }
  }

  // モック geolocation オブジェクトを作成してページコンテキストへ設定
  const mockGeo = cloneInto({}, pageWin);

  mockGeo.getCurrentPosition = exportFunction(function (success, error, options) {
    notifyRequested();
    if (!configLoaded) {
      pendingCurrentCalls.push({ success: success, error: error, options: options });
      return;
    }
    handleGetCurrentPosition(success, error, options);
  }, pageWin);

  mockGeo.watchPosition = exportFunction(function (success, error, options) {
    notifyRequested();
    // 常に合成IDを返すことでモード切り替え時にウォッチを追跡可能にする
    const id = nextWatchId++;

    if (!configLoaded) {
      // config 未到着 → pending として積む（config 到着後に処理）
      activeWatches.set(id, { success: success, error: error, options: options, realId: undefined, pending: true });
      return id;
    }

    const mode = currentMode();
    if (mode === 'mock') {
      activeWatches.set(id, { success: success, error: error, options: options, realId: undefined, pending: false });
      try { success(buildPosition(geoConfig.latitude, geoConfig.longitude)); } catch (_) {}
    } else if (mode === 'deny') {
      activeWatches.set(id, { success: success, error: error, options: options, realId: undefined, pending: false });
      notifyDenied(error);
    } else if (origGeo) {
      const realId = origGeo.watchPosition(success, error, options);
      activeWatches.set(id, { success: success, error: error, options: options, realId: realId, pending: false });
    } else {
      activeWatches.set(id, { success: success, error: error, options: options, realId: undefined, pending: false });
      notifyUnsupported(error);
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

    const prevConfig = geoConfig;
    const prevMode = prevConfig ? prevConfig.mode : null;
    geoConfig = msg;
    configLoaded = true;
    const mode = currentMode();

    // 設定待ちの getCurrentPosition を処理
    const currCalls = pendingCurrentCalls.splice(0);
    for (let i = 0; i < currCalls.length; i++) {
      handleGetCurrentPosition(currCalls[i].success, currCalls[i].error, currCalls[i].options);
    }

    // 設定待ちの watchPosition を処理（pending=true のエントリ）
    for (const [, entry] of activeWatches) {
      if (!entry.pending) continue;
      entry.pending = false;
      if (mode === 'mock') {
        try { entry.success(buildPosition(geoConfig.latitude, geoConfig.longitude)); } catch (_) {}
      } else if (mode === 'deny') {
        notifyDenied(entry.error);
      } else if (origGeo) {
        entry.realId = origGeo.watchPosition(entry.success, entry.error, entry.options);
      } else {
        notifyUnsupported(entry.error);
      }
    }

    // update 時: モックのまま座標のみ変わった場合、アクティブなモックウォッチへ新座標を配信する
    if (
      msg.action === 'update' && prevConfig !== null && prevMode === mode && mode === 'mock' &&
      (prevConfig.latitude !== msg.latitude || prevConfig.longitude !== msg.longitude)
    ) {
      for (const [, entry] of activeWatches) {
        if (entry.pending || entry.realId !== undefined) continue;
        try { entry.success(buildPosition(geoConfig.latitude, geoConfig.longitude)); } catch (_) {}
      }
    }

    // update 時: モード切り替えによるアクティブウォッチの移行
    if (msg.action === 'update' && prevMode !== null && prevMode !== mode) {
      for (const [, entry] of activeWatches) {
        if (entry.pending) continue;
        // real から離れる場合は origGeo ウォッチをキャンセルする
        if (mode !== 'real' && entry.realId !== undefined && origGeo) {
          origGeo.clearWatch(entry.realId);
          entry.realId = undefined;
        }
        if (mode === 'mock') {
          try { entry.success(buildPosition(geoConfig.latitude, geoConfig.longitude)); } catch (_) {}
        } else if (mode === 'deny') {
          notifyDenied(entry.error);
        } else if (entry.realId === undefined && origGeo) {
          // モック/拒否ウォッチを実 origGeo ウォッチへ切り替え
          entry.realId = origGeo.watchPosition(entry.success, entry.error, entry.options);
        } else if (entry.realId === undefined) {
          notifyUnsupported(entry.error);
        }
      }
    }
  });

  port.onDisconnect.addListener(function () {
    configLoaded = false;
    geoConfig = null;
  });
})();
