// content script からの要求を中継する。
// - 翻訳プロバイダが Gecko のとき: experiment API へ。experiment API は content script から呼べない
// - content script がネイティブアプリへ直接送れなかったとき: ネイティブアプリへ
"use strict";

const NATIVE_APP = "translatorApiBridge";

browser.runtime.onMessage.addListener(function (message) {
  if (message && message.relayToNativeApp) {
    return browser.runtime.sendNativeMessage(NATIVE_APP, message.relayToNativeApp);
  }
  switch (message && message.action) {
    case "availability":
      return browser.browsemTranslation.availability(message.sourceLanguage, message.targetLanguage);
    case "create":
      return browser.browsemTranslation.prepare(message.sourceLanguage, message.targetLanguage);
    case "translate":
      return browser.browsemTranslation.translate(
        message.sourceLanguage,
        message.targetLanguage,
        message.text,
      );
    case "languageDetectorAvailability":
      // Gecko の言語判定は同梱されており、常に使える
      return Promise.resolve({ success: true, availability: "available" });
    case "detectLanguage":
      return browser.browsemTranslation.detectLanguage(message.text);
    default:
      return undefined;
  }
});
