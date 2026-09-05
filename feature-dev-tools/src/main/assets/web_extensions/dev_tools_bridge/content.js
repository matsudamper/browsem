// 開発者ツール用のコンテンツスクリプト。
// フォーカス中の入力要素通知、ページ console 出力の転送、スクリプト実行を行う。
(function () {
  // トップフレームのみで実行する
  if (window !== window.top) return;

  const MAX_EXECUTE_CODE_LENGTH = 10000;

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

  function stringifyConsoleArgs(args) {
    return Array.from(args).map(function (value) {
      if (typeof value === 'string') return value;
      if (value === undefined) return 'undefined';
      if (value === null) return 'null';
      if (typeof value === 'object') {
        try {
          return JSON.stringify(value);
        } catch (error) {
          return String(value);
        }
      }
      return String(value);
    }).join(' ');
  }

  function formatResult(value) {
    if (value === undefined) return 'undefined';
    if (value === null) return 'null';
    if (typeof value === 'string') return value;
    if (typeof value === 'object') {
      try {
        return JSON.stringify(value);
      } catch (error) {
        return String(value);
      }
    }
    return String(value);
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
        notifyConsole(level, stringifyConsoleArgs(arguments));
        original.apply(pageWin.console, arguments);
      }, pageWin);
    });
  }

  const evaluateInPage = exportFunction(function (source) {
    // eslint-disable-next-line no-eval
    return eval(source);
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
    try {
      const result = evaluateInPage(code);
      postMessage({
        action: 'executeResult',
        requestId: requestId,
        success: true,
        result: formatResult(result),
      });
    } catch (error) {
      postMessage({
        action: 'executeResult',
        requestId: requestId,
        success: false,
        error: String(error && error.message ? error.message : error),
      });
    }
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

  // フォーカスの移動を監視して都度通知する
  document.addEventListener('focusin', reportFocusedInput, true);
  document.addEventListener('focusout', function () {
    // focusout 直後に activeElement が body に戻るため、次のフレームで再評価する
    setTimeout(reportFocusedInput, 0);
  }, true);

  // 接続直後に現在の状態を一度送信する
  reportFocusedInput();
})();
