// Runs in every page and every frame inside it, before the page's own scripts (see BrowserActivity).
// Video players embedded in frames often refuse to play ("sandboxed iframe", "disable your popup
// blocker") when the page around them adds a sandbox or when window.open doesn't return a window.
// This keeps frames unsandboxed and gives pop-ups a harmless stand-in, so the player carries on
// while nothing actually opens.
(function () {
  if (window.__beeshtv) return;
  window.__beeshtv = true;

  // 1. Never sandbox a frame: drop the attribute however it's set, before the frame loads.
  var setAttr = Element.prototype.setAttribute;
  Element.prototype.setAttribute = function (name, value) {
    if (this instanceof HTMLIFrameElement && String(name).toLowerCase() === 'sandbox') return;
    return setAttr.apply(this, arguments);
  };
  try {
    Object.defineProperty(HTMLIFrameElement.prototype, 'sandbox', {
      configurable: true,
      get: function () { return document.createElement('span').classList; },
      set: function () {}
    });
  } catch (e) {}
  function unsandbox(f) {
    if (!f.hasAttribute('sandbox')) return;
    f.removeAttribute('sandbox');
    // A frame keeps the sandbox it loaded with, so load it again without one.
    if (f.src) { var src = f.src; f.src = 'about:blank'; f.src = src; }
  }
  new MutationObserver(function (changes) {
    changes.forEach(function (c) {
      if (c.type === 'attributes') { if (c.target instanceof HTMLIFrameElement) unsandbox(c.target); return; }
      c.addedNodes.forEach(function (n) {
        if (n instanceof HTMLIFrameElement) unsandbox(n);
        else if (n.querySelectorAll) n.querySelectorAll('iframe[sandbox]').forEach(unsandbox);
      });
    });
  }).observe(document, { childList: true, subtree: true, attributes: true, attributeFilter: ['sandbox'] });

  // 2. Pop-ups: when blocking is on (BrowserActivity sets __beeshtvBlockPopups), window.open returns a
  //    stand-in window instead of opening one, so pages that check for a blocker don't stop.
  if (window.__beeshtvBlockPopups) {
    var standIn = {
      closed: false, opener: window, name: '',
      close: function () { this.closed = true; }, focus: function () {}, blur: function () {},
      postMessage: function () {}, addEventListener: function () {}, removeEventListener: function () {},
      location: { href: 'about:blank', assign: function () {}, replace: function () {}, reload: function () {} },
      document: { write: function () {}, writeln: function () {}, open: function () {}, close: function () {},
                  body: null, title: '' }
    };
    window.open = function () { return standIn; };
  }

  // 3. Read as a regular browser to players that check.
  try { Object.defineProperty(navigator, 'webdriver', { get: function () { return false; } }); } catch (e) {}
})();
