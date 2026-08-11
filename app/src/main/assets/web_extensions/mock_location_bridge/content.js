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

  // Geolocation API の仕様ではコールバックは必ず非同期に呼ばれる。
  // モック/拒否を同期的に呼ぶと、getCurrentPosition() の「呼び出し直後」に
  // 状態を初期化するページ（例: 取得完了フラグを呼び出し後に立て直すサイト）で
  // コールバックの結果が上書きされ、処理が進まなくなる。
  // そのため実位置情報と同じく必ず次のタスクへ回して呼び出す。
  function dispatchAsync(fn) {
    setTimeout(fn, 0);
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

  // 通知を予約したウォッチがまだ有効かを判定する。
  // 通知は非同期のため、予約から実行までの間に clearWatch() される場合がある。
  // 実 geolocation は解除後に通知しないので、解除済みなら破棄する。
  // watchId が undefined の場合は getCurrentPosition 由来なので常に有効。
  function isWatchActive(watchId) {
    return watchId === undefined || activeWatches.has(watchId);
  }

  // モック座標を成功コールバックへ通知する
  function notifyMockPosition(success, error, watchId) {
    if (!geoConfig) {
      notifyUnsupported(error, watchId);
      return;
    }
    if (!success) return;
    // 非同期化するため、通知時点ではなく呼び出し時点の座標を退避しておく
    const lat = geoConfig.latitude;
    const lng = geoConfig.longitude;
    dispatchAsync(function () {
      if (!isWatchActive(watchId)) return;
      try {
        success(buildPosition(lat, lng));
      } catch (e) {
        if (error) {
          try { error(cloneInto({ code: 2, message: 'mock error' }, pageWin)); } catch (_) {}
        }
      }
    });
  }

  // PERMISSION_DENIED(code: 1) のエラーを通知する
  function notifyDenied(error, watchId) {
    if (!error) return;
    dispatchAsync(function () {
      if (!isWatchActive(watchId)) return;
      try { error(cloneInto({ code: 1, message: 'User denied Geolocation' }, pageWin)); } catch (_) {}
    });
  }

  // 元の geolocation が存在しない環境でのエラーを通知する
  function notifyUnsupported(error, watchId) {
    if (!error) return;
    dispatchAsync(function () {
      if (!isWatchActive(watchId)) return;
      try { error(cloneInto({ code: 2, message: 'Geolocation not supported' }, pageWin)); } catch (_) {}
    });
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
      notifyMockPosition(success, error);
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
      notifyMockPosition(success, error, id);
    } else if (mode === 'deny') {
      activeWatches.set(id, { success: success, error: error, options: options, realId: undefined, pending: false });
      notifyDenied(error, id);
    } else if (origGeo) {
      const realId = origGeo.watchPosition(success, error, options);
      activeWatches.set(id, { success: success, error: error, options: options, realId: realId, pending: false });
    } else {
      activeWatches.set(id, { success: success, error: error, options: options, realId: undefined, pending: false });
      notifyUnsupported(error, id);
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
    for (const [watchId, entry] of activeWatches) {
      if (!entry.pending) continue;
      entry.pending = false;
      if (mode === 'mock') {
        notifyMockPosition(entry.success, entry.error, watchId);
      } else if (mode === 'deny') {
        notifyDenied(entry.error, watchId);
      } else if (origGeo) {
        entry.realId = origGeo.watchPosition(entry.success, entry.error, entry.options);
      } else {
        notifyUnsupported(entry.error, watchId);
      }
    }

    // update 時: モックのまま座標のみ変わった場合、アクティブなモックウォッチへ新座標を配信する
    if (
      msg.action === 'update' && prevConfig !== null && prevMode === mode && mode === 'mock' &&
      (prevConfig.latitude !== msg.latitude || prevConfig.longitude !== msg.longitude)
    ) {
      for (const [watchId, entry] of activeWatches) {
        if (entry.pending || entry.realId !== undefined) continue;
        notifyMockPosition(entry.success, entry.error, watchId);
      }
    }

    // update 時: モード切り替えによるアクティブウォッチの移行
    if (msg.action === 'update' && prevMode !== null && prevMode !== mode) {
      for (const [watchId, entry] of activeWatches) {
        if (entry.pending) continue;
        // real から離れる場合は origGeo ウォッチをキャンセルする
        if (mode !== 'real' && entry.realId !== undefined && origGeo) {
          origGeo.clearWatch(entry.realId);
          entry.realId = undefined;
        }
        if (mode === 'mock') {
          notifyMockPosition(entry.success, entry.error, watchId);
        } else if (mode === 'deny') {
          notifyDenied(entry.error, watchId);
        } else if (entry.realId === undefined && origGeo) {
          // モック/拒否ウォッチを実 origGeo ウォッチへ切り替え
          entry.realId = origGeo.watchPosition(entry.success, entry.error, entry.options);
        } else if (entry.realId === undefined) {
          notifyUnsupported(entry.error, watchId);
        }
      }
    }
  });

  port.onDisconnect.addListener(function () {
    configLoaded = false;
    geoConfig = null;
  });
})();
