// X (x.com / twitter.com) の画像・動画ビューアーで二本指ピンチズームが
// ほとんど効かない問題の修正 (Bugzilla 2007555 / webcompat#196790)。
//
// Gecko の APZ は、touch-action がピンチズームを許可していなくても
// 二本指ジェスチャーを横取りし、コンテンツへのイベント配送を
// 止めてしまう (Bugzilla 1648491 / 1663731 と同系列)。このため X 自身の
// JS ピンチズーム実装にはジェスチャー序盤のわずかな移動しか届かず、
// 「数 px しか拡大しない」「移動量が 1/10 になる」症状になる。
//
// コンテンツ側がマルチタッチの touchstart / touchmove を preventDefault
// すると APZ はジェスチャーから手を引き、以降のイベントが全量コンテンツへ
// 届くようになる (Chrome と同じ挙動)。伝播は止めないので X 自身の
// ハンドラーはそのまま動作する。
(function () {
  if (window !== window.top) return;

  // 画像・動画ビューアー (ライトボックス) は
  // /status/<id>/photo/<n>・/video/<n>・/<user>/photo 等への pushState で開かれる
  const VIEWER_PATH = /\/(photo|video|header_photo)(\/\d+)?\/?$/;

  function isMediaViewerOpen(target) {
    if (VIEWER_PATH.test(location.pathname)) return true;
    // DM 内の画像ビューアー等、URL が変わらないモーダルへのフォールバック
    return target instanceof Element && target.closest('[aria-modal="true"]') !== null;
  }

  function onMultiTouch(event) {
    if (event.touches.length < 2) return;
    if (!isMediaViewerOpen(event.target)) return;
    event.preventDefault();
  }

  const options = { capture: true, passive: false };
  window.addEventListener("touchstart", onMultiTouch, options);
  window.addEventListener("touchmove", onMultiTouch, options);
})();
