// Twitter/X の共有リンク・共有ボタンのクリックを捕捉し、
// OS の共有シートで共有するためのコンテンツスクリプト。
// - <a href="https://twitter.com/intent/tweet?..."> のようなリンククリック
// - 公式ウィジェット等が window.open で開く共有ポップアップ
// の両方を捕捉し、共有内容をネイティブアプリへ送信する。
// 公式共有ボタンは platform.twitter.com の iframe 内で window.open を呼ぶため、
// all_frames かつ document_start で各フレームに注入して確実に横取りする。
(function () {
  "use strict";

  // 共有インテントを表すホスト
  const SHARE_HOSTS = [
    "twitter.com",
    "www.twitter.com",
    "mobile.twitter.com",
    "x.com",
    "www.x.com",
    "mobile.x.com",
  ];
  // 共有インテントを表すパス（末尾スラッシュは除去して比較）
  const SHARE_PATHS = ["/intent/tweet", "/intent/post", "/share"];

  // URL が Twitter/X の共有インテントなら共有内容を、そうでなければ null を返す
  function parseShareUrl(rawUrl) {
    let url;
    try {
      url = new URL(rawUrl, location.href);
    } catch (e) {
      return null;
    }
    const host = url.hostname.toLowerCase();
    if (SHARE_HOSTS.indexOf(host) === -1) return null;
    const path = url.pathname.replace(/\/+$/, "") || "/";
    if (SHARE_PATHS.indexOf(path) === -1) return null;
    const params = url.searchParams;
    return {
      text: params.get("text") || "",
      url: params.get("url") || "",
      hashtags: params.get("hashtags") || "",
      via: params.get("via") || "",
    };
  }

  function sendShare(data) {
    browser.runtime
      .sendNativeMessage("twitterShareBridge", {
        text: data.text,
        url: data.url,
        hashtags: data.hashtags,
        via: data.via,
      })
      .catch(function (e) {
        console.error("[TwitterShare] sendNativeMessage error: " + e);
      });
  }

  // クリックによる共有リンク（<a href>）を捕捉する
  document.addEventListener(
    "click",
    function (event) {
      let el = event.target;
      while (el && el.nodeType === 1) {
        if (el.tagName === "A" && el.href) {
          const data = parseShareUrl(el.href);
          if (data) {
            // Twitter への遷移を止めて OS の共有シートへ振り替える
            event.preventDefault();
            event.stopPropagation();
            sendShare(data);
          }
          return;
        }
        el = el.parentElement;
      }
    },
    true,
  );

  // window.open による共有ポップアップ（公式ボタン等）を捕捉する
  const originalOpen = window.open;
  window.open = function (url) {
    if (url) {
      const data = parseShareUrl(url);
      if (data) {
        sendShare(data);
        // ポップアップは開かず、開いた体で null を返す
        return null;
      }
    }
    return originalOpen.apply(window, arguments);
  };
})();
