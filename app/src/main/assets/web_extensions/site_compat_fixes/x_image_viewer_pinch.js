// X (x.com / twitter.com) の画像ビューアーで二本指ピンチズームが
// ほとんど効かない問題の修正。
//
// 上流バグ (Firefox for Android 本体でも未修正):
// - https://github.com/webcompat/web-bugs/issues/196790
// - https://bugzilla.mozilla.org/show_bug.cgi?id=2007555
//
// Gecko では X のビューアーの二本指ジェスチャーがサイト JS で正しく
// 処理されないため、ビューアー内のピンチ操作は本スクリプトがイベントを
// 横取りし、自前の CSS transform でズーム・パンを行う。
//
// 設計ルール (v2 でビューアーが操作不能になった反省を反映):
// - ズーム対象は「ピンチ中点の真下の要素スタック」から解決する。
//   解決できなければ一切介入しない (誤った要素への transform で
//   見た目が変わらないままイベントだけ消費する状態を作らない)
// - X (React) の再レンダリングで inline style が巻き戻されるため、
//   ズーム中は毎フレーム transform を再適用する
// - 対象画像が DOM から外れたら即座に状態を捨てて素通しに戻す
// - 倍率 1 のシングルタッチは常に素通しで、X のスワイプ・ダブルタップ・
//   閉じる操作はそのまま動く
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
  let rafId = 0;
  let lastPathname = location.pathname;

  // ---- 進行中ジェスチャー ----
  const pointers = new Map(); // pointerId -> {x, y}
  let pinching = false;
  let panning = false;
  let prev = null; // 直前の基準点 {d, mx, my} または {x, y}

  function isViewerContext(target) {
    if (VIEWER_PATH.test(location.pathname)) return true;
    const modal = target instanceof Element ? target.closest('[aria-modal="true"]') : null;
    return modal !== null;
  }

  function isZoomableImage(el) {
    if (!(el instanceof HTMLImageElement)) return false;
    const src = el.currentSrc || el.src || "";
    if (!src.includes("twimg.com")) return false;
    const rect = el.getBoundingClientRect();
    return rect.width >= 64 && rect.height >= 64;
  }

  // ピンチ中点の真下にあるビューアー画像を解決する。
  // elementsFromPoint は重なり順 (手前→奥) で返るため、オーバーレイの
  // 背後でも実際に表示されている画像を背景のタイムライン等より先に
  // 見つけられる
  function resolvePinchTarget(mx, my) {
    const stack = document.elementsFromPoint(mx, my);
    for (const el of stack) {
      if (isZoomableImage(el)) return el;
    }
    // 中点が画像の外 (レターボックス部分) の場合は、最前面要素の属する
    // モーダル内で中点に最も近い画像へフォールバックする
    const top = stack.length > 0 ? stack[0] : null;
    const modal = top instanceof Element ? top.closest('[aria-modal="true"]') : null;
    if (!modal) return null;
    let best = null;
    let bestDist = Infinity;
    for (const candidate of modal.querySelectorAll("img")) {
      if (!isZoomableImage(candidate)) continue;
      const rect = candidate.getBoundingClientRect();
      const dx = (rect.left + rect.right) / 2 - mx;
      const dy = (rect.top + rect.bottom) / 2 - my;
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

  // X (React) の再レンダリングが inline style を巻き戻すことがあるため、
  // ズームが解除されるまで毎フレーム transform を再適用し続ける
  function ensureTransformLoop() {
    if (rafId) return;
    rafId = requestAnimationFrame(function tick() {
      rafId = 0;
      if (!img) return;
      if (!img.isConnected) {
        resetZoom();
        return;
      }
      applyTransform();
      if (scale > 1.001 || pinching || panning) {
        rafId = requestAnimationFrame(tick);
      }
    });
  }

  function resetZoom() {
    if (rafId) {
      cancelAnimationFrame(rafId);
      rafId = 0;
    }
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

  function resetIfNavigated() {
    if (location.pathname !== lastPathname) {
      lastPathname = location.pathname;
      resetZoom();
    }
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
      ensureTransformLoop();
    }
    prev = { d: d, mx: mx, my: my };
  }

  function handlePanMove(point) {
    tx += point.x - prev.x;
    ty += point.y - prev.y;
    prev = { x: point.x, y: point.y };
    clampTranslate();
    applyTransform();
    ensureTransformLoop();
  }

  function swallow(event) {
    event.stopImmediatePropagation();
  }

  function onPointerDown(event) {
    resetIfNavigated();
    if (!isViewerContext(event.target)) {
      if (img) resetZoom();
      pointers.clear();
      return;
    }
    pointers.set(event.pointerId, { x: event.clientX, y: event.clientY });
    if (pointers.size === 2) {
      const [p0, p1] = firstTwoPoints();
      const mx = (p0.x + p1.x) / 2;
      const my = (p0.y + p1.y) / 2;
      if (!img || !img.isConnected) {
        const found = resolvePinchTarget(mx, my);
        if (!found) {
          // 対象を解決できないピンチには介入しない (素通し)
          return;
        }
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
    if (!pinching && !panning) return;
    if (!img || !img.isConnected) {
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
  window.addEventListener("popstate", resetIfNavigated);
})();
