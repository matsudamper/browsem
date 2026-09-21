// 住所選択とメール選択は別経路。名前欄の選択でメールを埋めない。
// Gecko FormAutofill は shadow DOM 内 iframe を埋められないため、
// all_frames のコンテンツスクリプトから直接 value を書く。
(function () {
  'use strict';

  const FAMILY_NAME_TOKENS = ['family-name', 'familyname', 'lastname', 'last-name'];
  const GIVEN_NAME_TOKENS = ['given-name', 'givenname', 'firstname', 'first-name'];
  const ADDITIONAL_NAME_TOKENS = ['additional-name', 'additionalname', 'middlename', 'middle-name'];
  const FULL_NAME_TOKENS = ['name'];
  const ORGANIZATION_TOKENS = ['organization', 'org', 'company'];
  const STREET_ADDRESS_TOKENS = ['street-address', 'streetaddress'];
  const ADDRESS_LINE1_TOKENS = ['address-line1', 'addressline1'];
  const ADDRESS_LINE2_TOKENS = ['address-line2', 'addressline2'];
  const ADDRESS_LINE3_TOKENS = ['address-line3', 'addressline3'];
  const ADDRESS_LEVEL1_TOKENS = ['address-level1', 'addresslevel1', 'state', 'province'];
  const ADDRESS_LEVEL2_TOKENS = ['address-level2', 'addresslevel2', 'city'];
  const ADDRESS_LEVEL3_TOKENS = ['address-level3', 'addresslevel3'];
  const POSTAL_TOKENS = ['postal-code', 'postalcode', 'zip', 'zipcode', 'postcode'];
  const COUNTRY_TOKENS = ['country', 'country-name', 'countryname'];
  const TEL_TOKENS = ['tel', 'telephone', 'phone'];
  const EMAIL_TOKENS = ['email'];

  // 実質的に見えない不透明度の閾値。opacity:0.0001 のような回避も隠しているとみなす。
  const TRANSPARENT_OPACITY = 0.01;

  // 実質的に見えない描画サイズの閾値 (CSS px)。width:1px や transform:scale(0.001) を弾く。
  // 正当な入力欄がこれを下回ることはない。getBoundingClientRect は transform 適用後の値を返す。
  const MIN_VISIBLE_SIZE = 4;

  const FIELD_MAP = [
    { tokens: FAMILY_NAME_TOKENS, key: 'familyName' },
    { tokens: GIVEN_NAME_TOKENS, key: 'givenName' },
    { tokens: ADDITIONAL_NAME_TOKENS, key: 'additionalName' },
    { tokens: FULL_NAME_TOKENS, key: 'name' },
    { tokens: ORGANIZATION_TOKENS, key: 'organization' },
    { tokens: STREET_ADDRESS_TOKENS.concat(ADDRESS_LINE1_TOKENS), key: 'streetAddress' },
    { tokens: ADDRESS_LINE2_TOKENS.concat(ADDRESS_LINE3_TOKENS), key: '' },
    { tokens: ADDRESS_LEVEL1_TOKENS, key: 'addressLevel1' },
    { tokens: ADDRESS_LEVEL2_TOKENS, key: 'addressLevel2' },
    { tokens: ADDRESS_LEVEL3_TOKENS, key: 'addressLevel3' },
    { tokens: POSTAL_TOKENS, key: 'postalCode' },
    { tokens: COUNTRY_TOKENS, key: 'country' },
    { tokens: TEL_TOKENS, key: 'tel' },
    { tokens: EMAIL_TOKENS, key: 'email' },
  ];

  function tokenize(value) {
    if (!value) return [];
    const text = String(value);
    const lower = text.toLowerCase();
    const delimited = lower.split(/[\s_]+/).filter(Boolean);
    const camel = text.split(/(?<=[a-z0-9])(?=[A-Z])/).map(function (part) {
      return part.toLowerCase();
    }).filter(Boolean);
    return delimited.concat(camel, [lower]).map(function (token) {
      return token.replace(/_/g, '-');
    });
  }

  function fieldTokens(el) {
    return tokenize(el.getAttribute('autocomplete'))
      .concat(tokenize(el.id))
      .concat(tokenize(el.getAttribute('name')))
      .concat(tokenize(el.getAttribute('autofillhint')));
  }

  function isNonValueField(el) {
    const type = (el.getAttribute('type') || '').toLowerCase();
    return type === 'hidden' || type === 'submit' || type === 'button' ||
      type === 'reset' || type === 'checkbox' || type === 'radio';
  }

  function hasAutocompleteOff(el) {
    const autocomplete = (el.getAttribute('autocomplete') || '').toLowerCase().trim();
    return autocomplete === 'off' || autocomplete === 'new-password';
  }

  // collectFields は shadow root 内も収集するため、祖先をたどる際も ShadowRoot 境界を越える。
  // 境界で parentElement が null になり、透明な shadow host を見落とすのを防ぐ。
  function composedParentElement(node) {
    if (node.parentElement) return node.parentElement;
    const root = node.getRootNode();
    return root && root.host ? root.host : null;
  }

  // filter: opacity(0) は computed opacity を 1 のままにするため、opacity とは別に見る必要がある。
  // filter: url(#hide) のような SVG フィルターは適用後の不透明度をここでは算出できない。
  // 入力欄やそのラッパーに描画フィルターが使われることはまず無いため、保守的に隠しているとみなす。
  function filterOpacity(filter) {
    if (!filter || filter === 'none') return 1;
    if (filter.indexOf('url(') !== -1) return 0;
    const pattern = /opacity\(\s*([0-9.]+)(%?)\s*\)/g;
    let opacity = 1;
    let match = pattern.exec(filter);
    while (match) {
      const value = Number(match[1]);
      opacity *= match[2] === '%' ? value / 100 : value;
      match = pattern.exec(filter);
    }
    return opacity;
  }

  // mask-image: linear-gradient(transparent, transparent) は描画を完全に消すが opacity は 1 のまま。
  // 入力欄やそのラッパーに mask が使われることはまず無いため、指定があれば隠されているとみなす。
  function isMasked(style) {
    const maskImage = style.maskImage || style.webkitMaskImage;
    return !!maskImage && maskImage !== 'none';
  }

  function elementOpacity(style) {
    const opacity = Number(style.opacity);
    return (isNaN(opacity) ? 1 : opacity) * filterOpacity(style.filter);
  }

  // opacity / filter / clip は継承しないため、隠したラッパーを見抜くには祖先までたどる必要がある。
  // 不透明度は要素ごとではなく積で見る。opacity:0.02 のラッパーを重ねた合成透明化を見抜くため。
  // clip-path / clip は入力欄の装飾にはまず使われないので、指定があれば隠されているとみなす。
  // 判定を誤っても自動入力が働かなくなるだけで、値が漏れる方向には倒れない。
  function isInsideHiddenWrapper(el) {
    let node = el;
    let compositedOpacity = 1;
    while (node && node.nodeType === Node.ELEMENT_NODE) {
      const style = getComputedStyle(node);
      compositedOpacity *= elementOpacity(style);
      if (compositedOpacity < TRANSPARENT_OPACITY) return true;
      if (style.clipPath && style.clipPath !== 'none') return true;
      if (style.clip && style.clip !== 'auto') return true;
      if (isMasked(style)) return true;
      node = composedParentElement(node);
    }
    return false;
  }

  function isClippingOverflow(overflow) {
    // auto / scroll はユーザーがスクロールして到達できるため、隠されているとはみなさない。
    return overflow === 'hidden' || overflow === 'clip';
  }

  // overflow:hidden の祖先のクリップ領域外へ絶対配置された欄は、矩形もページ座標も有効なまま見えない。
  // クリップは重ねがけできるため交差を累積し、残った可視領域にも最小サイズを課す。
  function isClippedByAncestor(el, rect) {
    let left = rect.left;
    let top = rect.top;
    let right = rect.right;
    let bottom = rect.bottom;
    let node = composedParentElement(el);
    while (node && node.nodeType === Node.ELEMENT_NODE) {
      const style = getComputedStyle(node);
      const clipsX = isClippingOverflow(style.overflowX);
      const clipsY = isClippingOverflow(style.overflowY);
      if (clipsX || clipsY) {
        const bounds = node.getBoundingClientRect();
        if (clipsX) {
          left = Math.max(left, bounds.left);
          right = Math.min(right, bounds.right);
        }
        if (clipsY) {
          top = Math.max(top, bounds.top);
          bottom = Math.min(bottom, bounds.bottom);
        }
        if (right - left < MIN_VISIBLE_SIZE || bottom - top < MIN_VISIBLE_SIZE) return true;
      }
      node = composedParentElement(node);
    }
    return false;
  }

  /** computed color のアルファ値。rgb()/rgba() のカンマ区切りとスペース区切りの両方を受ける。 */
  function colorAlpha(color) {
    const match = /^rgba?\(([^)]*)\)$/.exec(String(color).trim());
    if (!match) return 1;
    const parts = match[1].replace(/\//g, ' ').trim().split(/[\s,]+/);
    if (parts.length < 4) return 1;
    const alpha = Number(parts[3]);
    return isNaN(alpha) ? 1 : alpha;
  }

  // 文字色が透明な欄は、埋めた値がユーザーに見えないままページ側の input ハンドラーへ渡る。
  // 正当な入力欄で文字色を透明にすることはないため、隠されているとみなす。
  function hasInvisibleText(style) {
    const textFillColor = style.webkitTextFillColor;
    if (textFillColor && colorAlpha(textFillColor) === 0) return true;
    return colorAlpha(style.color) === 0;
  }

  /** ヒットテストの結果が [el] 自身か、その内側・shadow host 側の要素なら [el] へ到達したとみなす。 */
  function hitReaches(el, x, y) {
    const root = el.getRootNode();
    const hit = typeof root.elementFromPoint === 'function'
      ? root.elementFromPoint(x, y)
      : document.elementFromPoint(x, y);
    if (!hit) return false;
    if (el.contains(hit)) return true;
    let node = hit;
    while (node) {
      if (node === el) return true;
      node = composedParentElement(node);
    }
    return false;
  }

  // 上に不透明な要素を重ねて隠す手口はスタイルや矩形からは見抜けないため、実際に最前面へ
  // 出ている点があるかを調べる。ラベルやアイコンによる部分的な重なりで誤判定しないよう
  // 複数点を見て、どれか一つでも到達できれば露出しているとみなす。
  // ビューポート外の欄はスクロールしないと判定できないため対象外とする。
  function isOccluded(el, rect) {
    const viewportWidth = window.innerWidth || document.documentElement.clientWidth;
    const viewportHeight = window.innerHeight || document.documentElement.clientHeight;
    const ratios = [0.5, 0.25, 0.75];
    let sampled = false;
    for (let i = 0; i < ratios.length; i++) {
      for (let j = 0; j < ratios.length; j++) {
        const x = rect.left + rect.width * ratios[i];
        const y = rect.top + rect.height * ratios[j];
        if (x < 0 || y < 0 || x >= viewportWidth || y >= viewportHeight) continue;
        sampled = true;
        if (hitReaches(el, x, y)) return false;
      }
    }
    return sampled;
  }

  // 画面に出ていない欄は、攻撃者が同じ form に仕込んだ収集用の隠し欄である可能性が高い。
  // ユーザーが自分で見て確認できる欄だけを埋める。
  function isVisibleField(el) {
    if (!el.isConnected) return false;
    const style = getComputedStyle(el);
    if (style.display === 'none') return false;
    // visibility は継承するため、祖先で隠された場合もここで弾ける。
    if (style.visibility === 'hidden' || style.visibility === 'collapse') return false;
    if (hasInvisibleText(style)) return false;
    if (isInsideHiddenWrapper(el)) return false;
    const isFixed = style.position === 'fixed';
    // position:fixed は offsetParent が null になるため、判定から除く。
    if (el.offsetParent === null && !isFixed) return false;
    const rect = el.getBoundingClientRect();
    if (rect.width < MIN_VISIBLE_SIZE || rect.height < MIN_VISIBLE_SIZE) return false;
    if (isClippedByAncestor(el, rect)) return false;
    if (isOccluded(el, rect)) return false;
    if (isFixed) {
      // 固定配置はスクロールしても位置が変わらないため、ビューポートと交差する分だけが見える範囲になる。
      // 端に 0.1px だけかかった欄を通さないよう、交差後の矩形にも最小サイズを課す。
      const viewportWidth = window.innerWidth || document.documentElement.clientWidth;
      const viewportHeight = window.innerHeight || document.documentElement.clientHeight;
      const visibleWidth = Math.min(rect.right, viewportWidth) - Math.max(rect.left, 0);
      const visibleHeight = Math.min(rect.bottom, viewportHeight) - Math.max(rect.top, 0);
      return visibleWidth >= MIN_VISIBLE_SIZE && visibleHeight >= MIN_VISIBLE_SIZE;
    }
    // 通常フローの欄はページ座標で判定する。スクロールしないと見えない欄は正当なフォームでも
    // 普通にあるため除外せず、left:-9999px のようにページの外へ追い出された欄だけを弾く。
    // ページの原点より手前はスクロールで到達できないため、こちらも見える分に最小サイズを課す。
    return rect.right + window.scrollX >= MIN_VISIBLE_SIZE &&
      rect.bottom + window.scrollY >= MIN_VISIBLE_SIZE;
  }

  function isEmailField(el) {
    const type = (el.getAttribute('type') || '').toLowerCase();
    if (type === 'email') return true;
    const tokens = fieldTokens(el);
    return EMAIL_TOKENS.some(function (token) {
      return tokens.indexOf(token) !== -1;
    });
  }

  function autocompleteTokens(el) {
    return tokenize(el.getAttribute('autocomplete'));
  }

  function identityTokens(el) {
    return tokenize(el.id).concat(tokenize(el.getAttribute('name')));
  }

  function hasAnyToken(tokens, candidates) {
    return candidates.some(function (token) {
      return tokens.indexOf(token) !== -1;
    });
  }

  function isNameField(el) {
    if (isEmailField(el)) return false;
    // autocomplete の name は氏名。id/name の素の "name" は userName に誤爆するため対象外。
    const nameAutocomplete = FAMILY_NAME_TOKENS
      .concat(GIVEN_NAME_TOKENS)
      .concat(ADDITIONAL_NAME_TOKENS)
      .concat(FULL_NAME_TOKENS);
    if (hasAnyToken(autocompleteTokens(el), nameAutocomplete)) return true;
    const nameIdAliases = FAMILY_NAME_TOKENS
      .concat(GIVEN_NAME_TOKENS)
      .concat(ADDITIONAL_NAME_TOKENS);
    return hasAnyToken(identityTokens(el), nameIdAliases);
  }

  function isAddressField(el) {
    if (isEmailField(el)) return false;
    const auto = autocompleteTokens(el);
    const identity = identityTokens(el);
    for (let i = 0; i < FIELD_MAP.length; i++) {
      if (FIELD_MAP[i].key === 'email') continue;
      if (hasAnyToken(auto, FIELD_MAP[i].tokens)) return true;
      // id/name の素の "name" は userName に誤爆するため対象外。
      if (FIELD_MAP[i].key === 'name') continue;
      if (hasAnyToken(identity, FIELD_MAP[i].tokens)) return true;
    }
    return false;
  }

  function resolveValue(el, address) {
    const tokens = fieldTokens(el);
    for (let i = 0; i < FIELD_MAP.length; i++) {
      const entry = FIELD_MAP[i];
      for (let j = 0; j < entry.tokens.length; j++) {
        if (tokens.indexOf(entry.tokens[j]) !== -1) {
          if (!entry.key) return null;
          const value = address[entry.key];
          return value == null ? '' : String(value);
        }
      }
    }
    return null;
  }

  function setFieldValue(el, value) {
    const proto = el instanceof HTMLTextAreaElement
      ? HTMLTextAreaElement.prototype
      : el instanceof HTMLSelectElement
        ? HTMLSelectElement.prototype
        : HTMLInputElement.prototype;
    const descriptor = Object.getOwnPropertyDescriptor(proto, 'value');
    if (descriptor && descriptor.set) {
      descriptor.set.call(el, value);
    } else {
      el.value = value;
    }
    el.dispatchEvent(new Event('input', { bubbles: true, composed: true }));
    el.dispatchEvent(new Event('change', { bubbles: true, composed: true }));
  }

  function collectFields(root, out) {
    const nodes = root.querySelectorAll('input, textarea, select');
    for (let i = 0; i < nodes.length; i++) {
      out.push(nodes[i]);
    }
    const all = root.querySelectorAll('*');
    for (let i = 0; i < all.length; i++) {
      if (all[i].shadowRoot) {
        collectFields(all[i].shadowRoot, out);
      }
    }
  }

  let focusedFillRoot = null;

  function resolveFillRoot(el) {
    if (!el || !el.isConnected) return null;
    if (el.form) return el.form;
    const closest = el.closest && el.closest('form');
    if (closest) return closest;
    return el;
  }

  function belongsToForm(el) {
    if (!el) return false;
    if (el.form) return true;
    return !!(el.closest && el.closest('form'));
  }

  function collectFillTargets(root) {
    const fields = [];
    if (!root) return fields;
    if (root.tagName && String(root.tagName).toUpperCase() === 'FORM') {
      // 配送先と請求先など、無関係な form へ同じ値を書かない。
      collectFields(root, fields);
      return fields;
    }
    // form がないページ（MDN autocomplete など）は、どの form にも属さない欄を埋める。
    collectFields(document, fields);
    const scoped = [];
    for (let i = 0; i < fields.length; i++) {
      if (!belongsToForm(fields[i])) {
        scoped.push(fields[i]);
      }
    }
    return scoped;
  }

  function fillAddress(address, mode) {
    if (!address) return 0;
    const fillMode = mode === 'email' ? 'email' : 'address';
    const root = focusedFillRoot && focusedFillRoot.isConnected ? focusedFillRoot : null;
    if (!root) return 0;
    const fields = collectFillTargets(root);
    let filled = 0;
    for (let i = 0; i < fields.length; i++) {
      const el = fields[i];
      if (isNonValueField(el)) continue;
      if (!isVisibleField(el)) continue;
      if (fillMode === 'email') {
        if (!isEmailField(el)) continue;
      } else {
        if (isEmailField(el)) continue;
        if (hasAutocompleteOff(el)) continue;
      }
      const value = fillMode === 'email' ? (address.email || '') : resolveValue(el, address);
      if (!value) continue;
      setFieldValue(el, value);
      filled += 1;
    }
    console.log('address-autofill: mode=' + fillMode + ' filled=' + filled + ' href=' + location.href);
    return filled;
  }

  const port = browser.runtime.connectNative('addressAutofillBridge');
  port.onMessage.addListener(function (message) {
    if (!message || message.action !== 'fill') return;
    fillAddress(message.address, message.mode);
  });

  function isEditableFormControl(el) {
    if (!el || !el.tagName) return false;
    const tag = String(el.tagName).toUpperCase();
    return tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT';
  }

  document.addEventListener('focusin', function (event) {
    const el = event.target;
    if (!isEditableFormControl(el)) return;
    let kind = 'other';
    if (isEmailField(el)) kind = 'email';
    else if (isNameField(el)) kind = 'name';
    else if (isAddressField(el)) kind = 'address';
    focusedFillRoot = resolveFillRoot(el);
    port.postMessage({ action: 'field-focus', kind: kind });
  }, true);

  // ページの空領域タップでは次の focusin が来ないため、入力欄の focusout で閉じる。
  // 次が別の input なら focusin 側に任せる。バータップ時は relatedTarget が input ではない。
  document.addEventListener('focusout', function (event) {
    const el = event.target;
    if (!isEditableFormControl(el)) return;
    const next = event.relatedTarget;
    if (isEditableFormControl(next)) return;
    port.postMessage({ action: 'field-blur' });
  }, true);
})();
