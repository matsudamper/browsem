(function () {
  if (window !== window.top) return;
  var vv = window.visualViewport;
  if (!vv) return;

  var lastScale = -1;

  function send() {
    var s = vv.scale;
    if (s === lastScale) return;
    lastScale = s;
    browser.runtime
      .sendNativeMessage("viewportScaleBridge", { scale: s })
      .catch(function () {});
  }

  send();
  vv.addEventListener("resize", send);
  vv.addEventListener("scroll", send);
})();
