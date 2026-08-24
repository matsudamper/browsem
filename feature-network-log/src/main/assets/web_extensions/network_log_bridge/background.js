// ネットワークログ用のバックグラウンドスクリプト。
// webRequest でページの通信をすべて記録し、ネイティブ側へ送る。
// レスポンス本文はメモリを圧迫するため常時保持せず、
// ネイティブから要求されたときにキャッシュから再取得して返す。
(function () {
  // ネイティブとの双方向ポート。バックグラウンドの connectNative は
  // GeckoView では WebExtension.setMessageDelegate（拡張レベル）に届く
  const port = browser.runtime.connectNative('networkLogBridge');

  // 送信をまとめる間隔。1 リクエストごとに送るとメッセージ数が爆発する
  const FLUSH_INTERVAL_MS = 300;
  // ヘッダは数・長さともに制限する（巨大な Cookie ヘッダ等の転送を避ける）
  const MAX_HEADERS = 40;
  const MAX_HEADER_VALUE_LENGTH = 1024;
  // プレビュー用に取得する本文の上限。これを超えるものは取得しない
  const MAX_BODY_BYTES = 512 * 1024;
  // 未完了リクエストの保持上限。ページを離脱して完了通知が来ない分の掃除に使う
  const MAX_PENDING_REQUESTS = 500;

  // requestId -> 収集途中のエントリ
  const inflight = new Map();
  // 送信待ちのエントリ
  let queued = [];
  let flushTimer = null;

  function flush() {
    flushTimer = null;
    if (queued.length === 0) return;
    const entries = queued;
    queued = [];
    try {
      port.postMessage({ action: 'entries', entries: entries });
    } catch (ignoredPortError) {
      // ポート切断後は送信できないため無視する
    }
  }

  function enqueue(entry) {
    queued.push(entry);
    if (flushTimer === null) {
      flushTimer = setTimeout(flush, FLUSH_INTERVAL_MS);
    }
  }

  function trimHeaders(headers) {
    if (!headers) return [];
    return headers.slice(0, MAX_HEADERS).map(function (header) {
      const value = header.value !== undefined && header.value !== null
        ? String(header.value)
        : '';
      return {
        name: header.name || '',
        value: value.length > MAX_HEADER_VALUE_LENGTH
          ? value.slice(0, MAX_HEADER_VALUE_LENGTH) + '…'
          : value,
      };
    });
  }

  function headerValue(headers, name) {
    if (!headers) return '';
    const lower = name.toLowerCase();
    for (const header of headers) {
      if ((header.name || '').toLowerCase() === lower) {
        return header.value || '';
      }
    }
    return '';
  }

  function ensureEntry(details) {
    let entry = inflight.get(details.requestId);
    if (!entry) {
      entry = {
        requestId: details.requestId,
        tabId: typeof details.tabId === 'number' ? details.tabId : -1,
        url: details.url || '',
        method: details.method || '',
        type: details.type || 'other',
        startedAt: details.timeStamp || Date.now(),
        statusCode: 0,
        mimeType: '',
        contentLength: -1,
        transferred: 0,
        fromCache: false,
        durationMillis: 0,
        error: null,
        requestHeaders: [],
        responseHeaders: [],
      };
      // 完了通知が来ないリクエストが溜まり続けないよう、古いものから捨てる
      if (inflight.size >= MAX_PENDING_REQUESTS) {
        const oldest = inflight.keys().next();
        if (!oldest.done) inflight.delete(oldest.value);
      }
      inflight.set(details.requestId, entry);
    }
    return entry;
  }

  function finish(details, error) {
    const entry = ensureEntry(details);
    inflight.delete(details.requestId);
    const timeStamp = details.timeStamp || Date.now();
    entry.durationMillis = Math.max(0, Math.round(timeStamp - entry.startedAt));
    entry.fromCache = details.fromCache === true;
    if (typeof details.statusCode === 'number' && details.statusCode > 0) {
      entry.statusCode = details.statusCode;
    }
    if (typeof details.responseSize === 'number' && details.responseSize > 0) {
      entry.transferred = details.responseSize;
    }
    if (details.responseHeaders) {
      entry.responseHeaders = trimHeaders(details.responseHeaders);
      entry.mimeType = headerValue(details.responseHeaders, 'content-type');
      const length = parseInt(headerValue(details.responseHeaders, 'content-length'), 10);
      entry.contentLength = isNaN(length) ? entry.contentLength : length;
    }
    entry.error = error;
    entry.completed = true;
    enqueue(entry);
  }

  const allUrls = { urls: ['<all_urls>'] };

  browser.webRequest.onBeforeRequest.addListener(function (details) {
    ensureEntry(details);
  }, allUrls);

  browser.webRequest.onSendHeaders.addListener(function (details) {
    const entry = ensureEntry(details);
    entry.requestHeaders = trimHeaders(details.requestHeaders);
  }, allUrls, ['requestHeaders']);

  browser.webRequest.onHeadersReceived.addListener(function (details) {
    const entry = ensureEntry(details);
    entry.statusCode = details.statusCode || entry.statusCode;
    entry.responseHeaders = trimHeaders(details.responseHeaders);
    entry.mimeType = headerValue(details.responseHeaders, 'content-type');
    const length = parseInt(headerValue(details.responseHeaders, 'content-length'), 10);
    if (!isNaN(length)) entry.contentLength = length;
  }, allUrls, ['responseHeaders']);

  browser.webRequest.onCompleted.addListener(function (details) {
    finish(details, null);
  }, allUrls, ['responseHeaders']);

  browser.webRequest.onErrorOccurred.addListener(function (details) {
    finish(details, details.error || 'error');
  }, allUrls);

  function replyBody(requestId, payload) {
    payload.action = 'body';
    payload.requestId = requestId;
    try {
      port.postMessage(payload);
    } catch (ignoredPortError) {
      // ポート切断後は送信できないため無視する
    }
  }

  // Blob をネイティブへ渡せる base64 文字列にする
  function toBase64(blob) {
    return new Promise(function (resolve, reject) {
      const reader = new FileReader();
      reader.onload = function () {
        const result = reader.result || '';
        const comma = result.indexOf(',');
        resolve(comma >= 0 ? result.slice(comma + 1) : '');
      };
      reader.onerror = function () {
        reject(reader.error);
      };
      reader.readAsDataURL(blob);
    });
  }

  function isTextLike(mimeType) {
    const type = (mimeType || '').toLowerCase();
    if (type.indexOf('text/') === 0) return true;
    return [
      'application/json',
      'application/javascript',
      'application/x-javascript',
      'application/ecmascript',
      'application/xml',
      'application/xhtml+xml',
      'image/svg+xml',
      'application/manifest+json',
    ].some(function (candidate) {
      return type.indexOf(candidate) >= 0;
    });
  }

  // プレビュー用に本文を取得する。
  // 通信時の本文を保持し続けるとメモリを圧迫するため、
  // 要求されたタイミングで HTTP キャッシュを優先して再取得する。
  // 再取得は GET のみを対象とする。GET 以外を再送すると
  // 元のリクエストとは異なる結果になるうえ、サーバ側に副作用を与えうる。
  async function fetchBody(requestId, url, method) {
    if ((method || 'GET').toUpperCase() !== 'GET') {
      replyBody(requestId, { ok: false, reason: 'not_replayable' });
      return;
    }
    try {
      const response = await fetch(url, { credentials: 'include', cache: 'force-cache' });
      const blob = await response.blob();
      const mimeType = blob.type || '';
      if (blob.size > MAX_BODY_BYTES) {
        replyBody(requestId, { ok: false, reason: 'too_large', size: blob.size });
        return;
      }
      if (isTextLike(mimeType)) {
        const text = await blob.text();
        replyBody(requestId, { ok: true, kind: 'text', text: text, mimeType: mimeType, size: blob.size });
      } else {
        const base64 = await toBase64(blob);
        replyBody(requestId, { ok: true, kind: 'binary', base64: base64, mimeType: mimeType, size: blob.size });
      }
    } catch (error) {
      replyBody(requestId, { ok: false, reason: 'fetch_failed', message: String(error) });
    }
  }

  port.onMessage.addListener(function (message) {
    if (!message) return;
    if (message.action === 'fetchBody') {
      fetchBody(message.requestId, message.url, message.method);
    } else if (message.action === 'flush') {
      flush();
    }
  });

  // コンテンツスクリプトからの問い合わせに tabId を返す。
  // ネイティブ側はこれを使ってセッションとタブを対応付ける
  browser.runtime.onMessage.addListener(function (message, sender) {
    if (message && message.action === 'whoami') {
      return Promise.resolve({
        tabId: sender && sender.tab ? sender.tab.id : -1,
      });
    }
    return undefined;
  });
})();
