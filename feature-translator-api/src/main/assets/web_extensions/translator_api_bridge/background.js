// 翻訳プロバイダが Gecko のとき、content script からの要求を experiment API へ中継する。
// experiment API は content script から呼べないため、バックグラウンドを経由する。
"use strict";

browser.runtime.onMessage.addListener(function (message) {
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
