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

  /*
   * How the page is filtered. The defaults won a measured comparison on real sites and on a page
   * of hard cases (tools/experiments, 2026-10): no blocked text left visible, no page errors.
   * Debug builds can switch them to compare alternatives again:
   * - filter: hide anything at all (false measures the page untouched);
   * - hide: 'inline' style, or a 'sheet' rule on a marker attribute (equal results);
   * - empty: also blank a hidden item's text — off: changing text a framework rendered (React)
   *   makes it throw (26 errors per load on bbc.com), and hiding alone left nothing visible;
   * - shadow: find shadow roots by 'sweep'ing the page as it loads, by a 'main'-world hook on
   *   attachShadow (same results, but code in the page's world), or 'none' (leaks);
   * - scan: judge the smallest readable 'block' (paragraph, heading, link, list entry) — 'item'
   *   (the item, else the parent element) left headlines visible on news sites;
   * - collapse: a card left with almost no text once its title is hidden (a video's thumbnail)
   *   goes too;
   * - images: pictures judged by the app's classifier (off measures the text filter alone).
   */
  var config = Object.assign({ filter: true, hide: 'inline', empty: false, shadow: 'sweep', scan: 'block', collapse: true, images: true }, HISAR_DATA.config || {});
  var measuring = HISAR_DATA.measure === true;
  var metrics = { scanMs: 0, checks: 0, hidden: 0, hiddenChars: 0, errors: 0, rejections: 0, roots: 0, errorSamples: [], hiddenSamples: [] };

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
  /** When measuring: how long after load the page is audited. */
  var AUDIT_DELAY_MILLIS = 5000;
  /** A card with no more than this much other text left goes with its hidden title. */
  var COLLAPSE_TEXT = 40;
  /** A changing page's title and tags are checked at most this often. */
  var SUBJECT_CHECK_MILLIS = 300;

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
    if (config.shadow === 'none' || !config.filter) return;
    var root = host.openOrClosedShadowRoot;
    if (!root || observedRoots.has(root)) return;
    metrics.roots++;
    roots.push(root);
    // The page's styles don't reach into shadow roots: inline pictures there wait the same way.
    if (config.images) {
      var style = document.createElement('style');
      style.textContent = HISAR_DATA.inlineImageCss || '';
      root.appendChild(style);
    }
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
  if (config.shadow === 'sweep') {
    document.addEventListener('DOMContentLoaded', sweepShadowRoots);
    window.addEventListener('load', function () {
      sweepShadowRoots();
      SWEEP_DELAYS_MILLIS.forEach(function (delay) { setTimeout(sweepShadowRoots, delay); });
    });
  }
  // The 'main' strategy: a page-world script (shadow-hook.js) announces each shadow root attached
  // to an element already in the page; ones attached before insertion are found on insertion.
  if (config.shadow === 'main') {
    window.addEventListener('__hisar_shadow', function (e) {
      var host = e.composedPath ? e.composedPath()[0] : e.target;
      if (host && host.nodeType === 1) adoptShadowRoot(host);
    }, true);
  }
  if (config.shadow !== 'none') {
    window.addEventListener('focusin', function (e) {
      var path = e.composedPath ? e.composedPath() : [];
      path.forEach(function (n) { if (n && n.nodeType === 1 && n.tagName.indexOf('-') > 0) adoptShadowRoot(n); });
    }, true);
  }

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

  var HIDDEN_RULE = '[data-hisar-hidden] { display: none !important; }';
  var sheeted = new WeakSet();
  /** The 'sheet' strategy: one rule per document or shadow root, hiding marked items. */
  function ensureSheet(root) {
    if (sheeted.has(root)) return;
    sheeted.add(root);
    var style = document.createElement('style');
    style.textContent = HIDDEN_RULE;
    (root === document ? (document.head || document.documentElement) : root).appendChild(style);
  }

  /** Hides an item and empties it, so nothing of it can be read, copied, saved or shown later. */
  function hide(item) {
    if (hidden.has(item)) return;
    hidden.add(item);
    metrics.hidden++;
    metrics.hiddenChars += (item.textContent || '').length;
    if (measuring && metrics.hiddenSamples.length < 12) metrics.hiddenSamples.push(item.tagName + ': ' + (item.textContent || '').replace(/\s+/g, ' ').trim().slice(0, 50));
    item.setAttribute('data-hisar-hidden', '');
    if (config.hide === 'sheet') ensureSheet(item.getRootNode());
    else item.style.setProperty('display', 'none', 'important');
    if (!config.empty) return;
    var walker = document.createTreeWalker(item, NodeFilter.SHOW_TEXT);
    for (var t = walker.nextNode(); t; t = walker.nextNode()) if (t.data) t.data = '';
    item.querySelectorAll('img, source, video').forEach(function (media) {
      media.removeAttribute('srcset');
      media.removeAttribute('src');
    });
  }

  /**
   * The 'block' strategy's unit: the smallest block a reader sees as one piece — a link, list
   * entry, card, paragraph, heading or table cell — never a container judged by its children.
   */
  var BLOCK = ITEM + ', p, h1, h2, h3, h4, h5, h6, td, th, dd, dt, blockquote, figcaption, caption, label, summary, pre';
  /** Units judged in the current batch: a paragraph with many links is read once, not per link. */
  var judged = new Set();
  function checkBlock(textNode, el) {
    // 'item-block': the link or card around the text when it is item-sized (it takes a video's
    // thumbnail with its title), else the smallest block.
    var unit = null;
    if (config.scan === 'item-block') {
      unit = el.closest(ITEM);
      if (unit && (unit.textContent || '').length > MAX_ITEM_TEXT) unit = null;
    }
    unit = unit || el.closest(BLOCK);
    if (unit === document.body || unit === document.documentElement) unit = null;
    if (unit) {
      if (judged.has(unit)) return;
      judged.add(unit);
    }
    var text = unit ? unit.textContent || '' : '';
    if (!unit || text.length > MAX_ITEM_TEXT) {
      // Text loose in a container: judged alone; its own element goes only if that holds nothing else.
      if (shownBlocked(textNode.data) && el.childElementCount === 0 && el !== document.body) { hide(el); report(); }
      return;
    }
    if (checked.get(unit) === text) return;
    checked.set(unit, text);
    var label = text + ' ' + (unit.getAttribute('aria-label') || '') + ' ' + (unit.getAttribute('title') || '');
    if (shownBlocked(label)) {
      hide(unit);
      if (config.collapse) collapse(unit);
      report();
    }
  }

  /**
   * A card or link that is left with almost nothing to read once a block in it is hidden (a
   * video's thumbnail without its title) goes too.
   */
  function collapse(unit) {
    var card = unit.parentElement && unit.parentElement.closest(ITEM);
    if (!card || card === document.body || hidden.has(card)) return;
    var rest = 0;
    var walker = document.createTreeWalker(card, NodeFilter.SHOW_TEXT);
    for (var t = walker.nextNode(); t && rest <= COLLAPSE_TEXT; t = walker.nextNode()) {
      if (!inHidden(t.parentElement)) rest += t.data.trim().length;
    }
    if (rest <= COLLAPSE_TEXT) hide(card);
  }

  // Items are judged by their whole text: sites split it into pieces ("les" bolded + "bian…" in a
  // suggestion), so no single piece holds the word.
  function check(textNode) {
    var el = textNode.parentElement;
    if (!el || el.tagName === 'SCRIPT' || el.tagName === 'STYLE' || isField(el)) return;
    if (!/\S/.test(textNode.data)) return;
    if (inHidden(el)) { if (config.empty && textNode.data) textNode.data = ''; return; }
    if (el.tagName === 'TITLE') {
      if (config.empty && shownBlocked(textNode.data)) textNode.data = '';
      return;
    }
    metrics.checks++;
    if (config.scan === 'block' || config.scan === 'item-block') return checkBlock(textNode, el);
    var item = itemOf(el);
    if (!item) return;
    // The 'node' strategy: the text itself first; the item's whole text only when the text is one
    // piece of it (a word split across elements).
    if (config.scan === 'node') {
      if (shownBlocked(textNode.data)) { hide(item); report(); return; }
      if (el === item) return;
    }
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
      if (node.tagName === 'IMG') onImage(node);
      if (node.tagName.indexOf('-') > 0) adoptShadowRoot(node);
    }
    var walker = document.createTreeWalker(node, NodeFilter.SHOW_ELEMENT | NodeFilter.SHOW_TEXT);
    for (var n = walker.nextNode(); n; n = walker.nextNode()) {
      if (n.nodeType === 3) { if (n.data.trim().length > 0) check(n); continue; }
      if (n.tagName === 'HISAR-HIDDEN') onMarker(n);
      else if (n.tagName === 'IMG') onImage(n);
      else if (n.tagName.indexOf('-') > 0) adoptShadowRoot(n);
    }
  }

  var pending = [];
  var scheduled = false;
  function flush() {
    scheduled = false;
    var batch = pending;
    pending = [];
    var start = performance.now();
    batch.forEach(scan);
    judged.clear();
    metrics.scanMs += performance.now() - start;
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
    // A picture changing its address (sites reuse their elements as lists scroll) is judged again
    // before it is drawn: observers run before the next paint.
    if (config.images) {
      new MutationObserver(function (mutations) {
        mutations.forEach(function (m) { if (m.target.tagName === 'IMG') onImage(m.target); });
      }).observe(root, { attributes: true, subtree: true, attributeFilter: ['src', 'srcset'] });
    }
  }
  if (config.filter) {
    observe(document);
    document.addEventListener('DOMContentLoaded', function () { queue(document.body || document.documentElement); });
  }

  // --- Pictures ------------------------------------------------------------------------------------

  /*
   * Pictures loaded from the network are judged before they are drawn (image-filter.js): a picture
   * that is not safe arrives as a grey placeholder of the same size, and this frame hears of it.
   * - A video's thumbnail takes its video's card out of the list, as a blocked word in its title
   *   would, when the classifier is sure of it.
   * - Any other picture, and a thumbnail the classifier only covered to be safe, gets its outline
   *   mask when the app has made it: the people in it covered, the rest of the picture shown.
   * Pictures a page holds itself (data: and blob: addresses, such as Google Images' first results)
   * never touch the network: they stay invisible (a style registered with this script) until they
   * have been judged here.
   */
  /** Covered pictures' addresses, each with whether the classifier was sure. */
  var coveredImages = new Map();
  /** Cards hidden for their thumbnail, which come back if the site reuses them for another video. */
  var imageHidden = new WeakSet();
  /** The address this script gave a picture (placeholder or outline mask), which needs no judging. */
  var ownSource = new WeakMap();
  /** Outline masks asked for, by the picture's address or inline key. */
  var outlines = new Map();
  var IMAGE_OK = 'data-hisar-image-ok';
  /** Addresses of a video page, on the sites people watch videos on and in general. */
  var VIDEO_LINK = /(\/watch\b|\/shorts\/|youtu\.be\/|\/embed\/|\/videos?\/|vimeo\.com\/\d|\/reels?\/|dailymotion\.com\/video|\/clip\/|[?&]v=)/i;
  /** Thumbnails' own addresses on the largest video sites. */
  var THUMBNAIL = /(ytimg\.com\/(vi|vi_webp|an_webp|sb)\/|i\.vimeocdn\.com\/video\/|dmcdn\.net\/|tiktokcdn[^/]*\/.*(video|tos-)|rumble\.com\/.*thumb|img\.youtube\.com\/vi\/)/i;
  /** A video's length printed on its thumbnail (3:45, 1:02:10). */
  var DURATION = /^\s*\d{1,2}(:\d{2}){1,2}\s*$/;
  /** Inline pictures shorter than this (characters of their address) are icons and dots. */
  var MIN_INLINE = 1600;

  if (config.filter && config.images) {
    browser.runtime.onMessage.addListener(function (message) {
      if (message && message.type === 'imageCovered') {
        coveredImages.set(message.url, message.sure === true);
        // After the page has placed it: the grey picture is what is drawn meanwhile.
        requestAnimationFrame(function () { showing(message.url).forEach(function (el) { onCovered(el, message.url, message.sure === true); }); });
      }
    });
  }

  function sourceOf(el) {
    return el.currentSrc || el.getAttribute('src') || '';
  }

  /** Elements in this frame showing the picture at [url]. */
  function showing(url) {
    var found = [];
    roots.forEach(function (root) {
      try {
        root.querySelectorAll('img, video[poster], [style*="url("]').forEach(function (el) {
          if (el.tagName === 'IMG') {
            if (el.currentSrc === url || el.src === url || (el.getAttribute('srcset') || '').indexOf(url) >= 0) found.push(el);
          } else if (el.tagName === 'VIDEO') {
            if (el.poster === url) found.push(el);
          } else if ((el.style.backgroundImage || '').indexOf(url) >= 0) {
            found.push(el);
          }
        });
      } catch (e) {}
    });
    return found;
  }

  /** An <img> appeared or changed its address. */
  function onImage(img) {
    if (!config.images) return;
    var src = img.getAttribute('src') || '';
    if (ownSource.get(img) === src) { img.setAttribute(IMAGE_OK, ''); return; }
    // A card hidden for its old thumbnail comes back when the site shows another video in it.
    for (var n = img.parentElement; n; n = n.parentElement) {
      if (!imageHidden.has(n)) continue;
      if (!coveredImages.has(sourceOf(img))) unhideCard(n);
      break;
    }
    if (/^(data:image\/(?!svg)|blob:)/i.test(src)) {
      img.removeAttribute(IMAGE_OK);
      judgeInline(img, src);
      return;
    }
    var url = img.currentSrc || img.src;
    if (url && coveredImages.has(url)) onCovered(img, url, coveredImages.get(url));
  }

  /** A covered picture on this page: its video's card goes, or its outline mask is asked for. */
  function onCovered(el, key, sure) {
    if (inHidden(el)) return;
    var card = sure ? videoCard(el, key) : null;
    if (card) { hideCard(card); return; }
    if (!outlines.has(key)) outlines.set(key, browser.runtime.sendMessage({ type: 'outlineImage', url: key }).catch(function () { return null; }));
    outlines.get(key).then(function (masked) {
      if (!masked || inHidden(el)) return;
      if (el.tagName === 'IMG') {
        // Sources of a <picture> would outrank the new address.
        if (el.parentElement && el.parentElement.tagName === 'PICTURE') {
          el.parentElement.querySelectorAll('source').forEach(function (source) { source.removeAttribute('srcset'); });
        }
        ownSource.set(el, masked);
        el.removeAttribute('srcset');
        el.setAttribute('src', masked);
      } else if (el.tagName === 'VIDEO') {
        el.poster = masked;
      } else {
        el.style.setProperty('background-image', 'url("' + masked + '")', 'important');
      }
    });
  }

  /**
   * The card of the video whose thumbnail [el] is, or null when it is not a video's: the largest
   * element around it that holds no other video, as long as it reads as one item.
   */
  function videoCard(el, url) {
    var link = el.closest('a[href]');
    var video = THUMBNAIL.test(url) || el.tagName === 'VIDEO' || (link && VIDEO_LINK.test(link.href));
    var start = link || el;
    var mine = videoKeys(start);
    var card = start;
    for (var p = start.parentElement; p && p !== document.body && p !== document.documentElement; p = p.parentElement) {
      if ((p.textContent || '').length > MAX_ITEM_TEXT) break;
      var keys = videoKeys(p);
      var other = false;
      keys.forEach(function (k) { if (!mine.has(k)) other = true; });
      if (other || (!keys.size && otherPicture(p, card))) break;
      card = p;
    }
    if (!video) video = hasDuration(card);
    return video && card !== document.body ? card : null;
  }

  /** The videos an element links to, by address without its tracking parameters. */
  function videoKeys(el) {
    var keys = new Set();
    var links = el.matches('a[href]') ? [el] : [];
    el.querySelectorAll('a[href]').forEach(function (a) { links.push(a); });
    links.forEach(function (a) {
      if (!VIDEO_LINK.test(a.href)) return;
      var m = /[?&]v=([\w-]+)/.exec(a.href);
      keys.add(m ? m[1] : a.href.split(/[?#]/)[0]);
    });
    return keys;
  }

  /** Whether [el] shows another sizeable picture than those in [card]. */
  function otherPicture(el, card) {
    var imgs = el.querySelectorAll('img');
    for (var i = 0; i < imgs.length; i++) {
      if (card.contains(imgs[i])) continue;
      if (imgs[i].width >= 64 && imgs[i].height >= 64) return true;
    }
    return false;
  }

  function hasDuration(card) {
    var walker = document.createTreeWalker(card, NodeFilter.SHOW_TEXT);
    for (var t = walker.nextNode(); t; t = walker.nextNode()) if (DURATION.test(t.data)) return true;
    return false;
  }

  function hideCard(card) {
    imageHidden.add(card);
    hide(card);
  }

  function unhideCard(card) {
    imageHidden.delete(card);
    hidden.delete(card);
    card.removeAttribute('data-hisar-hidden');
    card.style.removeProperty('display');
    checked.delete(card);
    queue(card);
  }

  /** A picture the page holds itself: judged by the app, shown only once it is safe. */
  function judgeInline(img, src) {
    if (src.length < MIN_INLINE && src.indexOf('data:') === 0) { img.setAttribute(IMAGE_OK, ''); return; }
    bytesOf(src).then(function (bytes) {
      return browser.runtime.sendMessage({ type: 'judgeImage', bytes: bytes });
    }).then(function (verdict) {
      if (img.getAttribute('src') !== src) return;
      if (verdict && verdict.cover) {
        ownSource.set(img, verdict.placeholder);
        img.removeAttribute('srcset');
        img.setAttribute('src', verdict.placeholder);
        if (verdict.key) onCovered(img, verdict.key, verdict.sure === true);
      } else if (verdict) {
        img.setAttribute(IMAGE_OK, '');
      }
    }, function () {});
  }

  function bytesOf(src) {
    if (src.indexOf('blob:') === 0) return fetch(src).then(function (r) { return r.arrayBuffer(); });
    var comma = src.indexOf(',');
    var meta = src.slice(0, comma);
    var data = src.slice(comma + 1);
    if (/;base64$/i.test(meta)) {
      var text = atob(data.replace(/\s+/g, ''));
      var bytes = new Uint8Array(text.length);
      for (var i = 0; i < text.length; i++) bytes[i] = text.charCodeAt(i);
      return Promise.resolve(bytes.buffer);
    }
    return Promise.resolve(new TextEncoder().encode(decodeURIComponent(data)).buffer);
  }

  // --- Measuring (debug builds, tools/experiments) ----------------------------------------------------

  if (measuring && isTop) {
    window.addEventListener('error', function (e) {
      if (e.filename === undefined) return;
      metrics.errors++;
      if (metrics.errorSamples.length < 4) metrics.errorSamples.push(String(e.message).slice(0, 120) + ' @ ' + String(e.filename).slice(-60) + ':' + e.lineno);
    }, true);
    window.addEventListener('unhandledrejection', function () { metrics.rejections++; }, true);
    window.addEventListener('load', function () { setTimeout(audit, AUDIT_DELAY_MILLIS); });
  }

  /**
   * What is left on screen after the page settled: visible items whose text has a blocked word,
   * looked for everywhere (every shadow root too), whatever strategy is in use.
   */
  function audit() {
    var leaks = new Set();
    var samples = [];
    function walk(root) {
      var walker = document.createTreeWalker(root, NodeFilter.SHOW_ELEMENT | NodeFilter.SHOW_TEXT);
      for (var n = walker.nextNode(); n; n = walker.nextNode()) {
        if (n.nodeType === 1) {
          var shadow = n.openOrClosedShadowRoot;
          if (shadow) walk(shadow);
          continue;
        }
        var el = n.parentElement;
        if (!el || !n.data.trim() || el.tagName === 'SCRIPT' || el.tagName === 'STYLE') continue;
        if (el.checkVisibility && !el.checkVisibility()) continue;
        var item = el.closest(ITEM) || el;
        if (leaks.has(item)) continue;
        // What is rendered: innerText leaves out hidden descendants (slow, but only when measuring).
        var text = (item.innerText || '').slice(0, MAX_ITEM_TEXT);
        if (shownBlocked(text)) {
          leaks.add(item);
          if (samples.length < 3) samples.push(text.replace(/\s+/g, ' ').trim().slice(0, 80));
        }
      }
    }
    var start = performance.now();
    walk(document.documentElement);
    var nav = performance.getEntriesByType('navigation')[0];
    browser.runtime.sendNativeMessage('hisar', {
      type: 'metrics',
      url: location.href,
      config: config,
      scanMs: Math.round(metrics.scanMs),
      checks: metrics.checks,
      hidden: metrics.hidden,
      roots: metrics.roots,
      errors: metrics.errors,
      errorSamples: metrics.errorSamples,
      hiddenSamples: metrics.hiddenSamples,
      hiddenChars: metrics.hiddenChars,
      rejections: metrics.rejections,
      leaks: leaks.size,
      samples: samples,
      auditMs: Math.round(performance.now() - start),
      domContentLoadedMs: nav ? Math.round(nav.domContentLoadedEventEnd) : -1,
      loadMs: nav ? Math.round(nav.loadEventEnd) : -1,
      // The test page's own verdict (tools/experiments/testpage), else the start of the page.
      probe: (document.getElementById('hisar-test-results') || {}).textContent ||
        (document.body ? document.body.textContent : '').replace(/\s+/g, ' ').trim().slice(0, 160),
    }).catch(function () {});
  }

  // --- The page's subject (top frame only) -----------------------------------------------------------

  if (!isTop) return;

  /**
   * A page whose own title or tags hold a blocked word or tag is about it (a video's watch page):
   * the app leaves it. This holds however the page arrived — from the network (where the data
   * filter usually catches it first), from a site's service worker cache, or by a single-page
   * app changing pages without a load.
   */
  var leftFor = null;
  function checkSubject() {
    if (!config.filter) return;
    var title = document.title || '';
    var tags = [];
    document.querySelectorAll('meta[name="keywords"], meta[property="og:video:tag"], meta[property="article:tag"]').forEach(function (meta) {
      (meta.getAttribute('content') || '').split(',').forEach(function (t) { if (t.trim()) tags.push(t.trim()); });
    });
    var blocked = shownBlocked(title) || tags.some(function (t) { return matchers.isTag(t) || shownBlocked(t); });
    if (!blocked || leftFor === location.href) return;
    leftFor = location.href;
    browser.runtime.sendNativeMessage('hisar', { type: 'pageBlocked' }).catch(function () {});
  }
  // Titles change as single-page apps move between pages: the <head> is watched, throttled.
  var subjectTimer = null;
  var subjectWatcher = new MutationObserver(function () {
    if (subjectTimer) return;
    subjectTimer = setTimeout(function () { subjectTimer = null; checkSubject(); }, SUBJECT_CHECK_MILLIS);
  });
  document.addEventListener('DOMContentLoaded', function () {
    checkSubject();
    if (document.head) subjectWatcher.observe(document.head, { subtree: true, childList: true, characterData: true, attributes: true, attributeFilter: ['content'] });
  });

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
