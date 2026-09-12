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
    translations.forEach(function (translation) {
      const entry = entries.get(translation.id);
      if (!entry || entry.sourceText !== translation.sourceText) return;
      if (refreshEntryIfPageChanged(entry)) return;

      if (entry.kind === 'text') {
        const translatedValue = preserveWhitespace(entry.originalValue, translation.translatedText);
        entry.lastApplied = translatedValue;
        entry.node.nodeValue = translatedValue;
      } else {
        const translatedValue = String(translation.translatedText || '').trim();
        entry.lastApplied = translatedValue;
        entry.element.setAttribute(entry.attributeName, translatedValue);
      }
    });
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
      htmlLanguage: document.documentElement ? (document.documentElement.lang || '') : '',
    });

    const segments = [];
    collectRoot(document.body, segments);
    sendSegmentBatches('scanSegments', requestId, segments);
    postMessage({
      action: 'scanComplete',
      requestId: requestId,
      documentId: documentId,
    });
    startObserver();
  }

  function onNativeMessage(message) {
    if (!message) return;
    if (message.action === 'start') {
      startTranslation(message.requestId || '');
      return;
    }
    if (message.action === 'apply' && message.documentId === documentId) {
      applyTranslations(Array.isArray(message.translations) ? message.translations : []);
      return;
    }
    if (message.action === 'revert') {
      stopObserver();
      restoreAll();
      resetEntries();
      return;
    }
    if (message.action === 'stop') {
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

  document.addEventListener('visibilitychange', connectWhenVisible);
  window.addEventListener('pageshow', connectWhenVisible);

  connect();
})();
