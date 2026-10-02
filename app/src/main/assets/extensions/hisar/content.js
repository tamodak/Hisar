'use strict';

/*
 * Hisar on the page itself, in every page and frame from the moment the document starts. It runs
 * in the extension's isolated world: pages can neither see it nor change what it does.
 *
 * - Showing: an item (link, list entry, card) whose text has a blocked word is hidden and emptied,
 *   as it appears — a site's suggestions, results, posts. Items the background script already
 *   cleaned out of the HTML (<hisar-hidden> markers) go the same way.
 * - Submitting — Enter, the page's own search or send button, a form — is cancelled when a text
 *   field holds a blocked word, and the field is cleared. (On-screen keyboards are checked before
 *   this, natively, in GuardedGeckoView; requests the page sends are checked in background.js.)
 * - Tapping a link, suggestion or button whose text has a blocked word is cancelled.
 * - Pasting or dropping text with a blocked word is refused; a finished word typed on a hardware
 *   keyboard is removed.
 * - For the app: simple mode's style, the simplified (reader) view, and saving the page.
 */
(function () {
  if (typeof HISAR_DATA === 'undefined' || window.__hisarContent) return;
  window.__hisarContent = true;

  var matchers = HisarMatcher.fromLists(HISAR_DATA.lists);
  var typedBlocked = matchers.typed;
  var shownBlocked = matchers.shown;
  var isTop = window === window.top;

  /** Reports of what was stopped are at most this often. */
  var REPORT_INTERVAL_MILLIS = 1500;
  /** Text longer than this is a section of the page, not an item in it. */
  var MAX_ITEM_TEXT = 2000;
  /** Replies to the app are sent in pieces of this many characters. */
  var CHUNK = 256 * 1024;
  /** Limits for a saved page's inlined images. */
  var MAX_IMAGE_BYTES = 4 * 1024 * 1024;
  var MAX_SAVED_BYTES = 24 * 1024 * 1024;
  /** Sweeps for late shadow roots after the page has loaded. */
  var SWEEP_DELAYS_MILLIS = [1000, 3000];

  // --- Telling the app ---------------------------------------------------------------------------

  var lastReport = 0;
  /** Something was stopped: the app says so, once per burst. */
  function report() {
    var now = Date.now();
    if (now - lastReport < REPORT_INTERVAL_MILLIS) return;
    lastReport = now;
    browser.runtime.sendNativeMessage('hisar', { type: 'blocked' }).catch(function () {});
  }

  // --- Simple mode ---------------------------------------------------------------------------------

  var STYLE_ID = 'hisar-simple-mode';
  function applySimpleMode(css) {
    var style = document.getElementById(STYLE_ID);
    if (!css) { if (style) style.remove(); return; }
    if (!style) { style = document.createElement('style'); style.id = STYLE_ID; }
    style.textContent = css;
    (document.head || document.documentElement).appendChild(style);
  }
  if (HISAR_DATA.simpleCss) {
    if (document.documentElement) applySimpleMode(HISAR_DATA.simpleCss);
    document.addEventListener('DOMContentLoaded', function () { applySimpleMode(HISAR_DATA.simpleCss); });
  }
  browser.runtime.onMessage.addListener(function (message) {
    if (message && message.type === 'simpleMode') applySimpleMode(message.css);
  });

  // --- Text fields, including inside shadow roots --------------------------------------------------

  // A tappable or listed item: what a tap acts on, and what is hidden when its text is blocked.
  var ITEM = 'a, li, button, article, [role="option"], [role="listitem"], [role="link"], [role="row"], [role="article"], [role="button"]';

  var roots = [document];
  var observedRoots = new WeakSet();

  /** Shadow roots, open or closed (an extension may see closed ones), get watched like the page. */
  function adoptShadowRoot(host) {
    var root = host.openOrClosedShadowRoot;
    if (!root || observedRoots.has(root)) return;
    roots.push(root);
    observe(root);
    queue(root);
  }

  // Components often attach their shadow roots after they are inserted, which no mutation of the
  // page shows. (Wrapping the page's attachShadow would hear of each one, but breaks sites' own
  // components.) So the page is swept for them as it loads, and a focused field's roots are
  // adopted when it gets focus.
  function sweepShadowRoots() {
    var walker = document.createTreeWalker(document.documentElement || document, NodeFilter.SHOW_ELEMENT);
    for (var n = walker.nextNode(); n; n = walker.nextNode()) if (n.tagName.indexOf('-') > 0) adoptShadowRoot(n);
  }
  document.addEventListener('DOMContentLoaded', sweepShadowRoots);
  window.addEventListener('load', function () {
    sweepShadowRoots();
    SWEEP_DELAYS_MILLIS.forEach(function (delay) { setTimeout(sweepShadowRoots, delay); });
  });
  window.addEventListener('focusin', function (e) {
    var path = e.composedPath ? e.composedPath() : [];
    path.forEach(function (n) { if (n && n.nodeType === 1 && n.tagName.indexOf('-') > 0) adoptShadowRoot(n); });
  }, true);

  function isField(el) {
    if (!el || el.nodeType !== 1) return false;
    if (el.isContentEditable) return true;
    if (el.tagName === 'TEXTAREA') return true;
    if (el.tagName !== 'INPUT') return false;
    var t = (el.type || 'text').toLowerCase();
    return t === 'text' || t === 'search' || t === 'url' || t === '' || t === 'email';
  }
  function valueOf(el) { return el.isContentEditable ? el.innerText : el.value; }
  function fields() {
    var found = [];
    roots.forEach(function (root) {
      try {
        root.querySelectorAll('input, textarea, [contenteditable=""], [contenteditable="true"]').forEach(function (el) {
          if (isField(el)) found.push(el);
        });
      } catch (e) {}
    });
    return found;
  }
  // Clears a field the way typing would, so frameworks (React and the like) see it too. Setting
  // value from here calls the element's own setter, past anything the page put in front of it.
  function clear(el) {
    if (el.isContentEditable) el.textContent = '';
    else el.value = '';
    el.dispatchEvent(new Event('input', { bubbles: true, composed: true }));
    el.dispatchEvent(new Event('change', { bubbles: true, composed: true }));
  }
  function clearBlockedFields() {
    var any = false;
    fields().forEach(function (el) { if (typedBlocked(valueOf(el))) { clear(el); any = true; } });
    return any;
  }
  function stop(e) {
    e.preventDefault();
    e.stopImmediatePropagation();
    report();
  }
  /** The text field an event is about, looking into shadow roots. */
  function fieldOf(e) {
    var path = e.composedPath ? e.composedPath() : [e.target];
    for (var i = 0; i < path.length; i++) if (isField(path[i])) return path[i];
    // A closed shadow root hides its inside from the event path; the focus still leads there.
    var active = document.activeElement;
    while (active && active.openOrClosedShadowRoot && active.openOrClosedShadowRoot.activeElement) {
      active = active.openOrClosedShadowRoot.activeElement;
    }
    return isField(active) ? active : null;
  }

  // --- Submitting ----------------------------------------------------------------------------------

  window.addEventListener('keydown', function (e) {
    if (e.key !== 'Enter' && e.keyCode !== 13) return;
    var field = fieldOf(e);
    if (field && typedBlocked(valueOf(field))) { clear(field); stop(e); }
  }, true);
  window.addEventListener('submit', function (e) {
    if (clearBlockedFields()) stop(e);
  }, true);

  // --- Tapping -------------------------------------------------------------------------------------

  // A tap on the page's own search button with a blocked word in a field, or on a link or
  // suggestion whose text has one. Checked on pointerdown too: some sites act before click.
  function onTap(e) {
    if (clearBlockedFields()) { stop(e); return; }
    // Only the tapped item itself — a link, button or list entry — not the page around it.
    var path = e.composedPath ? e.composedPath() : [e.target];
    for (var i = 0; i < path.length; i++) {
      var el = path[i];
      if (!el || el.nodeType !== 1 || el === document.body) return;
      if (!el.matches(ITEM)) continue;
      var label = (el.innerText || '').slice(0, 300) + ' ' + (el.getAttribute('aria-label') || '') + ' ' + (el.getAttribute('href') || '');
      if (typedBlocked(label)) stop(e);
      return;
    }
  }
  window.addEventListener('pointerdown', onTap, true);
  window.addEventListener('click', onTap, true);

  // --- Pasting, dropping, hardware keyboards ---------------------------------------------------------

  window.addEventListener('paste', function (e) {
    var text = e.clipboardData && e.clipboardData.getData('text');
    if (typedBlocked(text)) stop(e);
  }, true);
  window.addEventListener('drop', function (e) {
    var text = e.dataTransfer && e.dataTransfer.getData('text');
    if (typedBlocked(text)) stop(e);
  }, true);
  // A finished word (followed by a space or punctuation) typed outside the on-screen keyboard.
  window.addEventListener('input', function (e) {
    if (e.isComposing) return;
    var field = fieldOf(e);
    if (!field) return;
    var value = valueOf(field) || '';
    var finished = value.replace(/[\p{L}\p{N}]+$/u, '');
    if (typedBlocked(finished)) { clear(field); report(); }
  }, true);

  // --- Showing -------------------------------------------------------------------------------------

  var hidden = new WeakSet();
  var checked = new WeakMap();

  /** The item hidden in place of [el]: its link, list entry or card, or el itself. */
  function itemOf(el) {
    var item = el.closest(ITEM) || el;
    if (item === document.body || item === document.documentElement) return null;
    return item;
  }
  function inHidden(el) {
    for (var n = el; n; n = n.parentElement) if (hidden.has(n)) return true;
    return false;
  }

  /** Hides an item and empties it, so nothing of it can be read, copied, saved or shown later. */
  function hide(item) {
    if (hidden.has(item)) return;
    hidden.add(item);
    item.style.setProperty('display', 'none', 'important');
    item.setAttribute('data-hisar-hidden', '');
    var walker = document.createTreeWalker(item, NodeFilter.SHOW_TEXT);
    for (var t = walker.nextNode(); t; t = walker.nextNode()) if (t.data) t.data = '';
    item.querySelectorAll('img, source, video').forEach(function (media) {
      media.removeAttribute('srcset');
      media.removeAttribute('src');
    });
  }

  // Items are judged by their whole text: sites split it into pieces ("les" bolded + "bian…" in a
  // suggestion), so no single piece holds the word.
  function check(textNode) {
    var el = textNode.parentElement;
    if (!el || el.tagName === 'SCRIPT' || el.tagName === 'STYLE' || isField(el)) return;
    if (inHidden(el)) { if (textNode.data) textNode.data = ''; return; }
    if (el.tagName === 'TITLE') {
      if (shownBlocked(textNode.data)) textNode.data = '';
      return;
    }
    var item = itemOf(el);
    if (!item) return;
    var text = item.textContent || '';
    if (text.length > MAX_ITEM_TEXT) { item = el; text = el.textContent || ''; }
    // Never a whole section of the page, only the item around the words.
    if (text.length > MAX_ITEM_TEXT) return;
    if (checked.get(item) === text) return;
    checked.set(item, text);
    var label = text + ' ' + (item.getAttribute('aria-label') || '') + ' ' + (item.getAttribute('title') || '');
    if (shownBlocked(label)) { hide(item); report(); }
  }

  /** A <hisar-hidden> marker from the HTML filter: its item goes. */
  function onMarker(marker) {
    var parent = marker.parentElement;
    var item = parent && itemOf(parent);
    if (item) hide(item);
  }

  function scan(node) {
    if (node.nodeType === 3) { check(node); return; }
    if (node.nodeType !== 1 && node.nodeType !== 11) return;
    if (node.nodeType === 1) {
      if (node.tagName === 'HISAR-HIDDEN') { onMarker(node); return; }
      if (node.tagName.indexOf('-') > 0) adoptShadowRoot(node);
    }
    var walker = document.createTreeWalker(node, NodeFilter.SHOW_ELEMENT | NodeFilter.SHOW_TEXT);
    for (var n = walker.nextNode(); n; n = walker.nextNode()) {
      if (n.nodeType === 3) { if (n.data.trim().length > 0) check(n); continue; }
      if (n.tagName === 'HISAR-HIDDEN') onMarker(n);
      else if (n.tagName.indexOf('-') > 0) adoptShadowRoot(n);
    }
  }

  var pending = [];
  var scheduled = false;
  function flush() {
    scheduled = false;
    var batch = pending;
    pending = [];
    batch.forEach(scan);
  }
  // Batched to the next frame: before it is painted, so hidden items are never seen.
  function schedule() {
    if (!scheduled) { scheduled = true; requestAnimationFrame(flush); }
  }
  function queue(node) {
    pending.push(node);
    schedule();
  }
  function observe(root) {
    observedRoots.add(root);
    new MutationObserver(function (mutations) {
      mutations.forEach(function (m) {
        if (m.type === 'characterData') queue(m.target);
        else m.addedNodes.forEach(queue);
      });
    }).observe(root, { childList: true, subtree: true, characterData: true });
  }
  observe(document);
  document.addEventListener('DOMContentLoaded', function () { queue(document.body || document.documentElement); });

  // --- The app's requests (top frame only) -----------------------------------------------------------

  if (!isTop) return;

  var port = browser.runtime.connectNative('hisar');
  browser.runtime.sendMessage({ type: 'whoami' }).then(function (me) {
    port.postMessage({ type: 'tab', tabId: me.tabId });
  }, function () {});

  port.onMessage.addListener(function (message) {
    var reply = function (result) { port.postMessage({ type: 'reply', id: message.id, result: result }); };
    switch (message.cmd) {
      case 'reader': reply(readerArticle()); break;
      case 'save':
        savePage().then(function (html) { sendLong(message.id, html); }, function () { reply(null); });
        break;
      case 'simpleMode': applySimpleMode(message.css); reply(true); break;
    }
  });

  /** Long replies go in pieces: messages to the app are best kept small. */
  function sendLong(id, text) {
    for (var i = 0; i < text.length; i += CHUNK) {
      port.postMessage({ type: 'chunk', id: id, text: text.slice(i, i + CHUNK) });
    }
    port.postMessage({ type: 'reply', id: id, result: true });
  }

  /**
   * The simplified view's article: the element whose paragraphs hold the most text, with only
   * reading markup kept. Returns {title, html, length}, length 0 when the page is not an article.
   * Hidden items are left out.
   */
  function readerArticle() {
    var best = null, bestScore = 0, scores = new Map();
    document.querySelectorAll('p').forEach(function (p) {
      if (p.closest('nav,aside,footer,header,form,[role=navigation],[aria-hidden=true],[data-hisar-hidden]')) return;
      var len = (p.innerText || '').trim().length;
      if (len < 40) return;
      var parent = p.parentElement;
      var s = (scores.get(parent) || 0) + len;
      scores.set(parent, s);
      if (s > bestScore) { bestScore = s; best = parent; }
    });
    if (!best || bestScore < 600) return { length: 0 };
    var root = best.closest('article') || best;
    var keep = { P: 1, H1: 1, H2: 1, H3: 1, H4: 1, H5: 1, H6: 1, UL: 1, OL: 1, LI: 1, BLOCKQUOTE: 1, PRE: 1, FIGURE: 1, FIGCAPTION: 1, EM: 1, STRONG: 1, B: 1, I: 1, A: 1, BR: 1, IMG: 1, CODE: 1 };
    var skip = { SCRIPT: 1, STYLE: 1, NAV: 1, ASIDE: 1, FOOTER: 1, FORM: 1, BUTTON: 1, IFRAME: 1, NOSCRIPT: 1, SVG: 1, 'HISAR-HIDDEN': 1 };
    function esc(t) { return t.replace(/[&<>"]/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]; }); }
    function walk(n) {
      if (n.nodeType === 3) return esc(n.textContent);
      if (n.nodeType !== 1 || skip[n.tagName] || hidden.has(n)) return '';
      var inner = '';
      n.childNodes.forEach(function (c) { inner += walk(c); });
      var t = n.tagName;
      if (t === 'IMG') { var src = n.currentSrc || n.src; return src && src.indexOf('http') === 0 ? '<img src="' + esc(src) + '">' : ''; }
      if (t === 'A') return n.href && n.href.indexOf('http') === 0 ? '<a href="' + esc(n.href) + '">' + inner + '</a>' : inner;
      if (t === 'BR') return '<br>';
      if (keep[t]) return '<' + t.toLowerCase() + '>' + inner + '</' + t.toLowerCase() + '>';
      return inner;
    }
    var h1 = document.querySelector('h1');
    return { title: (h1 && h1.innerText.trim()) || document.title, html: walk(root), length: bestScore };
  }

  /**
   * The page as one HTML file for reading offline: its current DOM (hidden items left out), with
   * images and stylesheets inlined and scripts removed, so it shows the page as it was seen.
   */
  function savePage() {
    var clone = document.documentElement.cloneNode(true);
    var originals = document.documentElement.querySelectorAll('img');
    var copies = clone.querySelectorAll('img');
    var budget = { bytes: MAX_SAVED_BYTES };
    var work = [];
    copies.forEach(function (img, i) {
      var source = originals[i];
      var src = source && (source.currentSrc || source.src);
      img.removeAttribute('srcset');
      img.removeAttribute('loading');
      if (!src || src.indexOf('http') !== 0 || hidden.has(source)) { img.removeAttribute('src'); return; }
      work.push(dataUrl(src, budget).then(function (data) { if (data) img.setAttribute('src', data); else img.removeAttribute('src'); }));
    });
    clone.querySelectorAll('link[rel~="stylesheet"][href]').forEach(function (link) {
      var href = link.href;
      work.push(fetch(href).then(function (r) { return r.ok ? r.text() : ''; }).then(function (css) {
        var style = document.createElement('style');
        // url(…) in a stylesheet is relative to the stylesheet, not the page.
        style.textContent = css.replace(/url\(\s*(['"]?)([^'")]+)\1\s*\)/g, function (all, q, u) {
          if (/^(data:|https?:|#)/.test(u)) return all;
          try { return 'url("' + new URL(u, href).href + '")'; } catch (e) { return all; }
        });
        link.replaceWith(style);
      }, function () { link.remove(); }));
    });
    return Promise.all(work).then(function () {
      clone.querySelectorAll('script, noscript, iframe, frame, object, embed, base, [data-hisar-hidden], hisar-hidden, link[rel~="preload"], link[rel~="modulepreload"], link[rel~="prefetch"], meta[http-equiv]').forEach(function (n) { n.remove(); });
      clone.querySelectorAll('*').forEach(function (el) {
        for (var i = el.attributes.length - 1; i >= 0; i--) {
          var a = el.attributes[i];
          if (/^on/i.test(a.name) || /^\s*javascript:/i.test(a.value)) el.removeAttribute(a.name);
        }
      });
      var head = clone.querySelector('head');
      if (head) {
        var base = document.createElement('base');
        base.href = location.href;
        head.prepend(base);
        var charset = document.createElement('meta');
        charset.setAttribute('charset', 'utf-8');
        head.prepend(charset);
      }
      return '<!doctype html>\n' + clone.outerHTML;
    });
  }

  function dataUrl(src, budget) {
    return fetch(src).then(function (r) { return r.ok ? r.blob() : null; }).then(function (blob) {
      if (!blob || blob.size > MAX_IMAGE_BYTES || blob.size > budget.bytes) return null;
      budget.bytes -= blob.size;
      return new Promise(function (resolve) {
        var reader = new FileReader();
        reader.onload = function () { resolve(reader.result); };
        reader.onerror = function () { resolve(null); };
        reader.readAsDataURL(blob);
      });
    }, function () { return null; });
  }

})();
