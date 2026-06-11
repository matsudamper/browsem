// X (x.com / twitter.com) の画像ビューアーで二本指ピンチズームが
// ほとんど効かない問題の修正。
//
// 上流バグ (Firefox for Android 本体でも未修正):
// - https://github.com/webcompat/web-bugs/issues/196790
// - https://bugzilla.mozilla.org/show_bug.cgi?id=2007555
//
// 経緯:
// - v1: マルチタッチの preventDefault で APZ の横取りを防ぐ → 効果なし
//   (X は元々タッチを consume しており、その先の自前ピンチ処理が
//   Gecko 上で壊れているため)
// - v2/v3: コンテンツスクリプトによるピンチズーム自前実装 → X の
//   ビューアー DOM 構造に依存する対象解決が安定せず断念
// - v4 (現行): X によるタッチイベントのキャンセルを無効化し、
//   ブラウザネイティブ (APZ) のピンチズームに任せる。
//   本アプリは forceUserScalableEnabled(true) のため user-scalable=no でも
//   ネイティブズームが可能で、DOM 構造に依存しない
//
// 仕組み:
// 1. ビューアー表示中は Event.prototype.preventDefault を touch イベントに
//    限り no-op 化する。X がタッチを consume できなくなり、APZ が
//    ジェスチャーを処理できるようになる
// 2. touch-action CSS がピンチズームを禁止している場合に備え、
//    ビューアー表示中のみ全要素の touch-action を manipulation
//    (パン + ピンチズーム許可) に緩和する
//
// シングルタッチへの影響: ビューアーのレイヤーはスクロール不能
// (overflow: hidden) のため、キャンセル無効化してもブラウザ既定動作は
// 発生せず、X のスワイプ・ダブルタップ等の自前処理はそのまま動く。
(function () {
  "use strict";
  if (window !== window.top) return;

  // 画像・動画ビューアーは /status/<id>/photo/<n>・/<user>/photo 等への
  // pushState で開かれる
  const VIEWER_PATH = /\/(photo|video|header_photo)(\/\d+)?\/?$/;

  function isViewerOpen() {
    return VIEWER_PATH.test(location.pathname);
  }

  // --- ビューアー表示中のみ touch-action を緩和する ---
  const touchActionStyle = document.createElement("style");
  touchActionStyle.textContent =
    "body, body * { touch-action: manipulation !important; }";

  function syncTouchActionStyle() {
    const shouldEnable = isViewerOpen();
    if (shouldEnable && !touchActionStyle.isConnected) {
      (document.head || document.documentElement).appendChild(touchActionStyle);
    } else if (!shouldEnable && touchActionStyle.isConnected) {
      touchActionStyle.remove();
    }
  }

  // URL は pushState で変わるため、ジェスチャー開始時と履歴移動時に同期する
  window.addEventListener("pointerdown", syncTouchActionStyle, {
    capture: true,
    passive: true,
  });
  window.addEventListener("popstate", syncTouchActionStyle);
  syncTouchActionStyle();

  // --- ビューアー表示中は touch イベントの preventDefault を無効化する ---
  try {
    const pageWindow = window.wrappedJSObject;
    const eventProto = pageWindow.Event.prototype;
    const originalPreventDefault = eventProto.preventDefault;
    eventProto.preventDefault = exportFunction(function () {
      const type = String(this.type || "");
      if (type.startsWith("touch") && VIEWER_PATH.test(location.pathname)) {
        return undefined;
      }
      return originalPreventDefault.call(this);
    }, pageWindow);
  } catch (_e) {
    // wrappedJSObject / exportFunction が使えない場合は touch-action の緩和のみ
  }

  // --- ビューアーのメイン画像を原寸 (name=orig) に差し替える ---
  // X は画面サイズ向けの縮小版 (name=small/medium/large) を表示するため、
  // ズームしてもぼやけたままになる。原寸版へ差し替えることで、ネイティブ
  // ズーム時に Gecko が原寸ファイルから再ラスタライズし鮮明に見える。
  const NAME_PARAM = /([?&]name=)[^&]+/;

  function toOrigUrl(src) {
    if (!src.includes("pbs.twimg.com/media/")) return null;
    if (/[?&]name=orig(&|$)/.test(src)) return null;
    if (NAME_PARAM.test(src)) {
      return src.replace(NAME_PARAM, "$1orig");
    }
    return src + (src.includes("?") ? "&" : "?") + "name=orig";
  }

  function upgradeViewerImages() {
    if (!isViewerOpen()) return;
    for (const img of document.querySelectorAll('img[src*="pbs.twimg.com/media/"]')) {
      const rect = img.getBoundingClientRect();
      // ビューアーのメイン画像のみ対象 (タイムラインのサムネイル等は除外)
      if (rect.width < window.innerWidth * 0.5 && rect.height < window.innerHeight * 0.5) {
        continue;
      }
      const origUrl = toOrigUrl(img.currentSrc || img.src);
      if (origUrl === null) continue;
      // 読み込み完了後に差し替えて、低解像度版の表示が消える瞬間を作らない
      const loader = new Image();
      loader.addEventListener("load", function () {
        if (img.isConnected) {
          img.removeAttribute("srcset");
          img.src = origUrl;
        }
      });
      loader.src = origUrl;
    }
  }

  // カルーセル移動や React の再レンダリングで src が戻されるため、
  // ビューアー表示中は DOM 変化を監視して再適用する
  let upgradeScheduled = false;
  const observer = new MutationObserver(function () {
    if (upgradeScheduled) return;
    upgradeScheduled = true;
    requestAnimationFrame(function () {
      upgradeScheduled = false;
      upgradeViewerImages();
    });
  });
  let observing = false;

  function syncImageUpgrade() {
    if (isViewerOpen()) {
      upgradeViewerImages();
      if (!observing && document.body) {
        observer.observe(document.body, {
          childList: true,
          subtree: true,
          attributes: true,
          attributeFilter: ["src"],
        });
        observing = true;
      }
    } else if (observing) {
      observer.disconnect();
      observing = false;
    }
  }

  window.addEventListener("pointerdown", syncImageUpgrade, {
    capture: true,
    passive: true,
  });
  window.addEventListener("popstate", syncImageUpgrade);
  syncImageUpgrade();
})();
