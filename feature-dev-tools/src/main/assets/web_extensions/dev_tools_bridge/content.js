// 開発者ツール用のコンテンツスクリプト。
// フォーカス中の入力要素の通知、ページの console 出力の転送、JavaScript の実行を担当する。
(function () {
  // トップフレームのみで実行する
  if (window !== window.top) return;

  // ページ側の console 出力を保持する上限。開発者ツールを開く前の出力も見せるため先読みで貯める
  const MAX_BUFFERED_LOGS = 500;
  const MAX_MESSAGE_LENGTH = 4096;
  const MAX_ARG_COUNT = 20;

  // 値の整形はページの操作を止めないよう、深さ・件数・走査ノード数で頭打ちにする
  const MAX_FORMAT_DEPTH = 3;
  const MAX_FORMAT_ENTRIES = 50;
  const MAX_FORMAT_NODES = 500;

  // ネイティブ側の受け口が用意される前に接続すると即座に切断されるため、間隔を伸ばしつつ繋ぎ直す
  const RECONNECT_DELAY_MS = 500;
  const MAX_RECONNECT_DELAY_MS = 30000;

  // ページ遷移とポートの繋ぎ直しをネイティブ側が区別するための、この文書での識別子
  const documentId = Date.now().toString(36) + '-' + Math.random().toString(36).slice(2);

  let port = null;
  let reconnectCount = 0;

  function postMessage(message) {
    if (port === null) return;
    try {
      port.postMessage(message);
    } catch (error) {
      // 切断直後の送信は例外になるが、再接続時に送り直されるため無視してよい
    }
  }

  // ---- フォーカス中の入力要素 ----

  // input / textarea / contenteditable など、テキスト入力に類する要素のみを対象とする
  function isInputLike(el) {
    if (!el || el === document.body || el === document.documentElement) return false;
    const tag = (el.tagName || '').toUpperCase();
    if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') return true;
    if (el.isContentEditable) return true;
    return false;
  }

  // 現在フォーカスされている入力要素の情報を送信する。
  // フォーカスが入力要素でない場合は focused=false を送る。
  function reportFocusedInput() {
    const el = document.activeElement;
    if (isInputLike(el)) {
      postMessage({
        action: 'focusedInput',
        focused: true,
        id: el.id || '',
        tagName: (el.tagName || '').toLowerCase(),
        type: (el.getAttribute && el.getAttribute('type')) || '',
        name: el.name || '',
      });
    } else {
      postMessage({ action: 'focusedInput', focused: false });
    }
  }

  // ---- 値の文字列化 ----

  function truncate(text) {
    if (text.length <= MAX_MESSAGE_LENGTH) return text;
    return text.slice(0, MAX_MESSAGE_LENGTH) + '…';
  }

  // ページが定義した getter はログ出力のために実行しない。
  // DOM の属性のような組み込みのアクセサは prototype 側にあるため、自身の記述子だけを見る
  function readProperty(value, key) {
    const descriptor = Object.getOwnPropertyDescriptor(value, key);
    if (descriptor && !('value' in descriptor)) return undefined;
    return value[key];
  }

  // Xray 越しのページ側オブジェクトは instanceof が使えないため、形で判定する
  function isErrorLike(value) {
    return typeof readProperty(value, 'message') === 'string' &&
      typeof readProperty(value, 'name') === 'string' &&
      ('stack' in value);
  }

  function isElementLike(value) {
    return readProperty(value, 'nodeType') === 1 &&
      typeof readProperty(value, 'tagName') === 'string';
  }

  function isArrayLike(value) {
    if (Array.isArray(value)) return true;
    return typeof readProperty(value, 'length') === 'number' &&
      typeof readProperty(value, 'splice') === 'function';
  }

  function describeElement(el) {
    const elementId = readProperty(el, 'id');
    const id = elementId ? '#' + elementId : '';
    const rawClassName = readProperty(el, 'className');
    const className = typeof rawClassName === 'string' && rawClassName
      ? '.' + rawClassName.trim().split(/\s+/).join('.')
      : '';
    return '<' + readProperty(el, 'tagName').toLowerCase() + id + className + '>';
  }

  // 1 回の整形で走査できるノード数。深い・巨大なオブジェクトで走査が長引かないようにする
  let formatNodeBudget = MAX_FORMAT_NODES;

  function formatProperty(value, key, depth) {
    try {
      // getter を実行するとログ出力だけでページの状態が変わりうるため、値を取らずに示す
      const descriptor = Object.getOwnPropertyDescriptor(value, key);
      if (descriptor && typeof descriptor.get === 'function') return '[Getter]';
      return formatValue(value[key], depth + 1);
    } catch (error) {
      return '[取得できません]';
    }
  }

  function formatEntries(value, keys, omittedLabel, depth, open, close) {
    const parts = keys.map(function (key) {
      const formatted = formatProperty(value, key, depth);
      return open === '[' ? formatted : key + ': ' + formatted;
    });
    if (omittedLabel !== null) {
      parts.push(omittedLabel);
    }
    return open + parts.join(', ') + close;
  }

  // キーの配列を作る時点で頭打ちにする。全件を配列化すると巨大なオブジェクトで停止しうる
  function ownKeysUpToLimit(value) {
    const keys = [];
    let hasMore = false;
    for (const key in value) {
      if (!Object.prototype.hasOwnProperty.call(value, key)) continue;
      if (keys.length >= MAX_FORMAT_ENTRIES) {
        hasMore = true;
        break;
      }
      keys.push(key);
    }
    return { keys: keys, hasMore: hasMore };
  }

  function formatObjectLike(value, depth) {
    if (depth >= MAX_FORMAT_DEPTH) return isArrayLike(value) ? '[…]' : '{…}';
    if (isArrayLike(value)) {
      // length が極端に大きい配列でも走査が伸びないよう、上限までの添字だけを作る
      const length = readProperty(value, 'length');
      const shownCount = Math.min(length, MAX_FORMAT_ENTRIES);
      const indexes = [];
      for (let index = 0; index < shownCount; index += 1) {
        indexes.push(index);
      }
      const omitted = length > shownCount ? '…他 ' + (length - shownCount) + ' 件' : null;
      return formatEntries(value, indexes, omitted, depth, '[', ']');
    }
    const own = ownKeysUpToLimit(value);
    return formatEntries(value, own.keys, own.hasMore ? '…他' : null, depth, '{', '}');
  }

  function formatErrorLike(value) {
    const stack = readProperty(value, 'stack');
    if (typeof stack === 'string' && stack) return stack;
    return readProperty(value, 'name') + ': ' + readProperty(value, 'message');
  }

  function formatValue(value, depth) {
    // 予算はプリミティブも含めて消費する。深さと件数の上限だけでは総量を抑えられない
    if (formatNodeBudget <= 0) return '…';
    formatNodeBudget -= 1;
    if (value === undefined) return 'undefined';
    if (value === null) return 'null';
    const type = typeof value;
    if (type === 'string') return depth > 0 ? truncate(JSON.stringify(value)) : truncate(value);
    if (type === 'number' || type === 'boolean' || type === 'bigint' || type === 'symbol') {
      return String(value);
    }
    if (type === 'function') return 'function ' + (value.name || '(anonymous)') + '()';
    try {
      if (isErrorLike(value)) return formatErrorLike(value);
      if (isElementLike(value)) return describeElement(value);
      return formatObjectLike(value, depth);
    } catch (error) {
      // 循環参照や Xray 越しに触れないオブジェクトでは String() にフォールバックする
    }
    try {
      return String(value);
    } catch (error) {
      return '[object]';
    }
  }

  // console 出力 1 件・実行結果 1 件のような、独立した整形の起点。予算はここで戻す
  function formatForEntry(value) {
    formatNodeBudget = MAX_FORMAT_NODES;
    return truncate(formatValue(value, 0));
  }

  function formatArgs(args) {
    const values = Array.prototype.slice.call(args, 0, MAX_ARG_COUNT);
    // 予算は 1 回の出力全体で共有し、引数ごとに切り詰めてから連結する
    formatNodeBudget = MAX_FORMAT_NODES;
    return truncate(values.map(function (value) {
      return truncate(formatValue(value, 0));
    }).join(' '));
  }

  // ---- console 出力の収集 ----

  // ネイティブ側は取得済みの通番までを知っているため、再送時の重複を避けられる
  let nextSeq = 1;
  const bufferedLogs = [];
  let forwarding = false;

  function addLog(level, message) {
    const log = {
      action: 'consoleLog',
      documentId: documentId,
      seq: nextSeq,
      level: level,
      message: message,
      url: location.href,
      timestamp: Date.now(),
    };
    nextSeq += 1;
    bufferedLogs.push(log);
    if (bufferedLogs.length > MAX_BUFFERED_LOGS) {
      bufferedLogs.shift();
    }
    if (forwarding) {
      postMessage(log);
    }
  }

  function forwardBufferedLogs(sinceSeq) {
    bufferedLogs.forEach(function (log) {
      if (log.seq > sinceSeq) {
        postMessage(log);
      }
    });
  }

  // ページの console はページ側のコンテキストにあるため、exportFunction で差し替える。
  // 開発者ツールを開く前の出力も見せたいので、常に読み込み直後からフックしておく。
  const pageWindow = window.wrappedJSObject;

  // 転送の失敗を握り潰すと原因が分からなくなるため、最初の 1 件だけログに残す
  let hookFailureReported = false;

  function reportHookFailure(error) {
    if (hookFailureReported) return;
    hookFailureReported = true;
    addLog('error', truncate('console フックの転送に失敗しました: ' + String(error)));
  }

  function installConsoleHook() {
    const pageConsole = pageWindow.console;
    if (!pageConsole) return;
    ['log', 'info', 'warn', 'error', 'debug'].forEach(function (level) {
      const original = pageConsole[level];
      if (typeof original !== 'function') return;
      pageConsole[level] = exportFunction(function () {
        try {
          addLog(level, formatArgs(arguments));
        } catch (error) {
          reportHookFailure(error);
        }
        try {
          return original.apply(pageConsole, arguments);
        } catch (error) {
          // ページ側の呼び出しが失敗しても差し替えが原因で例外を投げない
          return undefined;
        }
      }, pageWindow);
    });
  }

  function resourceErrorMessage(event) {
    const target = event.target;
    const url = (target && (target.src || target.href)) || '';
    const tag = target && target.tagName ? target.tagName.toLowerCase() : 'リソース';
    return 'リソースの読み込みに失敗しました: ' + tag + ' ' + url;
  }

  function installErrorHooks() {
    window.addEventListener('error', function (event) {
      // キャプチャフェーズには画像や script の読み込み失敗も届く。
      // これらは message を持たない Event のため、内容が空の行にならないよう分けて扱う
      if (typeof event.message !== 'string') {
        addLog('error', truncate(resourceErrorMessage(event)));
        return;
      }
      const at = event.filename ? ' (' + event.filename + ':' + event.lineno + ')' : '';
      const detail = event.error ? formatForEntry(event.error) : event.message;
      addLog('error', truncate(detail + at));
    }, true);
    window.addEventListener('unhandledrejection', function (event) {
      addLog('error', truncate('Uncaught (in promise) ' + formatForEntry(event.reason)));
    }, true);
  }

  // ---- スクリプト実行 ----

  // 実行するコード自身の EvalError と区別するため、副作用のない式で eval の可否を確かめる
  function isPageEvalAllowed() {
    try {
      pageWindow.eval('0');
      return true;
    } catch (error) {
      return false;
    }
  }

  function evaluate(code) {
    if (isPageEvalAllowed()) return pageWindow.eval(code);
    // CSP でページ側の eval が禁止されている場合はコンテンツスクリプト側で実行する。
    // ページの変数は参照できないが、DOM 操作は同じように行える。
    // 直接 eval だとこのスクリプトのローカル変数が見えてしまうため、間接 eval で行う
    return (0, eval)(code);
  }

  function isThenable(value) {
    return value !== null && typeof value === 'object' && typeof value.then === 'function';
  }

  function postExecuteSuccess(requestId, value) {
    postMessage({
      action: 'executeResult',
      requestId: requestId,
      success: true,
      result: formatForEntry(value),
    });
  }

  function postExecuteFailure(requestId, error) {
    postMessage({
      action: 'executeResult',
      requestId: requestId,
      success: false,
      error: formatForEntry(error),
    });
  }

  function executeScript(requestId, code) {
    try {
      const result = evaluate(code);
      if (isThenable(result)) {
        result.then(
          function (value) {
            postExecuteSuccess(requestId, value);
          },
          function (error) {
            postExecuteFailure(requestId, error);
          },
        );
        return;
      }
      postExecuteSuccess(requestId, result);
    } catch (error) {
      postExecuteFailure(requestId, error);
    }
  }

  // ---- ネイティブとの接続 ----

  function onNativeMessage(msg) {
    if (!msg) return;
    // 応答があるならネイティブ側の受け口は用意されているため、繋ぎ直しの回数を戻す
    reconnectCount = 0;
    if (msg.action === 'query') {
      reportFocusedInput();
      return;
    }
    if (msg.action === 'setConsoleForwarding') {
      forwarding = !!msg.enabled;
      if (forwarding) {
        forwardBufferedLogs(msg.sinceSeq || 0);
      }
      return;
    }
    if (msg.action === 'execute') {
      executeScript(msg.requestId || '', msg.code || '');
    }
  }

  function connect() {
    port = browser.runtime.connectNative('devToolsBridge');
    port.onMessage.addListener(onNativeMessage);
    port.onDisconnect.addListener(function () {
      port = null;
      forwarding = false;
      // ネイティブ側がセッションを登録する前に接続すると、delegate 不在で即切断される。
      // 事前読み込みしたセッションは登録まで数分空くことがあるため、
      // 回数では打ち切らず、間隔を伸ばしながら文書が生きている間は繋ぎ直す
      const delay = Math.min(RECONNECT_DELAY_MS * Math.pow(2, reconnectCount), MAX_RECONNECT_DELAY_MS);
      reconnectCount += 1;
      setTimeout(connect, delay);
    });
    // 接続直後に現在の状態を送る。ネイティブ側はこの識別子でページ遷移と再接続を見分ける
    postMessage({ action: 'hello', documentId: documentId });
    reportFocusedInput();
  }

  installConsoleHook();
  installErrorHooks();

  // フォーカスの移動を監視して都度通知する
  document.addEventListener('focusin', reportFocusedInput, true);
  document.addEventListener('focusout', function () {
    // focusout 直後に activeElement が body に戻るため、次のフレームで再評価する
    setTimeout(reportFocusedInput, 0);
  }, true);

  connect();
})();
