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
        // A live list, cheap when there are no frames inside (most added nodes).
        else if (n.getElementsByTagName) { var fs = n.getElementsByTagName('iframe'); for (var i = 0; i < fs.length; i++) unsandbox(fs[i]); }
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

  // 3. A text field took focus (in any frame): BeeshTV shows its own keyboard, since the TV's can't
  //    attach to the page. Leaving the field puts it away.
  function typable(el) {
    if (!el) return false;
    if (el.isContentEditable) return true;
    if (el.tagName === 'TEXTAREA') return !el.readOnly && !el.disabled;
    if (el.tagName !== 'INPUT' || el.readOnly || el.disabled) return false;
    return !/^(button|checkbox|color|file|hidden|image|radio|range|reset|submit)$/i.test(el.type || '');
  }
  if (window.BeeshTVInput) {
    document.addEventListener('focusin', function (e) { if (typable(e.target)) BeeshTVInput.focus(); }, true);
    document.addEventListener('focusout', function (e) { if (typable(e.target)) BeeshTVInput.blur(); }, true);
  }

  // 4. Blocked hosts fail the way they do with any ad blocker: the browser decides (adblock lists,
  //    DNS protection, and sites where ads are allowed). Requests the page makes from script would
  //    otherwise "succeed" with the browser's empty answer.
  if (window.BeeshTVInput && BeeshTVInput.blocks) {
    var blocked = function (url) {
      try { return BeeshTVInput.blocks(new URL(url, location.href).href); } catch (e) { return false; }
    };
    var realFetch = window.fetch;
    if (realFetch) window.fetch = function (input, init) {
      var url = typeof input === 'string' ? input : (input && input.url) || String(input);
      if (blocked(url)) return Promise.reject(new TypeError('Failed to fetch'));
      return realFetch.apply(this, arguments);
    };
    var open = XMLHttpRequest.prototype.open, send = XMLHttpRequest.prototype.send;
    XMLHttpRequest.prototype.open = function (method, url) {
      this.__beeshtvBlocked = blocked(url);
      return open.apply(this, arguments);
    };
    XMLHttpRequest.prototype.send = function () {
      if (!this.__beeshtvBlocked) return send.apply(this, arguments);
      var x = this;
      setTimeout(function () { x.dispatchEvent(new Event('error')); x.dispatchEvent(new Event('loadend')); }, 0);
    };
    if (navigator.sendBeacon) {
      var beacon = navigator.sendBeacon.bind(navigator);
      navigator.sendBeacon = function (url, data) { return blocked(url) ? false : beacon(url, data); };
    }
  }

  // 5. Lighter pages (BrowserActivity sets __beeshtvLight): a TV draws every page effect itself, every
  //    frame. Backdrop blur, the costliest, is switched off; and while a video plays, the page's looping
  //    decorations (spinners, carousels, pulses) are paused, and resume when it stops.
  if (window.__beeshtvLight) {
    var css = '*,*::before,*::after{-webkit-backdrop-filter:none!important;backdrop-filter:none!important}';
    var addStyle = function () {
      var st = document.createElement('style');
      st.textContent = css;
      (document.head || document.documentElement).appendChild(st);
    };
    if (document.documentElement) addStyle(); else document.addEventListener('DOMContentLoaded', addStyle);
    var paused = [];
    var anyPlaying = function () {
      var vs = document.getElementsByTagName('video');
      for (var i = 0; i < vs.length; i++) if (!vs[i].paused && !vs[i].ended) return true;
      return false;
    };
    var settle = function () {
      if (!document.getAnimations) return;
      if (anyPlaying()) {
        document.getAnimations().forEach(function (an) {
          var t = an.effect && an.effect.getTiming && an.effect.getTiming();
          if (t && t.iterations === Infinity && an.playState === 'running') { an.pause(); paused.push(an); }
        });
      } else {
        paused.forEach(function (an) { try { an.play(); } catch (e) {} });
        paused = [];
      }
    };
    ['playing', 'pause', 'ended'].forEach(function (ev) {
      document.addEventListener(ev, function () { setTimeout(settle, 50); }, true);
    });
    // Decorations added later while the video plays are caught too.
    setInterval(function () { if (paused.length || anyPlaying()) settle(); }, 3000);
  }

  // 6. Read as a regular browser to players that check.
  try { Object.defineProperty(navigator, 'webdriver', { get: function () { return false; } }); } catch (e) {}
})();
