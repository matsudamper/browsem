// 開発者ツール用のコンテンツスクリプト。
// フォーカス中の入力要素の通知、ページの console 出力の転送、JavaScript の実行を担当する。
(function () {
  // トップフレームのみで実行する
  if (window !== window.top) return;

  // ページ側の console 出力を保持する上限。開発者ツールを開く前の出力も見せるため先読みで貯める
  const MAX_BUFFERED_LOGS = 500;
  const MAX_MESSAGE_LENGTH = 4096;
  const MAX_ARG_COUNT = 20;

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

  function describeElement(el) {
    const id = el.id ? '#' + el.id : '';
    const className = typeof el.className === 'string' && el.className
      ? '.' + el.className.trim().split(/\s+/).join('.')
      : '';
    return '<' + el.tagName.toLowerCase() + id + className + '>';
  }

  function stringifyObject(value) {
    const seen = new WeakSet();
    return JSON.stringify(value, function (key, entry) {
      if (entry === null || typeof entry !== 'object') return entry;
      if (seen.has(entry)) return '[Circular]';
      seen.add(entry);
      return entry;
    }, 2);
  }

  function formatValue(value) {
    if (value === undefined) return 'undefined';
    if (value === null) return 'null';
    const type = typeof value;
    if (type === 'string') return value;
    if (type === 'number' || type === 'boolean' || type === 'bigint' || type === 'symbol') {
      return String(value);
    }
    if (type === 'function') return 'function ' + (value.name || '(anonymous)') + '()';
    try {
      if (isErrorLike(value)) return value.stack || value.name + ': ' + value.message;
      if (isElementLike(value)) return describeElement(value);
      const json = stringifyObject(value);
      if (json !== undefined) return json;
    } catch (error) {
      // 循環参照や getter の例外では String() にフォールバックする
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
      return truncate(formatValue(value));
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

  function installConsoleHook() {
    const pageConsole = pageWindow.console;
    if (!pageConsole) return;
    const notify = exportFunction(function (level, message) {
      addLog(level, message);
    }, pageWindow);

    ['log', 'info', 'warn', 'error', 'debug'].forEach(function (level) {
      const original = pageConsole[level];
      if (typeof original !== 'function') return;
      pageConsole[level] = exportFunction(function () {
        try {
          notify(level, formatArgs(arguments));
        } catch (error) {
          // 転送に失敗してもページ側の console 呼び出しは壊さない
        }
        return original.apply(pageConsole, arguments);
      }, pageWindow);
    });
  }

  function installErrorHooks() {
    window.addEventListener('error', function (event) {
      const detail = event.error ? formatValue(event.error) : String(event.message || '');
      const at = event.filename ? ' (' + event.filename + ':' + event.lineno + ')' : '';
      addLog('error', truncate(detail + at));
    }, true);
    window.addEventListener('unhandledrejection', function (event) {
      addLog('error', truncate('Uncaught (in promise) ' + formatValue(event.reason)));
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
    postMessage({
      action: 'executeResult',
      requestId: requestId,
      success: true,
      result: truncate(formatValue(value)),
    });
  }

  function postExecuteFailure(requestId, error) {
    postMessage({
      action: 'executeResult',
      requestId: requestId,
      success: false,
      error: truncate(formatValue(error)),
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
