(function () {
  if (window !== window.top) return;
  const visualViewport = window.visualViewport;
  if (!visualViewport) return;

  let lastScale = -1;

  function send() {
    const scale = visualViewport.scale;
    if (scale === lastScale) return;
    lastScale = scale;
    browser.runtime
      .sendNativeMessage("viewportScaleBridge", { scale })
      .catch(function () {});
  }

  send();
  visualViewport.addEventListener("resize", send);
  visualViewport.addEventListener("scroll", send);
})();
