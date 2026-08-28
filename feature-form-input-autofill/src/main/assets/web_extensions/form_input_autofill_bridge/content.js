// 住所・氏名・メール以外のフォーム欄について、ページ (host + path) 単位で
// 送信内容を保存し、フォーカス時に候補を出す。
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
  const EMAIL_TOKENS = ['email', 'e-mail'];
  const CREDIT_CARD_TOKENS = [
    'cc-number', 'ccnumber', 'cc-exp', 'ccexp', 'cc-exp-month', 'ccexpmonth',
    'cc-exp-year', 'ccexyear', 'cc-csc', 'cccsc', 'cc-name', 'ccname',
    'cc-type', 'cctype', 'cc', 'creditcard', 'credit-card',
  ];
  const GENERIC_SENSITIVE_IDENTIFIERS = ['name', 'address', 'fullname', 'full-name', 'full_name'];
  const PASSWORD_TOKENS = ['password', 'passwd', 'pwd', 'pass', 'current-password', 'new-password'];
  const ONE_TIME_CODE_TOKENS = ['one-time-code', 'onetimecode', 'otp', 'totp', 'sms-otp'];

  const ADDRESS_FIELD_MAP = [
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
      type === 'reset' || type === 'checkbox' || type === 'radio' ||
      type === 'password' || type === 'file';
  }

  function isDisabledField(el) {
    return Boolean(el.disabled);
  }

  function hasAutocompleteOff(el) {
    const tokens = autocompleteTokens(el);
    if (hasAnyToken(tokens, PASSWORD_TOKENS)) return true;
    const autocomplete = (el.getAttribute('autocomplete') || '').toLowerCase().trim();
    if (autocomplete === 'off') return true;
    const form = el.form;
    if (form) {
      const formAutocomplete = (form.getAttribute('autocomplete') || '').toLowerCase().trim();
      if (formAutocomplete === 'off') return true;
    }
    return false;
  }

  function isPasswordField(el) {
    const tokens = fieldTokens(el);
    return hasAnyToken(tokens, PASSWORD_TOKENS);
  }

  function isOneTimeCodeField(el) {
    const tokens = fieldTokens(el);
    return hasAnyToken(tokens, ONE_TIME_CODE_TOKENS);
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

  function isCreditCardField(el) {
    const auto = autocompleteTokens(el);
    const identity = identityTokens(el);
    return hasAnyToken(auto, CREDIT_CARD_TOKENS) || hasAnyToken(identity, CREDIT_CARD_TOKENS);
  }

  function isGenericSensitiveIdentity(el) {
    const id = (el.id || '').trim().toLowerCase();
    const name = (el.getAttribute('name') || '').trim().toLowerCase();
    return GENERIC_SENSITIVE_IDENTIFIERS.some(function (identifier) {
      return id === identifier || name === identifier;
    }) || hasAnyToken(identityTokens(el), GENERIC_SENSITIVE_IDENTIFIERS);
  }

  function isAddressField(el) {
    if (isEmailField(el)) return false;
    const auto = autocompleteTokens(el);
    const identity = identityTokens(el);
    for (let i = 0; i < ADDRESS_FIELD_MAP.length; i++) {
      if (ADDRESS_FIELD_MAP[i].key === 'email') continue;
      if (hasAnyToken(auto, ADDRESS_FIELD_MAP[i].tokens)) return true;
      if (ADDRESS_FIELD_MAP[i].key === 'name') continue;
      if (hasAnyToken(identity, ADDRESS_FIELD_MAP[i].tokens)) return true;
    }
    return false;
  }

  function isExcludedAutofillField(el) {
    return isEmailField(el) ||
      isNameField(el) ||
      isAddressField(el) ||
      isCreditCardField(el) ||
      isPasswordField(el) ||
      isOneTimeCodeField(el) ||
      isGenericSensitiveIdentity(el);
  }

  function isTargetField(el) {
    if (!el || !el.tagName) return false;
    const tag = String(el.tagName).toUpperCase();
    if (tag !== 'INPUT' && tag !== 'TEXTAREA' && tag !== 'SELECT') return false;
    if (isDisabledField(el)) return false;
    if (isNonValueField(el)) return false;
    if (hasAutocompleteOff(el)) return false;
    if (isExcludedAutofillField(el)) return false;
    return true;
  }

  function fieldKey(el) {
    const name = (el.getAttribute('name') || '').trim();
    if (name) return name;
    const id = (el.id || '').trim();
    if (id) return id;
    return '';
  }

  function fieldValue(el) {
    if (el instanceof HTMLSelectElement) {
      return el.value || '';
    }
    return String(el.value || '');
  }

  function isEmptyFieldValue(value) {
    return value.trim() === '';
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

  function resolveFillRoot(el) {
    if (!el || !el.isConnected) return null;
    if (el.form) return el.form;
    const closest = el.closest && el.closest('form');
    if (closest) return closest;
    return el;
  }

  function collectFormFields(form) {
    const fields = [];
    if (!form) return fields;
    if (form.elements) {
      for (let i = 0; i < form.elements.length; i++) {
        const el = form.elements[i];
        if (isEditableFormControl(el)) {
          fields.push(el);
        }
      }
    }
    collectFields(form, fields);
    const seen = new Set();
    const result = [];
    for (let i = 0; i < fields.length; i++) {
      const el = fields[i];
      if (seen.has(el)) continue;
      seen.add(el);
      if (!isTargetField(el)) continue;
      const key = fieldKey(el);
      if (!key) continue;
      const value = fieldValue(el);
      if (isEmptyFieldValue(value)) continue;
      result.push({ fieldKey: key, value: value });
    }
    return result;
  }

  function pagePath() {
    const path = location.pathname || '';
    if (!path || path === '/') return '';
    return path;
  }

  let focusedFieldKey = '';
  let focusedElement = null;

  const port = browser.runtime.connectNative('formInputAutofillBridge');
  port.onMessage.addListener(function (message) {
    if (!message || message.action !== 'fill') return;
    const el = focusedElement;
    if (!el || !el.isConnected) return;
    const key = fieldKey(el);
    if (!key || key !== message.fieldKey) return;
    setFieldValue(el, message.value || '');
  });

  function isEditableFormControl(el) {
    if (!el || !el.tagName) return false;
    const tag = String(el.tagName).toUpperCase();
    return tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT';
  }

  function eventTargetElement(event) {
    const path = event.composedPath();
    for (let i = 0; i < path.length; i++) {
      const node = path[i];
      if (node instanceof Element && isEditableFormControl(node)) {
        return node;
      }
    }
    return event.target;
  }

  function deepActiveElement() {
    let el = document.activeElement;
    while (el && el.shadowRoot && el.shadowRoot.activeElement) {
      el = el.shadowRoot.activeElement;
    }
    return el;
  }

  document.addEventListener('focusin', function (event) {
    const el = eventTargetElement(event);
    if (!isEditableFormControl(el) || !isTargetField(el)) return;
    const key = fieldKey(el);
    if (!key) return;
    focusedFieldKey = key;
    focusedElement = el;
    port.postMessage({
      action: 'field-focus',
      fieldKey: key,
      pageUrl: location.href,
    });
  }, true);

  document.addEventListener('focusout', function (event) {
    const el = eventTargetElement(event);
    if (!isEditableFormControl(el)) return;
    const leavingEl = el;
    setTimeout(function () {
      const active = deepActiveElement();
      if (isEditableFormControl(active) && isTargetField(active)) return;
      if (focusedElement === leavingEl) {
        focusedElement = null;
        focusedFieldKey = '';
      }
      port.postMessage({ action: 'field-blur' });
    }, 0);
  }, true);

  function postFormSubmit(form) {
    const fields = collectFormFields(form);
    if (fields.length === 0) return;
    port.postMessage({
      action: 'form-submit',
      pageUrl: location.href,
      fields: fields,
    });
  }

  document.addEventListener('formdata', function (event) {
    const form = event.target;
    if (!form || !form.tagName || String(form.tagName).toUpperCase() !== 'FORM') return;
    postFormSubmit(form);
  }, true);
})();
