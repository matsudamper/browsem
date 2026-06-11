// X (x.com / twitter.com) の画像ビューアーで二本指ピンチズームが
// ほとんど効かない問題の修正。
//
// 上流バグ (Firefox for Android 本体でも未修正):
// - https://github.com/webcompat/web-bugs/issues/196790
// - https://bugzilla.mozilla.org/show_bug.cgi?id=2007555
//
// Gecko では X のビューアーの二本指ジェスチャーがサイト JS で正しく
// 処理されず、マルチタッチを preventDefault して APZ の横取りを防ぐ
// だけでは直らないことを実機で確認済み。そのためビューアー内の
// ピンチ操作は本スクリプトがイベントを横取りし、自前の CSS transform で
// ズーム・パンを行い、X 自身の (Gecko 上で壊れている) ジェスチャー処理
// には渡さない。
//
// 動作ルール:
// - 倍率 1 のときのシングルタッチは素通し。X のスワイプ・ダブルタップ・
//   閉じる操作はそのまま動く
// - 2 本指ピンチと、ズーム中 (倍率 > 1) のシングルタッチ (パン) は消費する
// - touch イベントは preventDefault して APZ にコンテンツ消費を伝え、
//   ジェスチャーの横取り (touchcancel) を防ぐ。pointer イベントは
//   stopImmediatePropagation で X 側のハンドラーから隠す
(function () {
  "use strict";
  if (window !== window.top) return;

  // 画像・動画ビューアーは /status/<id>/photo/<n>・/<user>/photo 等への
  // pushState で開かれる。DM 等 URL が変わらないモーダルにも対応する
  const VIEWER_PATH = /\/(photo|video|header_photo)(\/\d+)?\/?$/;
  const MIN_SCALE = 1;
  const MAX_SCALE = 8;

  // ---- ズーム状態 ----
  let img = null;
  let scale = 1;
  let tx = 0;
  let ty = 0;
  let lastPathname = location.pathname;

  // ---- 進行中ジェスチャー ----
  const pointers = new Map(); // pointerId -> {x, y}
  let pinching = false;
  let panning = false;
  let prev = null; // 直前の基準点 {d, mx, my} または {x, y}

  function isViewerContext(target) {
    if (VIEWER_PATH.test(location.pathname)) return true;
    const modal = target instanceof Element ? target.closest('[aria-modal="true"]') : null;
    return modal !== null && modal.querySelector('img[src*="twimg.com"]') !== null;
  }

  // 表示中のビューアー画像 (カルーセル内で画面中央に最も近いもの) を探す
  function findViewerImage(target) {
    const modal = target instanceof Element ? target.closest('[aria-modal="true"]') : null;
    const root = modal || document;
    const cx = window.innerWidth / 2;
    const cy = window.innerHeight / 2;
    let best = null;
    let bestDist = Infinity;
    for (const candidate of root.querySelectorAll('img[src*="twimg.com"]')) {
      const rect = candidate.getBoundingClientRect();
      if (rect.width < 64 || rect.height < 64) continue; // アイコン類を除外
      const dx = (rect.left + rect.right) / 2 - cx;
      const dy = (rect.top + rect.bottom) / 2 - cy;
      const dist = dx * dx + dy * dy;
      if (dist < bestDist) {
        bestDist = dist;
        best = candidate;
      }
    }
    return best;
  }

  // 初回ジェスチャーが APZ に横取りされても次回以降は全量届くように、
  // スクロールしない要素に限って touch-action: none を付与する
  function setTouchActionNone(el) {
    if (!(el instanceof Element)) return;
    if (el.scrollHeight - el.clientHeight > 1) return;
    if (el.scrollWidth - el.clientWidth > 1) return;
    el.style.touchAction = "none";
  }

  function applyTransform() {
    img.style.transformOrigin = "0 0";
    img.style.transform = "translate(" + tx + "px," + ty + "px) scale(" + scale + ")";
  }

  function resetZoom() {
    if (img) {
      img.style.transform = "";
      img.style.transformOrigin = "";
    }
    img = null;
    scale = 1;
    tx = 0;
    ty = 0;
    pinching = false;
    panning = false;
    prev = null;
  }

  // 軸ごとにパン量を制限する。ビューポートに収まる軸は中央固定、
  // はみ出す軸は端がビューポート内側に入らない範囲に制限する
  function clampAxis(layoutPos, layoutSize, viewportSize, t) {
    const renderedSize = layoutSize * scale;
    if (renderedSize <= viewportSize) {
      return (viewportSize - renderedSize) / 2 - layoutPos;
    }
    const min = viewportSize - renderedSize - layoutPos;
    const max = -layoutPos;
    return Math.min(max, Math.max(min, t));
  }

  function clampTranslate() {
    const rect = img.getBoundingClientRect();
    // transform-origin 0 0 のため、現在の transform を引くとレイアウト位置になる
    tx = clampAxis(rect.left - tx, rect.width / scale, window.innerWidth, tx);
    ty = clampAxis(rect.top - ty, rect.height / scale, window.innerHeight, ty);
  }

  function firstTwoPoints() {
    const iterator = pointers.values();
    return [iterator.next().value, iterator.next().value];
  }

  // ピンチの基準点を現在の指の位置で取り直す (指の増減時のジャンプ防止)
  function primePinch() {
    const [p0, p1] = firstTwoPoints();
    prev = {
      d: Math.hypot(p1.x - p0.x, p1.y - p0.y),
      mx: (p0.x + p1.x) / 2,
      my: (p0.y + p1.y) / 2,
    };
  }

  function handlePinchMove() {
    const [p0, p1] = firstTwoPoints();
    const d = Math.hypot(p1.x - p0.x, p1.y - p0.y);
    const mx = (p0.x + p1.x) / 2;
    const my = (p0.y + p1.y) / 2;
    if (prev.d > 0 && d > 0) {
      const newScale = Math.min(MAX_SCALE, Math.max(MIN_SCALE, scale * (d / prev.d)));
      const k = newScale / scale;
      // 前回の中点を現在の中点へ写し、k 倍する (client 座標系での増分更新)
      const rect = img.getBoundingClientRect();
      const layoutX = rect.left - tx;
      const layoutY = rect.top - ty;
      tx = mx + k * (rect.left - prev.mx) - layoutX;
      ty = my + k * (rect.top - prev.my) - layoutY;
      scale = newScale;
      clampTranslate();
      applyTransform();
    }
    prev = { d: d, mx: mx, my: my };
  }

  function handlePanMove(point) {
    tx += point.x - prev.x;
    ty += point.y - prev.y;
    prev = { x: point.x, y: point.y };
    clampTranslate();
    applyTransform();
  }

  function swallow(event) {
    event.stopImmediatePropagation();
  }

  function onPointerDown(event) {
    if (location.pathname !== lastPathname) {
      lastPathname = location.pathname;
      resetZoom();
    }
    if (!isViewerContext(event.target)) {
      if (img) resetZoom();
      pointers.clear();
      return;
    }
    pointers.set(event.pointerId, { x: event.clientX, y: event.clientY });
    if (pointers.size === 2) {
      if (!img || !img.isConnected) {
        const found = findViewerImage(event.target);
        if (!found) return;
        img = found;
      }
      setTouchActionNone(event.target);
      setTouchActionNone(img);
      pinching = true;
      panning = false;
      primePinch();
      swallow(event);
    } else if (pointers.size === 1 && scale > 1.01 && img && img.isConnected) {
      panning = true;
      prev = { x: event.clientX, y: event.clientY };
      swallow(event);
    }
  }

  function onPointerMove(event) {
    if (!pointers.has(event.pointerId)) return;
    pointers.set(event.pointerId, { x: event.clientX, y: event.clientY });
    if (img && !img.isConnected) {
      resetZoom();
      return;
    }
    if (pinching && pointers.size >= 2) {
      handlePinchMove();
      swallow(event);
    } else if (panning && pointers.size === 1) {
      handlePanMove(pointers.get(event.pointerId));
      swallow(event);
    }
  }

  // up / cancel は X 側にも届かせ、ジェスチャー終了を認識させる
  function onPointerUp(event) {
    if (!pointers.has(event.pointerId)) return;
    pointers.delete(event.pointerId);
    if (pinching) {
      if (pointers.size >= 2) {
        primePinch();
      } else if (pointers.size === 1 && scale > 1.01) {
        pinching = false;
        panning = true;
        const remaining = pointers.values().next().value;
        prev = { x: remaining.x, y: remaining.y };
      } else {
        pinching = false;
      }
    } else if (panning && pointers.size === 0) {
      panning = false;
    }
    if (pointers.size === 0 && scale <= 1.02 && img) {
      resetZoom();
    }
  }

  // ジェスチャー消費中の touch イベントは preventDefault で APZ に
  // コンテンツ消費を伝えつつ、X 側のハンドラーからも隠す
  function onTouchStartOrMove(event) {
    if (!pinching && !panning) return;
    if (event.cancelable) event.preventDefault();
    event.stopImmediatePropagation();
  }

  const captureOptions = { capture: true, passive: false };
  window.addEventListener("pointerdown", onPointerDown, captureOptions);
  window.addEventListener("pointermove", onPointerMove, captureOptions);
  window.addEventListener("pointerup", onPointerUp, captureOptions);
  window.addEventListener("pointercancel", onPointerUp, captureOptions);
  window.addEventListener("touchstart", onTouchStartOrMove, captureOptions);
  window.addEventListener("touchmove", onTouchStartOrMove, captureOptions);
  window.addEventListener("popstate", function () {
    if (location.pathname !== lastPathname) {
      lastPathname = location.pathname;
      resetZoom();
    }
  });
})();
