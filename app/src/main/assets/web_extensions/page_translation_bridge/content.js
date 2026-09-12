(function () {
  if (window !== window.top) return;

  const NATIVE_APP_ID = 'pageTranslationBridge';
  const SEGMENT_BATCH_SIZE = 48;
  const SEGMENT_BATCH_CHAR_LIMIT = 32768;
  const DYNAMIC_FLUSH_DELAY_MS = 120;
  const NATIVE_RECONNECT_DELAY_MS = 1000;
  const TRANSLATED_ATTRIBUTES = ['title', 'aria-label', 'aria-description', 'placeholder', 'alt'];
  const OBSERVED_ATTRIBUTES = TRANSLATED_ATTRIBUTES.concat(['value']);
  const EXCLUDED_TAGS = new Set([
    'SCRIPT',
    'STYLE',
    'NOSCRIPT',
    'TEMPLATE',
    'CODE',
    'PRE',
    'KBD',
    'SAMP',
    'SVG',
    'MATH',
    'CANVAS',
    'HEAD',
    'TITLE',
    'TEXTAREA',
  ]);

  const documentId = Date.now().toString(36) + '-' + Math.random().toString(36).slice(2);

  let port = null;
  let observer = null;
  let nextSegmentId = 1;
  let nodeIds = new WeakMap();
  let attributeIds = new WeakMap();
  let knownShadowRoots = new Set();
  const entries = new Map();
  const pendingDynamicSegments = new Map();
  let dynamicFlushTimer = null;
  let nativeReconnectTimer = null;
  // 再接続時に監視を張り直すため、翻訳中かどうかを保持する
  let translationActive = false;

  function postMessage(message) {
    if (port === null) return false;
    try {
      port.postMessage(message);
      return true;
    } catch (error) {
      return false;
    }
  }

  function sourceText(value) {
    return String(value || '').trim();
  }

  function isTranslatableText(value) {
    const text = sourceText(value);
    if (!text || !/[\p{L}\p{N}]/u.test(text)) return false;
    if (/^(?:https?:\/\/|ftp:\/\/|www\.)\S+$/i.test(text)) return false;
    if (/^[\w.+-]+@[\w.-]+\.[A-Za-z]{2,}$/.test(text)) return false;
    return true;
  }

  function parentAcrossShadow(element) {
    if (element.parentElement) return element.parentElement;
    const root = element.getRootNode && element.getRootNode();
    return root && root.host ? root.host : null;
  }

  function isExcludedElement(element) {
    let current = element;
    while (current) {
      const tagName = (current.tagName || '').toUpperCase();
      if (EXCLUDED_TAGS.has(tagName)) return true;
      if (current.isContentEditable) return true;
      if (current.hasAttribute && current.hasAttribute('hidden')) return true;
      if (tagName === 'INPUT' && (current.getAttribute('type') || '').toLowerCase() === 'hidden') return true;
      if (current.getAttribute && current.getAttribute('translate') === 'no') return true;
      if (current.classList && current.classList.contains('notranslate')) return true;
      current = parentAcrossShadow(current);
    }
    return false;
  }

  function nextId(prefix) {
    const id = prefix + nextSegmentId;
    nextSegmentId += 1;
    return id;
  }

  function textSegment(node) {
    const parent = node.parentElement;
    if (!parent || isExcludedElement(parent)) return null;

    const currentValue = node.nodeValue || '';
    const id = nodeIds.get(node);
    const existing = id ? entries.get(id) : null;
    if (existing && existing.lastApplied === currentValue) return null;

    const text = sourceText(currentValue);
    if (!isTranslatableText(text)) {
      if (existing) {
        existing.originalValue = currentValue;
        existing.sourceText = '';
        existing.lastApplied = null;
      }
      return null;
    }

    const segmentId = id || nextId('t');
    if (!id) nodeIds.set(node, segmentId);
    entries.set(segmentId, {
      kind: 'text',
      node: node,
      originalValue: currentValue,
      sourceText: text,
      lastApplied: null,
    });
    return { id: segmentId, text: text };
  }

  function isButtonValueAttribute(element, name) {
    if (name !== 'value') return true;
    if ((element.tagName || '').toUpperCase() !== 'INPUT') return false;
    const type = (element.getAttribute('type') || 'text').toLowerCase();
    return type === 'button' || type === 'submit' || type === 'reset';
  }

  function attributeSegment(element, name) {
    if (!isButtonValueAttribute(element, name) || isExcludedElement(element)) return null;

    const currentValue = element.getAttribute(name);
    let ids = attributeIds.get(element);
    if (!ids) {
      ids = new Map();
      attributeIds.set(element, ids);
    }
    const id = ids.get(name);
    const existing = id ? entries.get(id) : null;
    if (existing && existing.lastApplied === currentValue) return null;

    const text = sourceText(currentValue);
    if (!isTranslatableText(text)) {
      if (existing) {
        existing.originalValue = currentValue;
        existing.sourceText = '';
        existing.lastApplied = null;
      }
      return null;
    }

    const segmentId = id || nextId('a');
    if (!id) ids.set(name, segmentId);
    entries.set(segmentId, {
      kind: 'attribute',
      element: element,
      attributeName: name,
      originalValue: currentValue,
      sourceText: text,
      lastApplied: null,
    });
    return { id: segmentId, text: text };
  }

  function collectElementAttributes(element, output) {
    TRANSLATED_ATTRIBUTES.forEach(function (name) {
      if (!element.hasAttribute(name)) return;
      const segment = attributeSegment(element, name);
      if (segment) output.push(segment);
    });
    if (element.hasAttribute('value')) {
      const segment = attributeSegment(element, 'value');
      if (segment) output.push(segment);
    }
  }

  function collectRoot(root, output) {
    if (!root) return;
    if (root.nodeType === Node.ELEMENT_NODE) {
      const rootElement = root;
      if (!isExcludedElement(rootElement)) {
        collectElementAttributes(rootElement, output);
      }
    }

    const walker = document.createTreeWalker(
      root,
      NodeFilter.SHOW_ELEMENT | NodeFilter.SHOW_TEXT,
    );
    let node = walker.nextNode();
    while (node) {
      if (node.nodeType === Node.TEXT_NODE) {
        const segment = textSegment(node);
        if (segment) output.push(segment);
      } else if (node.nodeType === Node.ELEMENT_NODE) {
        const element = node;
        if (!isExcludedElement(element)) {
          collectElementAttributes(element, output);
          if (element.shadowRoot) {
            knownShadowRoots.add(element.shadowRoot);
            collectRoot(element.shadowRoot, output);
          }
        }
      }
      node = walker.nextNode();
    }
  }

  function collectNode(node, output) {
    if (node.nodeType === Node.TEXT_NODE) {
      const segment = textSegment(node);
      if (segment) output.push(segment);
      return;
    }
    if (node.nodeType === Node.ELEMENT_NODE || node.nodeType === Node.DOCUMENT_FRAGMENT_NODE) {
      collectRoot(node, output);
    }
  }

  function sendSegmentBatches(action, requestId, segments) {
    let batch = [];
    let batchChars = 0;

    function flush() {
      if (batch.length === 0) return;
      const message = {
        action: action,
        documentId: documentId,
        segments: batch,
      };
      if (requestId) message.requestId = requestId;
      postMessage(message);
      batch = [];
      batchChars = 0;
    }

    segments.forEach(function (segment) {
      const chars = segment.text.length;
      if (batch.length > 0 &&
          (batch.length >= SEGMENT_BATCH_SIZE || batchChars + chars > SEGMENT_BATCH_CHAR_LIMIT)) {
        flush();
      }
      batch.push(segment);
      batchChars += chars;
    });
    flush();
  }

  function preserveWhitespace(original, translated) {
    const leadingMatch = String(original || '').match(/^\s*/);
    const trailingMatch = String(original || '').match(/\s*$/);
    const leading = leadingMatch ? leadingMatch[0] : '';
    const trailing = trailingMatch ? trailingMatch[0] : '';
    return leading + String(translated || '').trim() + trailing;
  }

  function refreshEntryIfPageChanged(entry) {
    if (entry.kind === 'text') {
      if (!entry.node.isConnected) return true;
      const currentValue = entry.node.nodeValue || '';
      if (currentValue === entry.originalValue || currentValue === entry.lastApplied) return false;
      const segment = textSegment(entry.node);
      if (segment) queueDynamicSegment(segment);
      return true;
    }

    if (!entry.element.isConnected) return true;
    const currentValue = entry.element.getAttribute(entry.attributeName);
    if (currentValue === entry.originalValue || currentValue === entry.lastApplied) return false;
    const segment = attributeSegment(entry.element, entry.attributeName);
    if (segment) queueDynamicSegment(segment);
    return true;
  }

  function applyTranslations(translations) {
    let appliedCount = 0;
    let requeuedCount = 0;
    translations.forEach(function (translation) {
      const entry = entries.get(translation.id);
      if (!entry || entry.sourceText !== translation.sourceText) return;
      // ページ側が書き換えたノードは動的セグメントとして翻訳し直される
      if (refreshEntryIfPageChanged(entry)) {
        requeuedCount += 1;
        return;
      }

      // 原文と同じ訳文でも反映は成功しているため、値の差分ではなく処理できた件数を数える
      if (entry.kind === 'text') {
        const translatedValue = preserveWhitespace(entry.originalValue, translation.translatedText);
        entry.lastApplied = translatedValue;
        entry.node.nodeValue = translatedValue;
        appliedCount += 1;
      } else {
        const translatedValue = String(translation.translatedText || '').trim();
        entry.lastApplied = translatedValue;
        entry.element.setAttribute(entry.attributeName, translatedValue);
        appliedCount += 1;
      }
    });
    return { appliedCount: appliedCount, requeuedCount: requeuedCount };
  }

  function restoreAll() {
    entries.forEach(function (entry) {
      if (entry.kind === 'text') {
        if (!entry.node.isConnected) return;
        if (entry.lastApplied !== null && entry.node.nodeValue === entry.lastApplied) {
          entry.node.nodeValue = entry.originalValue;
        }
        return;
      }
      if (!entry.element.isConnected) return;
      if (entry.lastApplied !== null && entry.element.getAttribute(entry.attributeName) === entry.lastApplied) {
        if (entry.originalValue === null) {
          entry.element.removeAttribute(entry.attributeName);
        } else {
          entry.element.setAttribute(entry.attributeName, entry.originalValue);
        }
      }
    });
  }

  function resetEntries() {
    entries.clear();
    pendingDynamicSegments.clear();
    nextSegmentId = 1;
    nodeIds = new WeakMap();
    attributeIds = new WeakMap();
    knownShadowRoots = new Set();
    if (dynamicFlushTimer !== null) {
      clearTimeout(dynamicFlushTimer);
      dynamicFlushTimer = null;
    }
  }

  function flushDynamicSegments() {
    dynamicFlushTimer = null;
    if (pendingDynamicSegments.size === 0) return;
    const segments = Array.from(pendingDynamicSegments.values());
    pendingDynamicSegments.clear();
    sendSegmentBatches('dynamicSegments', null, segments);
  }

  function queueDynamicSegment(segment) {
    pendingDynamicSegments.set(segment.id, segment);
    if (dynamicFlushTimer !== null) return;
    dynamicFlushTimer = setTimeout(flushDynamicSegments, DYNAMIC_FLUSH_DELAY_MS);
  }

  function observeRoot(root) {
    if (!observer || !root) return;
    observer.observe(root, {
      subtree: true,
      childList: true,
      characterData: true,
      attributes: true,
      attributeFilter: OBSERVED_ATTRIBUTES,
    });
  }

  function startObserver() {
    stopObserver();
    observer = new MutationObserver(function (mutations) {
      const newSegments = [];
      mutations.forEach(function (mutation) {
        if (mutation.type === 'characterData') {
          const segment = textSegment(mutation.target);
          if (segment) newSegments.push(segment);
          return;
        }
        if (mutation.type === 'attributes') {
          const name = mutation.attributeName || '';
          const segment = attributeSegment(mutation.target, name);
          if (segment) newSegments.push(segment);
          return;
        }
        mutation.addedNodes.forEach(function (node) {
          collectNode(node, newSegments);
        });
      });
      newSegments.forEach(queueDynamicSegment);

      knownShadowRoots.forEach(function (shadowRoot) {
        observeRoot(shadowRoot);
      });
    });

    observeRoot(document.body);
    knownShadowRoots.forEach(function (shadowRoot) {
      observeRoot(shadowRoot);
    });
  }

  function stopObserver() {
    if (observer) {
      observer.disconnect();
      observer = null;
    }
  }

  function startTranslation(requestId) {
    stopObserver();
    restoreAll();
    resetEntries();

    postMessage({
      action: 'scanStart',
      requestId: requestId,
      documentId: documentId,
      documentUrl: location.href,
      htmlLanguage: document.documentElement ? (document.documentElement.lang || '') : '',
    });

    const segments = [];
    try {
      collectRoot(document.body, segments);
    } catch (error) {
      // 収集が途中で落ちると scanComplete が送られず、アプリ側は待ち続けてしまう
      postMessage({
        action: 'scanFailed',
        requestId: requestId,
        documentId: documentId,
        reason: String((error && error.message) || error),
      });
      return;
    }
    sendSegmentBatches('scanSegments', requestId, segments);
    postMessage({
      action: 'scanComplete',
      requestId: requestId,
      documentId: documentId,
      segmentCount: segments.length,
    });
    translationActive = true;
    startObserver();
  }

  function onNativeMessage(message) {
    if (!message) return;
    if (message.action === 'start') {
      startTranslation(message.requestId || '');
      return;
    }
    if (message.action === 'apply') {
      const documentMatched = message.documentId === documentId;
      const result = documentMatched
        ? applyTranslations(Array.isArray(message.translations) ? message.translations : [])
        : { appliedCount: 0, requeuedCount: 0 };
      if (message.requestId) {
        postMessage({
          action: 'applyResult',
          requestId: message.requestId,
          documentId: documentId,
          documentMatched: documentMatched,
          appliedCount: result.appliedCount,
          requeuedCount: result.requeuedCount,
        });
      }
      return;
    }
    if (message.action === 'revert') {
      translationActive = false;
      stopObserver();
      restoreAll();
      resetEntries();
      return;
    }
    if (message.action === 'stop') {
      translationActive = false;
      stopObserver();
      pendingDynamicSegments.clear();
    }
  }

  function scheduleNativeReconnect() {
    if (document.visibilityState !== 'visible' || port !== null || nativeReconnectTimer !== null) return;
    nativeReconnectTimer = setTimeout(function () {
      nativeReconnectTimer = null;
      connect();
    }, NATIVE_RECONNECT_DELAY_MS);
  }

  function connect() {
    if (document.visibilityState !== 'visible' || port !== null) return;
    let connectedPort;
    try {
      connectedPort = browser.runtime.connectNative(NATIVE_APP_ID);
    } catch (error) {
      scheduleNativeReconnect();
      return;
    }
    port = connectedPort;
    // 切断中に監視が外れているため、翻訳中なら張り直して動的翻訳を継続する
    if (translationActive) startObserver();
    connectedPort.onMessage.addListener(onNativeMessage);
    connectedPort.onDisconnect.addListener(function () {
      if (port !== connectedPort) return;
      port = null;
      stopObserver();
      scheduleNativeReconnect();
    });
  }

  function connectWhenVisible() {
    if (document.visibilityState !== 'visible') return;
    connect();
  }

  // bfcache へ入ったドキュメントが接続を保持すると、表示中のページではなく
  // 退避済みのページを翻訳してしまうため、離脱時に必ず切断する
  function disconnectOnPageHide() {
    if (nativeReconnectTimer !== null) {
      clearTimeout(nativeReconnectTimer);
      nativeReconnectTimer = null;
    }
    stopObserver();
    if (port === null) return;
    const disconnectingPort = port;
    port = null;
    try {
      disconnectingPort.disconnect();
    } catch (error) {
      // 切断済みの場合は何もしない
    }
  }

  document.addEventListener('visibilitychange', connectWhenVisible);
  window.addEventListener('pageshow', connectWhenVisible);
  window.addEventListener('pagehide', disconnectOnPageHide);

  connect();
})();
