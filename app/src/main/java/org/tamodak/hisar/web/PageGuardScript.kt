package org.tamodak.hisar.web

import org.json.JSONArray
import org.tamodak.hisar.filter.Guard
import org.tamodak.hisar.filter.WordFilter

/**
 * The in-page half of Hisar's word filter, run in every page and frame before the page's own
 * scripts (a document-start script).
 *
 * Request contents can't be relied on: sites send searches as binary protobuf (YouTube), GraphQL
 * or compressed bodies, and their telemetry is full of short codes that look like words. So this
 * works on what the user does, which looks the same on every site:
 *
 * - **Submitting** — Enter, the page's own search or send button, a form — is cancelled when any
 *   text field holds a blocked word, and the field is cleared. Fields inside shadow DOM (Reddit's
 *   web components) are found through the event path and a patched attachShadow.
 * - **Pasting** or dropping text with a blocked word is refused, and a finished word typed by a
 *   hardware keyboard is removed. (On-screen keyboards are checked natively, in GuardedWebView.)
 * - **Tapping** a link, suggestion or button whose text has a blocked word is cancelled.
 * - **Showing**: elements whose text holds a blocked word — a site's suggestions, results that got
 *   through some other way — are hidden as they appear.
 * - **Requests**: values of search-like keys (`query`, `q`, `rawQuery`, …) in JSON and form
 *   bodies are checked; nothing else in a body is, since codes in telemetry match by accident.
 *
 * Matching is the same whole-word, case-folding match as [Guard]. Anything it stops is posted to
 * [CHANNEL], a WebMessageListener, so the browser can say so.
 */
object PageGuardScript {
    const val CHANNEL = "hisarGuard"

