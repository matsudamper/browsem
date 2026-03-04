(function () {
  const sendThemeColor = () => {
    const meta = document.querySelector('meta[name="theme-color"]');
    const color = meta ? (meta.getAttribute('content') || '').trim() : '';
    browser.runtime.sendNativeMessage('theme-color', { color });
  };

  const observer = new MutationObserver(sendThemeColor);
  observer.observe(document.documentElement, {
    subtree: true,
    childList: true,
    attributes: true,
    attributeFilter: ['content', 'name']
  });

  sendThemeColor();
})();
