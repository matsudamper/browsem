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
  const STREET_TOKENS = [
    'street-address',
    'streetaddress',
    'address-line1',
    'address-line2',
    'address-line3',
  ];
  const ADDRESS_LEVEL1_TOKENS = ['address-level1', 'addresslevel1', 'state', 'province'];
  const ADDRESS_LEVEL2_TOKENS = ['address-level2', 'addresslevel2', 'city'];
  const ADDRESS_LEVEL3_TOKENS = ['address-level3', 'addresslevel3'];
  const POSTAL_TOKENS = ['postal-code', 'postalcode', 'zip', 'zipcode', 'postcode'];
  const COUNTRY_TOKENS = ['country', 'country-name', 'countryname'];
  const TEL_TOKENS = ['tel', 'telephone', 'phone'];
  const EMAIL_TOKENS = ['email'];

  const FIELD_MAP = [
    { tokens: FAMILY_NAME_TOKENS, key: 'familyName' },
    { tokens: GIVEN_NAME_TOKENS, key: 'givenName' },
    { tokens: ADDITIONAL_NAME_TOKENS, key: 'additionalName' },
    { tokens: FULL_NAME_TOKENS, key: 'name' },
    { tokens: ORGANIZATION_TOKENS, key: 'organization' },
    { tokens: STREET_TOKENS, key: 'streetAddress' },
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

  function fillAddress(address, mode) {
    if (!address) return 0;
    const fillMode = mode === 'email' ? 'email' : 'address';
    const fields = [];
    collectFields(document, fields);
    let filled = 0;
    for (let i = 0; i < fields.length; i++) {
      const el = fields[i];
      if (isNonValueField(el)) continue;
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

  document.addEventListener('focusin', function (event) {
    const el = event.target;
    if (!el || !el.tagName) return;
    const tag = String(el.tagName).toUpperCase();
    if (tag !== 'INPUT' && tag !== 'TEXTAREA' && tag !== 'SELECT') return;
    let kind = 'other';
    if (isEmailField(el)) kind = 'email';
    else if (isNameField(el)) kind = 'name';
    else if (isAddressField(el)) kind = 'address';
    port.postMessage({ action: 'field-focus', kind: kind });
  }, true);
})();
