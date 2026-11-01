// Runs in every page and every frame inside it, before the page's own scripts (see BrowserActivity).
// Video players embedded in frames often refuse to play ("sandboxed iframe", "disable your popup
// blocker") when the page around them adds a sandbox or when window.open doesn't return a window.
// This keeps frames unsandboxed and gives pop-ups a harmless stand-in, so the player carries on
// while nothing actually opens.
(function () {
  if (window.__beeshtv) return;
  window.__beeshtv = true;
  // XML files (feeds, sitemaps, error replies) are shown as a tree of their own elements, which
  // anything added here would appear in. Web pages, XHTML and videos opened on their own still run.
  var type = document.contentType || '';
  if (/xml/.test(type) && !/xhtml/.test(type)) return;

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
    // Typing on our keyboard arrives as key presses, which make the engine draw its focus ring round
    // whatever has focus. A pointer needs none, as with a mouse; a site's own focus styles still win,
    // since this rule has no weight.
    var ring = document.createElement('style');
    ring.textContent = ':where(:focus-visible){outline:none}';
    var addRing = function () { (document.head || document.documentElement).appendChild(ring); };
    if (document.documentElement) addRing(); else document.addEventListener('DOMContentLoaded', addRing);
  }

  // 3b. A video playing (in any frame) keeps the TV's screen on; see BrowserActivity.stayAwake. Each
  //     frame speaks for itself, and repeats it while it plays in case it's removed without a word.
  if (window.BeeshTVInput && BeeshTVInput.playing) {
    var frameId = Math.random().toString(36).slice(2);
    var videoPlaying = function () {
      var vs = document.getElementsByTagName('video');
      for (var i = 0; i < vs.length; i++) if (!vs[i].paused && !vs[i].ended) return true;
      return false;
    };
    var said = false;
    var tell = function () {
      var now = videoPlaying();
      if (now || said) BeeshTVInput.playing(frameId, now);
      // The frame around this one learns too, so it knows which of its frames to make full screen.
      if (now !== said && window.parent !== window) window.parent.postMessage({ __beeshtvPlaying: now }, '*');
      said = now;
    };
    ['playing', 'pause', 'ended', 'emptied'].forEach(function (ev) {
      document.addEventListener(ev, function () { setTimeout(tell, 50); }, true);
    });
    setInterval(function () { if (said) tell(); }, 15000);
    window.addEventListener('pagehide', function () { if (said) BeeshTVInput.playing(frameId, false); });

    // 3c. A real video (big, with sound, longer than a clip) starting to play: the browser offers to
    //     show it full screen. Muted previews and small autoplaying clips don't count.
    document.addEventListener('playing', function (e) {
      var v = e.target;
      if (!v || v.tagName !== 'VIDEO' || v.__beeshtvOffered || v.muted || document.fullscreenElement) return;
      var r = v.getBoundingClientRect();
      if (r.width < 320 || r.height < 180 || (isFinite(v.duration) && v.duration < 30)) return;
      v.__beeshtvOffered = true;
      if (BeeshTVInput.video) BeeshTVInput.video(frameId);
    }, true);

    // Full screen, when asked: the browser presses Ctrl+Alt+Shift+F12, which no site uses, and a key
    // press is what lets a page go full screen. The frame that gets it shows its own playing video;
    // a frame without one shows the frame inside it that has one, or asks the frame around it.
    var playingFrame = null;
    var frameOf = function (win) {
      var fs = document.querySelectorAll('iframe,frame');
      for (var i = 0; i < fs.length; i++) if (fs[i].contentWindow === win) return fs[i];
      return null;
    };
    var biggestPlaying = function () {
      var best = null, area = 0, vs = document.getElementsByTagName('video');
      for (var i = 0; i < vs.length; i++) {
        var v = vs[i], r = v.getBoundingClientRect();
        if (!v.paused && !v.ended && r.width * r.height > area) { best = v; area = r.width * r.height; }
      }
      return best;
    };
    var full = function (el) {
      try {
        var p = (el.requestFullscreen || el.webkitRequestFullscreen).call(el);
        if (p && p.catch) p.catch(function () {});
        return true;
      } catch (e) { return false; }
    };
    var goFull = function () {
      var v = biggestPlaying();
      if (v && full(v)) return;
      if (playingFrame && playingFrame.isConnected && full(playingFrame)) return;
      if (window.parent !== window) window.parent.postMessage({ __beeshtvFull: true }, '*');
    };
    window.addEventListener('keydown', function (e) {
      if (e.key !== 'F12' || !e.ctrlKey || !e.altKey || !e.shiftKey) return;
      e.preventDefault();
      e.stopImmediatePropagation();
      goFull();
    }, true);
    window.addEventListener('message', function (e) {
      var d = e.data;
      if (!d || typeof d !== 'object') return;
      if (d.__beeshtvFull === true) { goFull(); return; }
      if (typeof d.__beeshtvPlaying !== 'boolean') return;
      var f = frameOf(e.source);
      if (!f) return;
      if (d.__beeshtvPlaying) playingFrame = f; else if (playingFrame === f) playingFrame = null;
      if (window.parent !== window) window.parent.postMessage({ __beeshtvPlaying: d.__beeshtvPlaying }, '*');
    });
  }

  // 4. Blocked hosts fail the way they do with any ad blocker: the browser decides (adblock lists,
  //    DNS protection, and sites where ads are allowed). Requests the page makes from script would
  //    otherwise "succeed" with the browser's empty answer.
  if (window.BeeshTVInput && BeeshTVInput.blocks) {
    // One answer per host, so a stream's segment requests don't each wait on the browser.
    var answers = {};
    var blocked = function (url) {
      try {
        var u = new URL(url, location.href);
        if (u.hostname === location.hostname) return false;
        if (!(u.hostname in answers)) answers[u.hostname] = BeeshTVInput.blocks(u.href);
        return answers[u.hostname];
      } catch (e) { return false; }
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

  // 4b. Ad-blocker tests pass. Sites test for one by asking for a well-known ad script and showing a
  //     wall if that fails, and it fails here whether the browser or the TV's own DNS blocked it. For
  //     those few ad hosts a failure is turned into an empty "loaded", so the test passes and still no
  //     ad shows. Requests that do get through are left as they are.
  var BAIT = /(^|\.)(googlesyndication\.com|doubleclick\.net|googletagservices\.com|googleadservices\.com|adservice\.google\.[a-z.]+|amazon-adsystem\.com|adnxs\.com|pubmatic\.com|carbonads\.(com|net)|buysellads\.com|popads\.net|propellerads\.com)$/i;
  var isBait = function (url) {
    try { return BAIT.test(new URL(url, location.href).hostname); } catch (e) { return false; }
  };
  var seemLoaded = function (url) {
    if (/adsbygoogle/.test(url)) {
      try { window.adsbygoogle = window.adsbygoogle || []; window.adsbygoogle.loaded = true; } catch (e) {}
    }
  };
  var probeFetch = window.fetch;
  if (probeFetch) window.fetch = function (input, init) {
    var url = typeof input === 'string' ? input : (input && input.url) || String(input);
    var p = probeFetch.apply(this, arguments);
    if (!isBait(url)) return p;
    return p.catch(function () { seemLoaded(url); return new Response('', { status: 200, statusText: 'OK' }); });
  };
  var probeOpen = XMLHttpRequest.prototype.open, probeSend = XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.open = function (method, url) {
    this.__beeshtvBait = isBait(url) ? String(url) : null;
    return probeOpen.apply(this, arguments);
  };
  XMLHttpRequest.prototype.send = function () {
    var url = this.__beeshtvBait;
    if (!url) return probeSend.apply(this, arguments);
    var x = this, as = { readyState: 4, status: 200, statusText: 'OK', responseText: '', response: '', responseURL: url };
    Object.keys(as).forEach(function (k) {
      try { Object.defineProperty(x, k, { value: as[k], configurable: true }); } catch (e) {}
    });
    setTimeout(function () {
      ['readystatechange', 'load', 'loadend'].forEach(function (t) { x.dispatchEvent(new Event(t)); });
    }, 10);
  };
  // A <script> or picture from those hosts that failed to load reports "load" instead of "error".
  window.addEventListener('error', function (e) {
    var el = e.target;
    if (!el || el === window || !el.tagName) return;
    var src = el.src || el.href;
    if (!src || !isBait(src)) return;
    e.stopImmediatePropagation();
    seemLoaded(src);
    setTimeout(function () { el.dispatchEvent(new Event('load')); }, 0);
  }, true);

  // 5. Lighter pages (BrowserActivity sets __beeshtvLight): a TV draws every page effect itself, every
  //    frame. Backdrop blur, the costliest, is switched off; and while a video plays, the page's looping
  //    decorations (spinners, carousels, pulses) are paused and its blurred pictures drawn sharp, so
  //    every frame goes to the video. Both come back when it stops.
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
    // Blur filters on the page (not the video's): taken out, keeping any other filter, and put back.
    var unblurred = [], lastLook = 0;
    var unblur = function () {
      var all = document.body ? document.body.getElementsByTagName('*') : [];
      for (var i = 0; i < all.length; i++) {
        var el = all[i];
        if (el.tagName === 'VIDEO' || el.__beeshtvFilter !== undefined) continue;
        var f = getComputedStyle(el).filter;
        if (!f || f.indexOf('blur(') < 0) continue;
        el.__beeshtvFilter = el.style.getPropertyValue('filter');
        el.__beeshtvPriority = el.style.getPropertyPriority('filter');
        el.style.setProperty('filter', f.replace(/blur\([^)]*\)/g, '').trim() || 'none', 'important');
        unblurred.push(el);
      }
    };
    var reblur = function () {
      unblurred.forEach(function (el) {
        el.style.setProperty('filter', el.__beeshtvFilter, el.__beeshtvPriority);
        delete el.__beeshtvFilter;
        delete el.__beeshtvPriority;
      });
      unblurred = [];
    };
    var settle = function () {
      if (anyPlaying()) {
        if (document.getAnimations) {
          document.getAnimations().forEach(function (an) {
            var t = an.effect && an.effect.getTiming && an.effect.getTiming();
            if (t && t.iterations === Infinity && an.playState === 'running') { an.pause(); paused.push(an); }
          });
        }
        // Looking at every element costs a moment, so it's done as the video starts and then rarely.
        if (Date.now() - lastLook > 15000) { lastLook = Date.now(); unblur(); }
      } else {
        paused.forEach(function (an) { try { an.play(); } catch (e) {} });
        paused = [];
        reblur();
        lastLook = 0;
      }
    };
    ['playing', 'pause', 'ended'].forEach(function (ev) {
      document.addEventListener(ev, function () { setTimeout(settle, 50); }, true);
    });
    // Decorations added later while the video plays are caught too.
    setInterval(function () { if (paused.length || unblurred.length || anyPlaying()) settle(); }, 3000);
  }

  // 6. Every site in its full desktop layout, fitted to the screen at 100%: the TV is only 960 points
  //    wide, below where sites switch to desktop, so the page's viewport is set to the width the browser
  //    asks for (1280, a laptop's) and zoomed to fill the screen exactly. The
  //    page's own viewport tag is rewritten the moment it appears, so nothing jumps.
  if (window.top === window && window.BeeshTVInput && BeeshTVInput.layoutWidth) {
    var layout = BeeshTVInput.layoutWidth(location.hostname);
    var screenW = window.__beeshtvScreen || screen.width;
    // A screen wider than that (a big or low-density TV) lays pages out at its own width, at 100%,
    // instead of blowing a laptop-sized page up to fill it.
    if (screenW > layout) layout = screenW;
    if (layout > 0) {
      var want = 'width=' + layout + ',initial-scale=' + (Math.round(screenW / layout * 1000) / 1000)
          + ',minimum-scale=0.25,maximum-scale=5,user-scalable=yes';
      var fix = function (m) { if (m.content !== want) m.content = want; };
      var ensure = function () {
        var ms = document.querySelectorAll('meta[name="viewport"]');
        if (ms.length) { for (var i = 0; i < ms.length; i++) fix(ms[i]); return; }
        var head = document.head || document.documentElement;
        if (!head) return;
        var m = document.createElement('meta');
        m.name = 'viewport';
        m.content = want;
        head.appendChild(m);
      };
      ensure();
      new MutationObserver(function (changes) {
        for (var i = 0; i < changes.length; i++) {
          var c = changes[i];
          if (c.type === 'attributes') { if (c.target.name === 'viewport') fix(c.target); continue; }
          for (var k = 0; k < c.addedNodes.length; k++) {
            var n = c.addedNodes[k];
            if (n.tagName === 'META' && n.name === 'viewport') fix(n);
          }
        }
      }).observe(document, { childList: true, subtree: true, attributes: true, attributeFilter: ['content'] });
      document.addEventListener('DOMContentLoaded', ensure);
    }
  }

  // 6b. No gaps where ads were, as uBlock Origin does it: common ad containers are hidden, so is any
  //     frame or image from a blocked ad server, and then the box around it too if nothing visible is
  //     left inside (an empty card in a row closes up instead of staying blank).
  if (window.BeeshTVInput && BeeshTVInput.adsOn && BeeshTVInput.adsOn(location.hostname)) {
    var ADS = 'ins.adsbygoogle,.adsbygoogle,[id^="google_ads_iframe"],[id^="div-gpt-ad"],[id^="gpt-ad"],' +
      '[id^="gpt_unit"],[data-google-query-id],[id^="aswift_"],[data-ad-slot],[data-ad-unit],[data-adunit],' +
      '[data-ad-unit-path],amp-ad,amp-embed[type="taboola"],.ad-slot,.ad-container,.ad-wrapper,.ad-banner,' +
      '.ad-unit,.ad-placeholder,.adsbox,.advertisement,.sponsored-ad,[id^="taboola-"],.trc_rbox_container,' +
      '.OUTBRAIN,iframe[src*="googlesyndication"],iframe[src*="doubleclick"],iframe[src*="amazon-adsystem"]';
    var adCss = document.createElement('style');
    adCss.textContent = ADS + '{display:none!important}';
    var addAdCss = function () { (document.head || document.documentElement).appendChild(adCss); };
    if (document.documentElement) addAdCss(); else document.addEventListener('DOMContentLoaded', addAdCss);

    var hostOf = function (u) { try { return new URL(u, location.href).hostname; } catch (e) { return ''; } };
    var seen = {};
    var blockedSrc = function (el) {
      var src = el.getAttribute('src') || el.getAttribute('data-src') || '';
      if (!src || src.indexOf('data:') === 0 || src.indexOf('about:') === 0) return false;
      var h = hostOf(src);
      if (!h || h === location.hostname) return false;
      if (!(h in seen)) seen[h] = BeeshTVInput.blocks('https://' + h + '/');
      return seen[h];
    };
    var MEDIA = 'img,video,canvas,svg,picture,iframe,object,embed';
    var empty = function (el) {
      if ((el.innerText || '').trim()) return false;
      var ms = el.querySelectorAll(MEDIA);
      for (var i = 0; i < ms.length; i++) {
        var m = ms[i];
        if (m.dataset.beeshtvAd) continue;
        var r = m.getBoundingClientRect();
        if (r.width > 8 && r.height > 8) return false;
      }
      return true;
    };
    var collapse = function (el) {
      if (el.dataset.beeshtvAd) return;
      el.dataset.beeshtvAd = '1';
      el.style.setProperty('display', 'none', 'important');
      // Up to four boxes around it, while they hold nothing else you can see.
      var p = el.parentElement;
      for (var i = 0; i < 4 && p && p !== document.body && p !== document.documentElement; i++) {
        if (!empty(p)) break;
        p.dataset.beeshtvAd = '1';
        p.style.setProperty('display', 'none', 'important');
        p = p.parentElement;
      }
    };
    var sweep = function () {
      if (!document.body) return;
      document.querySelectorAll(ADS).forEach(collapse);
      document.querySelectorAll('iframe,img').forEach(function (el) { if (!el.dataset.beeshtvAd && blockedSrc(el)) collapse(el); });
    };
    var pending = null;
    var later = function () { if (!pending) pending = setTimeout(function () { pending = null; sweep(); }, 400); };
    document.addEventListener('DOMContentLoaded', sweep);
    window.addEventListener('load', function () { sweep(); setTimeout(sweep, 1500); setTimeout(sweep, 4000); });
    new MutationObserver(later).observe(document, { childList: true, subtree: true });
  }

  // 6c. "Turn off your ad blocker" walls, on every site: with the shield off too, since the TV's own DNS
  //     may be what the site noticed. The usual detector scripts are answered "no ad blocker"; a wall
  //     that shows anyway (a fixed box over the page that talks about ad blockers) is taken off, with
  //     its dimmed backdrop, and the page can scroll again.
  {
    var calm = function () {
      var self = {
        onDetected: function () { return self; },
        onNotDetected: function (f) { try { f(); } catch (e) {} return self; },
        on: function (detected, f) { if (!detected) try { f(); } catch (e) {} return self; },
        check: function () { return false; }, emitEvent: function () { return self; },
        clearEvent: function () {}, setOption: function () { return self; }
      };
      return self;
    };
    ['fuckAdBlock', 'blockAdBlock', 'sniffAdBlock'].forEach(function (n) {
      var one = calm();
      try { Object.defineProperty(window, n, { get: function () { return one; }, set: function () {} }); } catch (e) {}
    });
    ['FuckAdBlock', 'BlockAdBlock', 'SniffAdBlock'].forEach(function (n) {
      try { Object.defineProperty(window, n, { get: function () { return calm; }, set: function () {} }); } catch (e) {}
    });
    try { window.canRunAds = true; window.isAdBlockActive = false; } catch (e) {}

    var WALL = /\bad[\s-]?block|\b(whitelist|allowlist) (us|this site|our site)\b|\bsupport us by (disabling|allowing)\b|\ballow ads\b/i;
    var fixedUp = function (el) {
      var top = null;
      for (var n = el; n && n !== document.body && n !== document.documentElement; n = n.parentElement) {
        var pos = getComputedStyle(n).position;
        if (pos === 'fixed' || pos === 'sticky') top = n;
      }
      return top;
    };
    var covers = function (el, part) {
      var r = el.getBoundingClientRect();
      return r.width * r.height >= innerWidth * innerHeight * part;
    };
    var unlock = function () {
      [document.documentElement, document.body].forEach(function (n) {
        if (!n) return;
        var cs = getComputedStyle(n);
        if (cs.overflow === 'hidden' || cs.overflowY === 'hidden') n.style.setProperty('overflow', 'auto', 'important');
        if (n === document.body && cs.position === 'fixed') n.style.setProperty('position', 'static', 'important');
        if (cs.filter && cs.filter.indexOf('blur(') >= 0) n.style.setProperty('filter', 'none', 'important');
      });
      // Walls often blur the page's main block instead of the body.
      var kids = document.body ? document.body.children : [];
      for (var i = 0; i < kids.length; i++) {
        var f = getComputedStyle(kids[i]).filter;
        if (f && f.indexOf('blur(') >= 0) kids[i].style.setProperty('filter', 'none', 'important');
      }
    };
    var tearDown = function () {
      if (!document.body || !document.elementsFromPoint) return;
      var gone = false;
      [[0.5, 0.5], [0.5, 0.3], [0.5, 0.7]].forEach(function (at) {
        var stack = document.elementsFromPoint(innerWidth * at[0], innerHeight * at[1]);
        for (var i = 0; i < stack.length; i++) {
          var box = fixedUp(stack[i]);
          if (!box || !box.isConnected) continue;
          var words = (box.innerText || '').trim();
          if (words.length > 0 && words.length < 2000 && WALL.test(words)) { box.remove(); gone = true; continue; }
          // The dimmed backdrop under such a wall: fixed, nearly the whole screen, no words.
          if (gone && words.length < 20 && covers(box, 0.8)) box.remove();
        }
      });
      if (gone) unlock();
    };
    // Often for the first minute (when walls appear), then now and then.
    var looks = 0;
    var look = function () {
      tearDown();
      looks++;
      setTimeout(look, looks < 40 ? 1500 : 5000);
    };
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', look); else look();
  }

  // 7. YouTube's own ads (the ones inside videos), as Brave and uBlock Origin do it: the player is
  //    given its data with the ad schedule taken out, so no ad is ever set up. If one still starts, it
  //    is skipped to its end, muted, and the skip button pressed. Ad slots on the page are hidden.
  //    Off when ad blocking is off, or ads are allowed on YouTube.
  if (/(^|\.)youtube(-nocookie)?\.com$/.test(location.hostname) && window.BeeshTVInput && BeeshTVInput.adsOn
      && BeeshTVInput.adsOn(location.hostname)) {
    var AD_KEYS = ['adPlacements', 'playerAds', 'adSlots', 'adBreakHeartbeatParams'];
    var prune = function (o) {
      if (!o || typeof o !== 'object') return o;
      AD_KEYS.forEach(function (k) { if (k in o) delete o[k]; });
      if (o.playerResponse) prune(o.playerResponse);
      if (Array.isArray(o)) o.forEach(prune);
      return o;
    };
    var parse = JSON.parse;
    JSON.parse = function () { return prune(parse.apply(this, arguments)); };
    var json = Response.prototype.json;
    Response.prototype.json = function () { return json.apply(this, arguments).then(prune); };
    ['ytInitialPlayerResponse', 'ytInitialData'].forEach(function (name) {
      var held;
      try {
        Object.defineProperty(window, name, {
          configurable: true,
          get: function () { return held; },
          set: function (v) { held = prune(v); }
        });
      } catch (e) {}
    });
    var hide = document.createElement('style');
    hide.textContent = '.ytp-ad-module,.ytp-ad-overlay-container,.ytp-ad-image-overlay,#player-ads,' +
      'ytd-ad-slot-renderer,ytd-display-ad-renderer,ytd-promoted-sparkles-web-renderer,ytd-in-feed-ad-layout-renderer,' +
      'ytd-banner-promo-renderer,ytd-statement-banner-renderer,ytm-promoted-sparkles-web-renderer,' +
      'ytm-companion-ad-renderer,ytm-ad-slot-renderer,ytm-promoted-video-renderer,.ytm-promoted-sparkles-text-search-renderer' +
      '{display:none!important}';
    var addHide = function () { (document.head || document.documentElement).appendChild(hide); };
    if (document.documentElement) addHide(); else document.addEventListener('DOMContentLoaded', addHide);
    setInterval(function () {
      var player = document.querySelector('.ad-showing');
      if (!player) return;
      var v = player.querySelector('video');
      if (v) { v.muted = true; if (isFinite(v.duration) && v.duration > 0) v.currentTime = v.duration; }
      var skip = document.querySelector('.ytp-ad-skip-button,.ytp-ad-skip-button-modern,.ytp-skip-ad-button');
      if (skip) skip.click();
    }, 300);
  }

  // 8. Read as a regular browser to players that check.
  try { Object.defineProperty(navigator, 'webdriver', { get: function () { return false; } }); } catch (e) {}
})();
