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

  // ネイティブアプリとの双方向ポートを確立する
  const port = browser.runtime.connectNative('devToolsBridge');

  // ポート切断後に postMessage を呼ぶと例外になるため、切断状態を追跡する
  let disconnected = false;
  port.onDisconnect.addListener(function () {
    disconnected = true;
  });

  function postMessage(message) {
    if (disconnected) return;
    port.postMessage(message);
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

  // Xray 越しのページ側オブジェクトは instanceof が使えないため、形で判定する
  function isErrorLike(value) {
    return typeof value.message === 'string' && typeof value.name === 'string' &&
      ('stack' in value);
  }

  function isElementLike(value) {
    return value.nodeType === 1 && typeof value.tagName === 'string';
  }

  function isArrayLike(value) {
    if (Array.isArray(value)) return true;
    return typeof value.length === 'number' && typeof value.splice === 'function';
  }

  function describeElement(el) {
    const id = el.id ? '#' + el.id : '';
    const className = typeof el.className === 'string' && el.className
      ? '.' + el.className.trim().split(/\s+/).join('.')
      : '';
    return '<' + el.tagName.toLowerCase() + id + className + '>';
  }

  // 1 回の整形で走査できるノード数。深い・巨大なオブジェクトで走査が長引かないようにする
  let formatNodeBudget = MAX_FORMAT_NODES;

  function formatProperty(value, key, depth) {
    try {
      return formatValue(value[key], depth + 1);
    } catch (error) {
      // getter が例外を投げる場合がある
      return '[取得できません]';
    }
  }

  function formatEntries(value, keys, depth, open, close) {
    const shown = keys.slice(0, MAX_FORMAT_ENTRIES);
    const parts = shown.map(function (key) {
      const formatted = formatProperty(value, key, depth);
      return open === '[' ? formatted : key + ': ' + formatted;
    });
    if (keys.length > shown.length) {
      parts.push('…他 ' + (keys.length - shown.length) + ' 件');
    }
    return open + parts.join(', ') + close;
  }

  function formatObjectLike(value, depth) {
    if (depth >= MAX_FORMAT_DEPTH) return isArrayLike(value) ? '[…]' : '{…}';
    if (formatNodeBudget <= 0) return '…';
    formatNodeBudget -= 1;
    if (isArrayLike(value)) {
      const indexes = [];
      for (let index = 0; index < value.length; index += 1) {
        indexes.push(index);
      }
      return formatEntries(value, indexes, depth, '[', ']');
    }
    return formatEntries(value, Object.keys(value), depth, '{', '}');
  }

  function formatValue(value, depth) {
    if (value === undefined) return 'undefined';
    if (value === null) return 'null';
    const type = typeof value;
    if (type === 'string') return depth > 0 ? truncate(JSON.stringify(value)) : truncate(value);
    if (type === 'number' || type === 'boolean' || type === 'bigint' || type === 'symbol') {
      return String(value);
    }
    if (type === 'function') return 'function ' + (value.name || '(anonymous)') + '()';
    try {
      if (isErrorLike(value)) return value.stack || value.name + ': ' + value.message;
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

  function formatArgs(args) {
    const values = Array.prototype.slice.call(args, 0, MAX_ARG_COUNT);
    // 引数ごとに切り詰めてから連結し、巨大なオブジェクトでも中間文字列を膨らませない
    return truncate(values.map(function (value) {
      formatNodeBudget = MAX_FORMAT_NODES;
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
      const detail = event.error ? formatValue(event.error, 0) : event.message;
      addLog('error', truncate(detail + at));
    }, true);
    window.addEventListener('unhandledrejection', function (event) {
      addLog('error', truncate('Uncaught (in promise) ' + formatValue(event.reason, 0)));
    }, true);
  }

  // ---- スクリプト実行 ----

  // ページの CSP が eval を禁止している場合、ページコンテキストでの eval は EvalError になる
  function isEvalBlockedByCsp(error) {
    if (!error) return false;
    if (error.name === 'EvalError') return true;
    return /call to eval|unsafe-eval|Content Security Policy/i.test(String(error.message || ''));
  }

  function evaluate(code) {
    try {
      return pageWindow.eval(code);
    } catch (error) {
      if (!isEvalBlockedByCsp(error)) throw error;
    }
    // CSP でページ側の eval が禁止されている場合はコンテンツスクリプト側で実行する。
    // ページの変数は参照できないが、DOM 操作は同じように行える
    return eval(code);
  }

  function isThenable(value) {
    return value !== null && typeof value === 'object' && typeof value.then === 'function';
  }

  function postExecuteSuccess(requestId, value) {
    formatNodeBudget = MAX_FORMAT_NODES;
    postMessage({
      action: 'executeResult',
      requestId: requestId,
      success: true,
      result: truncate(formatValue(value, 0)),
    });
  }

  function postExecuteFailure(requestId, error) {
    formatNodeBudget = MAX_FORMAT_NODES;
    postMessage({
      action: 'executeResult',
      requestId: requestId,
      success: false,
      error: truncate(formatValue(error, 0)),
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

  // ---- ネイティブからの要求 ----

  port.onMessage.addListener(function (msg) {
    if (!msg) return;
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
  });

  installConsoleHook();
  installErrorHooks();

  // フォーカスの移動を監視して都度通知する
  document.addEventListener('focusin', reportFocusedInput, true);
  document.addEventListener('focusout', function () {
    // focusout 直後に activeElement が body に戻るため、次のフレームで再評価する
    setTimeout(reportFocusedInput, 0);
  }, true);

  // 接続直後に現在の状態を一度送信する
  reportFocusedInput();
})();
