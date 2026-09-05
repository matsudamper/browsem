// 開発者ツール用のコンテンツスクリプト。
// フォーカス中の入力要素通知、ページ console 出力の転送、スクリプト実行を行う。
(function () {
  // トップフレームのみで実行する
  if (window !== window.top) return;

  const MAX_EXECUTE_CODE_LENGTH = 10000;
  const MAX_CONSOLE_MESSAGE_LENGTH = 4096;

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

  function truncateMessage(message) {
    if (message.length <= MAX_CONSOLE_MESSAGE_LENGTH) return message;
    return message.slice(0, MAX_CONSOLE_MESSAGE_LENGTH) + '…';
  }

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
        focused: true,
        id: el.id || '',
        tagName: (el.tagName || '').toLowerCase(),
        type: (el.getAttribute && el.getAttribute('type')) || '',
        name: el.name || '',
      });
    } else {
      postMessage({ focused: false });
    }
  }

  function safeFormatConsoleArg(value) {
    if (value === undefined) return 'undefined';
    if (value === null) return 'null';
    const type = typeof value;
    if (type === 'string') return value;
    if (type === 'number' || type === 'boolean' || type === 'bigint') return String(value);
    if (type === 'function') return '[Function]';
    if (type === 'symbol') return String(value);
    try {
      return Object.prototype.toString.call(value);
    } catch (error) {
      return '[object]';
    }
  }

  function safeFormatConsoleArgs(args) {
    return Array.from(args).map(safeFormatConsoleArg).join(' ');
  }

  function formatResult(value) {
    if (value === undefined) return 'undefined';
    if (value === null) return 'null';
    if (typeof value === 'string') return value;
    if (typeof value === 'number' || typeof value === 'boolean' || typeof value === 'bigint') {
      return String(value);
    }
    if (typeof value === 'function') return '[Function]';
    if (typeof value === 'symbol') return String(value);
    try {
      return Object.prototype.toString.call(value);
    } catch (error) {
      return '[object]';
    }
  }

  const pageWin = window.wrappedJSObject;

  function installConsoleHook() {
    if (pageWin.__devToolsConsoleHookInstalled) return;
    pageWin.__devToolsConsoleHookInstalled = true;

    const notifyConsole = exportFunction(function (level, message) {
      postMessage({
        action: 'consoleLog',
        level: level,
        message: message,
        url: location.href,
        timestamp: Date.now(),
      });
    }, pageWin);

    const levels = ['log', 'warn', 'error', 'info', 'debug'];
    levels.forEach(function (level) {
      const original = pageWin.console[level];
      pageWin.console[level] = exportFunction(function () {
        original.apply(pageWin.console, arguments);
        try {
          notifyConsole(level, truncateMessage(safeFormatConsoleArgs(arguments)));
        } catch (error) {
          // 転送失敗でページ側の console 呼び出しを壊さない
        }
      }, pageWin);
    });
  }

  pageWin.__browsemDevToolsReportResult = exportFunction(function (requestId, success, value) {
    if (success) {
      postMessage({
        action: 'executeResult',
        requestId: requestId,
        success: true,
        result: formatResult(value),
      });
      return;
    }
    postMessage({
      action: 'executeResult',
      requestId: requestId,
      success: false,
      error: String(value && value.message ? value.message : value),
    });
  }, pageWin);

  function executeScript(requestId, code) {
    if (!code) {
      postMessage({
        action: 'executeResult',
        requestId: requestId,
        success: false,
        error: 'スクリプトが空です',
      });
      return;
    }
    if (code.length > MAX_EXECUTE_CODE_LENGTH) {
      postMessage({
        action: 'executeResult',
        requestId: requestId,
        success: false,
        error: 'スクリプトが長すぎます',
      });
      return;
    }

    const script = document.createElement('script');
    script.textContent =
      '(function(){' +
      'var requestId=' + JSON.stringify(requestId) + ';' +
      'try{' +
      'var result=eval(' + JSON.stringify(code) + ');' +
      'if(result&&typeof result.then==="function"){' +
      'result.then(function(res){window.__browsemDevToolsReportResult(requestId,true,res);})' +
      '.catch(function(err){window.__browsemDevToolsReportResult(requestId,false,err);});' +
      'return;' +
      '}' +
      'window.__browsemDevToolsReportResult(requestId,true,result);' +
      '}catch(e){window.__browsemDevToolsReportResult(requestId,false,e);}' +
      '})();';
    (document.documentElement || document.head).appendChild(script);
    script.remove();
  }

  installConsoleHook();

  port.onMessage.addListener(function (msg) {
    if (!msg) return;

    // ネイティブ側からの明示的な問い合わせに応答する
    if (msg.action === 'query') {
      reportFocusedInput();
      return;
    }

    if (msg.action === 'execute') {
      executeScript(msg.requestId || '', msg.code || '');
    }
  });

  function setupFocusListeners() {
    if (setupFocusListeners.done) return;
    setupFocusListeners.done = true;
    document.addEventListener('focusin', reportFocusedInput, true);
    document.addEventListener('focusout', function () {
      // focusout 直後に activeElement が body に戻るため、次のフレームで再評価する
      setTimeout(reportFocusedInput, 0);
    }, true);
    reportFocusedInput();
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', setupFocusListeners);
  } else {
    setupFocusListeners();
  }
})();