    fun build(guard: Guard): String {
        fun phrases(list: List<List<String>>) = JSONArray(list.map { JSONArray(it) }).toString()
        val search = phrases(guard.searchPhrases)
        val searchSymbols = JSONArray(guard.searchSymbols).toString()
        val searchEmbedded = JSONArray(guard.searchEmbedded).toString()
        val searchStems = JSONArray(guard.searchStems).toString()
        val page = phrases(guard.pagePhrases)
        val pageSymbols = JSONArray(guard.pageSymbols).toString()
        val pageEmbedded = JSONArray(guard.pageEmbedded).toString()
        val pageStems = JSONArray(guard.pageStems).toString()
        val suffixes = JSONArray(WordFilter.TURKISH_SUFFIXES).toString()
        return """
(function () {
  if (window.__hisarGuard) return;
  Object.defineProperty(window, '__hisarGuard', { value: true });

  // Same rules as WordFilter: whole words and phrases; safe words also with a Turkish ending
  // ("femboyun") or inside a joined-up name ("actuallesbians").
  var SUFFIXES = $suffixes;
  function escape(s) { return s.replace(/[.*+?^$\{\}()|[\]\\]/g, '\\$&'); }
  function matcher(phrases, symbols, embedded, stems) {
    // Prototype-free tables: a page's word "constructor" must not find Object's built-ins.
    var byFirst = Object.create(null);
    phrases.forEach(function (p) { (byFirst[p[0]] = byFirst[p[0]] || []).push(p); });
    var stemSet = Object.create(null);
    stems.forEach(function (s) { stemSet[s] = true; });
    var embeddedRe = embedded.length ? new RegExp(embedded.slice().sort(function (a, b) { return b.length - a.length; }).map(escape).join('|')) : null;
    function loose(word) {
      for (var s = 0; s < SUFFIXES.length; s++) {
        var suf = SUFFIXES[s];
        if (word.length > suf.length && word.slice(-suf.length) === suf && stemSet[word.slice(0, -suf.length)]) return true;
      }
      return word.length > 6 && embeddedRe !== null && embeddedRe.test(word);
    }
    return function (value) {
      if (value == null) return false;
      var text = String(value);
      try { text = decodeURIComponent(text.replace(/\+/g, ' ')); } catch (e) {}
      var w = text.toLowerCase().normalize('NFKC').replace(/i̇/g, 'i')
        .split(/[^\p{L}\p{N}]+/u).filter(function (x) { return x.length > 0; });
      for (var i = 0; i < w.length; i++) {
        var candidates = byFirst[w[i]];
        if (candidates) {
          for (var c = 0; c < candidates.length; c++) {
            var p = candidates[c], ok = i + p.length <= w.length;
            for (var j = 0; ok && j < p.length; j++) ok = w[i + j] === p[j];
            if (ok) return true;
          }
        }
        if (loose(w[i])) return true;
      }
      for (var s = 0; s < symbols.length; s++) if (text.indexOf(symbols[s]) >= 0) return true;
      return false;
    };
  }
  var typedBlocked = matcher($search, $searchSymbols, $searchEmbedded, $searchStems);
  var shownBlocked = matcher($page, $pageSymbols, $pageEmbedded, $pageStems);

  var lastReport = 0;
  function report() {
    var now = Date.now();
    if (now - lastReport < 1500) return;
    lastReport = now;
    try { if (window.$CHANNEL) window.$CHANNEL.postMessage('blocked'); } catch (e) {}
  }

  // A tappable or listed item: what a tap acts on, and what is hidden when its text is blocked.
  var ITEM = 'a, li, button, article, [role="option"], [role="listitem"], [role="link"], [role="row"], [role="article"], [role="button"]';

  // --- Text fields, including inside shadow roots ---------------------------------------------
  var roots = [document];
  var attachShadow = Element.prototype.attachShadow;
  Element.prototype.attachShadow = function () {
    var root = attachShadow.apply(this, arguments);
    roots.push(root);
    observe(root);
    return root;
  };
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
  var valueSetters = {
    INPUT: Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set,
    TEXTAREA: Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value').set
  };
  // Clears a field the way typing would, so frameworks (React and the like) see it too.
  function clear(el) {
    if (el.isContentEditable) el.textContent = '';
    else valueSetters[el.tagName].call(el, '');
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
  function fieldOf(e) {
    var path = e.composedPath ? e.composedPath() : [e.target];
    for (var i = 0; i < path.length; i++) if (isField(path[i])) return path[i];
    return null;
  }

  // --- Submitting -------------------------------------------------------------------------------
  window.addEventListener('keydown', function (e) {
    if (e.key !== 'Enter' && e.keyCode !== 13) return;
    var field = fieldOf(e);
    if (field && typedBlocked(valueOf(field))) { clear(field); stop(e); }
  }, true);
  window.addEventListener('submit', function (e) {
    if (clearBlockedFields()) stop(e);
  }, true);
  var submit = HTMLFormElement.prototype.submit;
  HTMLFormElement.prototype.submit = function () {
    if (clearBlockedFields()) { report(); return; }
    return submit.apply(this, arguments);
  };
  var requestSubmit = HTMLFormElement.prototype.requestSubmit;
  if (requestSubmit) HTMLFormElement.prototype.requestSubmit = function () {
    if (clearBlockedFields()) { report(); return; }
    return requestSubmit.apply(this, arguments);
  };

  // --- Tapping ----------------------------------------------------------------------------------
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

  // --- Pasting, dropping, hardware keyboards ------------------------------------------------------
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

  // --- Requests: search-like keys only -------------------------------------------------------------
  var SEARCH_KEY = /^(q|query|rawquery|searchquery|search_query|search|term|searchterm|keyword|keywords|text|oq|pq)$/i;
  function keyedBlocked(obj, depth) {
    if (!obj || typeof obj !== 'object' || depth > 6) return false;
    for (var k in obj) {
      if (!Object.prototype.hasOwnProperty.call(obj, k)) continue;
      var v = obj[k];
      if (typeof v === 'string' && SEARCH_KEY.test(k) && typedBlocked(v)) return true;
      if (typeof v === 'object' && keyedBlocked(v, depth + 1)) return true;
    }
    return false;
  }
  function bodyBlocked(body) {
    if (body == null) return false;
    if (typeof body === 'string') {
      var t = body.trim();
      if (t[0] === '{' || t[0] === '[') { try { return keyedBlocked(JSON.parse(t), 0); } catch (e) { return false; } }
      try { return bodyBlocked(new URLSearchParams(t)); } catch (e) { return false; }
    }
    if (body instanceof URLSearchParams || (typeof FormData !== 'undefined' && body instanceof FormData)) {
      var hit = false;
      body.forEach(function (v, k) { if (typeof v === 'string' && SEARCH_KEY.test(k) && typedBlocked(v)) hit = true; });
      return hit;
    }
    return false;
  }
  var originalFetch = window.fetch;
  if (originalFetch) {
    window.fetch = function (input, init) {
      if (init && bodyBlocked(init.body)) { report(); return Promise.reject(new TypeError('Blocked by Hisar')); }
      return originalFetch.apply(this, arguments);
    };
  }
  var send = XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.send = function (body) {
    if (bodyBlocked(body)) { report(); this.abort(); return; }
    return send.apply(this, arguments);
  };

  // --- Showing ------------------------------------------------------------------------------------
  // Hides what a page shows with a blocked word: the nearest list item, link or card.
  // Items are judged by their whole text: sites split it into pieces ("les" bolded + "bian…" in a
  // suggestion), so no single piece holds the word.
  var checked = new WeakMap();
  function check(textNode) {
    var el = textNode.parentElement;
    if (!el || el.tagName === 'SCRIPT' || el.tagName === 'STYLE' || el.tagName === 'TITLE' || isField(el)) return;
    var item = el.closest(ITEM) || el;
    if (item === document.body || item === document.documentElement) return;
    var text = item.textContent || '';
    if (text.length > 2000) { item = el; text = el.textContent || ''; }
    // Never a whole section of the page: that is the page scan's call, not this one's.
    if (text.length > 2000) return;
    if (checked.get(item) === text) return;
    checked.set(item, text);
    var label = text + ' ' + (item.getAttribute('aria-label') || '') + ' ' + (item.getAttribute('title') || '');
    if (shownBlocked(label)) item.style.setProperty('display', 'none', 'important');
  }
  function scan(node) {
    if (node.nodeType === 3) { check(node); return; }
    if (node.nodeType !== 1 && node.nodeType !== 11) return;
    var walker = document.createTreeWalker(node, NodeFilter.SHOW_TEXT);
    for (var t = walker.nextNode(); t; t = walker.nextNode()) if (t.data.trim().length > 0) check(t);
  }
  var pending = [];
  var scheduled = false;
  function flush() {
    scheduled = false;
    var batch = pending; pending = [];
    batch.forEach(scan);
  }
  function queue(node) {
    pending.push(node);
    if (!scheduled) { scheduled = true; requestAnimationFrame(flush); }
  }
  function observe(root) {
    new MutationObserver(function (mutations) {
      mutations.forEach(function (m) {
        if (m.type === 'characterData') queue(m.target);
        else m.addedNodes.forEach(queue);
      });
    }).observe(root, { childList: true, subtree: true, characterData: true });
  }
  observe(document);
  document.addEventListener('DOMContentLoaded', function () { queue(document.body || document.documentElement); });
})();
""".trimIndent()
    }
}
