"use strict";

const TAG = "[WRProbe]";

console.log(TAG, "background script start");

try {
  if (typeof browser === "undefined" && typeof chrome === "undefined") {
    console.log(TAG, "ERROR: neither browser nor chrome is defined");
  }
} catch (e) {
  console.log(TAG, "guard threw", String(e));
}

const api = (typeof browser !== "undefined") ? browser : chrome;

if (!api || !api.webRequest) {
  console.log(TAG, "ERROR: webRequest API unavailable", JSON.stringify({
    hasBrowser: typeof browser !== "undefined",
    hasChrome: typeof chrome !== "undefined",
    hasApi: !!api,
    hasWebRequest: !!(api && api.webRequest),
  }));
} else {
  console.log(TAG, "webRequest available, registering listener");

  let count = 0;
  api.webRequest.onBeforeRequest.addListener(
    function (details) {
      count++;
      if (count <= 30) {
        console.log(TAG, "onBeforeRequest", count, details.type, details.tabId, details.method, details.url);
      } else if (count === 31) {
        console.log(TAG, "...further onBeforeRequest events suppressed to reduce log volume");
      }
      // 何もブロックしない。観測専用。
      return {};
    },
    { urls: ["<all_urls>"] },
    ["blocking"],
  );

  api.webRequest.onErrorOccurred.addListener(
    function (details) {
      console.log(TAG, "onErrorOccurred", details.type, details.url, details.error);
    },
    { urls: ["<all_urls>"] },
  );

  console.log(TAG, "listeners registered");
}
