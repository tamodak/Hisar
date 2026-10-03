'use strict';

/**
 * Filters the data sites send before their pages read it — search results, feeds, comments,
 * recommendations — on any site, without knowing its format.
 *
 * Sites send lists of entries (a video, a post, a comment) as JSON arrays of objects. The rule is
 * the page filter's, applied to data: the smallest entry holding a blocked word, phrase, #hashtag
 * or tag (a value under a key like "keywords" or "tags") is removed from its list, so the page
 * never shows it and nothing of it can be tapped. An entry too big to be one item (a long post)
 * keeps its place, with the offending strings blanked.
 *
 * When a match is in no list at all — the response is about one thing, like a video's player
 * data with its tags — the whole response is reported as blocked ([result.whole]): the caller
 * refuses it and the app leaves the page.
 *
 * Only strings that read as text are judged: addresses, ids and tokens are skipped.
 */
var HisarJsonFilter = (function () {
  /** Keys whose values are tags. */
  var TAG_KEY = /^(keywords?|tags?|hashtags?|topics?|categories)$/i;
  /** Readable text an entry may hold and still be one item (a YouTube video holds about 1000). */
  var ITEM_LIMIT = 4000;
  /**
   * Readable strings an entry needs to be an item (a title, a channel, a label): a lone piece of
   * a title ({ text: "…" }) is not one, and removing it would leave its video in place.
   */
  var ITEM_STRINGS = 3;
  /** Strings that are addresses, ids or tokens rather than text. */
  var NOT_TEXT = /^(https?:|\/|[A-Za-z0-9_\-+\/=%.:]{24,}$)/;

  function readable(s) {
    return s.length > 1 && !NOT_TEXT.test(s) && /\p{L}/u.test(s);
  }

  /**
   * [tests]: { shown(text), isTag(value) } from HisarMatcher. Returns filter(value, rendered),
   * which changes [value] in place and returns { removed, blanked, whole }. [rendered] (optional)
   * tells whether a text is already in the page's server-rendered HTML: such entries are left to
   * the page filter, since data that no longer matches the HTML breaks frameworks' hydration.
   */
  function create(tests) {
    /** The first string in [node] that matches, or null. */
    function firstMatch(node, key) {
      if (typeof node === 'string') {
        return readable(node) && ((key !== null && TAG_KEY.test(key) && tests.isTag(node)) || tests.shown(node)) ? node : null;
      }
      if (node === null || typeof node !== 'object') return null;
      for (var k in node) {
        if (!Object.prototype.hasOwnProperty.call(node, k)) continue;
        var found = firstMatch(node[k], Array.isArray(node) ? key : k);
        if (found !== null) return found;
      }
      return null;
    }

    return function filter(root, rendered) {
      var result = { removed: 0, blanked: 0, whole: false, samples: [] };
      function sample(text) { if (result.samples.length < 3) result.samples.push(String(text).slice(0, 100)); }

      /**
       * Visits [node]; returns { text, size, hit } for its subtree: its readable text (up to the
       * item limit), that text's length, and whether a match is left in it.
       */
      function visit(node, key) {
        if (typeof node === 'string') {
          if (!readable(node)) return { text: '', size: 0, strings: 0, hit: false };
          var tagged = key !== null && TAG_KEY.test(key) && tests.isTag(node);
          var hitString = tagged || tests.shown(node);
          if (hitString) sample((key || '') + ': ' + node);
          return { text: node, size: node.length, strings: 1, hit: hitString };
        }
        if (node === null || typeof node !== 'object') return { text: '', size: 0, strings: 0, hit: false };

        var parts = [];
        var size = 0;
        var strings = 0;
        var hit = false;
        if (Array.isArray(node)) {
          var tagList = key !== null && TAG_KEY.test(key);
          for (var i = 0; i < node.length; i++) {
            var child = node[i];
            var r = typeof child === 'string' && tagList
              ? { text: child, size: child.length, strings: 1, hit: tests.isTag(child) || tests.shown(child) }
              : visit(child, null);
            // A string in a plain list (trending searches, labels) is one entry among others: blanked
            // in place, keeping the list's shape. Tag lists are the exception: tags are what the
            // thing they belong to is about.
            if (typeof child === 'string' && r.hit && !tagList) {
              node[i] = '';
              result.blanked++;
              continue;
            }
            var isItem = child !== null && typeof child === 'object' && r.strings >= ITEM_STRINGS && r.size <= ITEM_LIMIT;
            // An item is judged as a whole too: sites split a title into pieces ("runs").
            if (isItem && (r.hit || tests.shown(r.text))) {
              if (rendered && rendered(firstMatch(child, null) || r.text)) continue;
              node.splice(i, 1);
              i--;
              result.removed++;
              continue;
            }
            // Too big to be one item (a long post): it stays, with what matched blanked.
            if (r.hit && child !== null && typeof child === 'object' && r.size > ITEM_LIMIT) { blank(child, null); r.hit = false; }
            if (r.hit) hit = true;
            if (size <= ITEM_LIMIT && r.text) parts.push(r.text);
            size += r.size;
            strings += r.strings;
          }
        } else {
          for (var k in node) {
            if (!Object.prototype.hasOwnProperty.call(node, k)) continue;
            var v = node[k];
            var rv = visit(v, k);
            if (rv.hit) hit = true;
            if (size <= ITEM_LIMIT && rv.text) parts.push(rv.text);
            size += rv.size;
            strings += rv.strings;
          }
        }
        return { text: size <= ITEM_LIMIT ? parts.join(' ') : '', size: size, strings: strings, hit: hit };
      }

      // A match left in no list: the response is about one thing, and that thing is blocked.
      result.whole = visit(root, null, false).hit;
      return result;

      /** Blanks the matching strings in [node]. */
      function blank(node, key) {
        if (node === null || typeof node !== 'object') return;
        var tagList = key !== null && TAG_KEY.test(key);
        for (var k in node) {
          if (!Object.prototype.hasOwnProperty.call(node, k)) continue;
          var v = node[k];
          var tagKey = Array.isArray(node) ? (tagList ? key : null) : k;
          if (typeof v === 'string') {
            if (readable(v) && ((tagKey !== null && TAG_KEY.test(tagKey) && tests.isTag(v)) || tests.shown(v))) {
              node[k] = '';
              result.blanked++;
            }
          } else {
            blank(v, tagKey);
          }
        }
      }

    };
  }

  /** Prefixes some sites put before JSON so it can't be run as a script. */
  var GUARD_PREFIX = /^\)\]\}'\s*\n?|^for\s*\(;;\);/;

  /**
   * Filters a JSON text. Returns { text, removed, blanked, whole, changed }; text unchanged when
   * it isn't JSON or nothing matched.
   */
  function filterText(filter, text, rendered) {
    var prefix = '';
    var m = GUARD_PREFIX.exec(text);
    if (m) { prefix = m[0]; text = text.slice(prefix.length); }
    var trimmed = text.trim();
    if (trimmed[0] !== '{' && trimmed[0] !== '[') return { text: prefix + text, changed: false };
    var value;
    try { value = JSON.parse(trimmed); } catch (e) { return { text: prefix + text, changed: false }; }
    var r = filter(value, rendered);
    var changed = r.removed > 0 || r.blanked > 0;
    return {
      text: changed ? prefix + JSON.stringify(value) : prefix + text,
      removed: r.removed, blanked: r.blanked, whole: r.whole, changed: changed, samples: r.samples,
    };
  }

  // --- Data pages embed in their HTML ---------------------------------------------------------

  /** Where a script assigns or passes a JSON literal: `= {…}`, `= '{…}'`, `('\x7b…')`. */
  var LITERAL_START = /[=(:,]\s*(?:(['"])(?=\{|\\x7b|\\u007b)|(\{))/g;
  /** Embedded data smaller than this is configuration, not content. */
  var MIN_EMBEDDED = 1024;

  /** Decodes a JS string literal's body (without its quotes). */
  function decodeJsString(body) {
    return body.replace(/\\(x[0-9a-fA-F]{2}|u[0-9a-fA-F]{4}|u\{[0-9a-fA-F]+\}|[\s\S])/g, function (all, e) {
      var c = e[0];
      if (c === 'x') return String.fromCharCode(parseInt(e.slice(1), 16));
      if (c === 'u') return String.fromCodePoint(parseInt(e.replace(/[u{}]/g, ''), 16));
      if (c === 'n') return '\n';
      if (c === 'r') return '\r';
      if (c === 't') return '\t';
      if (c === 'b') return '\b';
      if (c === 'f') return '\f';
      if (c === '\n') return '';
      return c;
    });
  }

  /** A JS string literal's body for [text], safe inside a <script>. */
  function encodeJsString(text, quote) {
    return text.replace(/[\\'"<>\n\r\u2028\u2029]/g, function (c) {
      if (c === quote || c === '\\') return '\\' + c;
      if (c === '<') return '\\x3c';
      if (c === '>') return '\\x3e';
      if (c === '\n') return '\\n';
      if (c === '\r') return '\\r';
      if (c === '\u2028') return '\\u2028';
      if (c === '\u2029') return '\\u2029';
      return c;
    });
  }

  /** Where the string literal starting after its opening quote at [from] ends, or -1. */
  function stringEnd(src, from, quote) {
    for (var i = from; i < src.length; i++) {
      var c = src[i];
      if (c === '\\') { i++; continue; }
      if (c === quote) return i;
      if (c === '\n') return -1;
    }
    return -1;
  }

  /** Where the object literal starting at [from] ('{') ends (index after '}'), or -1. */
  function objectEnd(src, from) {
    var depth = 0;
    for (var i = from; i < src.length; i++) {
      var c = src[i];
      if (c === '"' || c === "'") {
        var e = stringEnd(src, i + 1, c);
        if (e < 0) return -1;
        i = e;
      } else if (c === '{' || c === '[') {
        depth++;
      } else if (c === '}' || c === ']') {
        depth--;
        if (depth === 0) return i + 1;
      }
    }
    return -1;
  }

  /**
   * Filters the data in one <script>: all of it when it is JSON ([json]), else every large JSON
   * literal it assigns or passes. Returns { text, removed, blanked, whole, changed }.
   */
  function filterScript(filter, content, json, rendered) {
    var total = { text: content, removed: 0, blanked: 0, whole: false, changed: false };
    function add(r) {
      total.removed += r.removed || 0;
      total.blanked += r.blanked || 0;
      total.whole = total.whole || !!r.whole;
    }
    if (json) {
      var r = filterText(filter, content, rendered);
      add(r);
      if (r.changed) { total.text = r.text.replace(/<\//g, '<\\/'); total.changed = true; }
      return total;
    }
    if (content.length < MIN_EMBEDDED) return total;
    var out = '';
    var last = 0;
    LITERAL_START.lastIndex = 0;
    var m;
    while ((m = LITERAL_START.exec(content)) !== null) {
      var quote = m[1];
      var start = m.index + m[0].length - (quote ? 0 : 1);
      if (quote) {
        var end = stringEnd(content, start, quote);
        if (end < 0 || end - start < MIN_EMBEDDED) continue;
        var decoded = decodeJsString(content.slice(start, end));
        var rs = filterText(filter, decoded, rendered);
        add(rs);
        if (rs.changed) {
          out += content.slice(last, start) + encodeJsString(rs.text, quote);
          last = end;
          total.changed = true;
        }
        LITERAL_START.lastIndex = end + 1;
      } else {
        var stop = objectEnd(content, start);
        if (stop < 0 || stop - start < MIN_EMBEDDED) continue;
        var ro = filterText(filter, content.slice(start, stop), rendered);
        add(ro);
        if (ro.changed) {
          out += content.slice(last, start) + ro.text.replace(/<\//g, '<\\/').replace(/\u2028/g, '\\u2028').replace(/\u2029/g, '\\u2029');
          last = stop;
          total.changed = true;
        }
        LITERAL_START.lastIndex = stop;
      }
    }
    if (total.changed) total.text = out + content.slice(last);
    return total;
  }

  /**
   * Filters the data embedded in an HTML document as it streams in: text passes through as it
   * arrives, each <script> waits until it is complete and is filtered whole. The page's own text
   * is left to the content script (rewriting it breaks frameworks' hydration).
   */
  /** Text as letters and digits only, for comparing data with rendered HTML. */
  function plain(text) {
    return String(text).replace(/&#?[0-9a-z]+;/gi, ' ').toLowerCase().replace(/[^\p{L}\p{N}]+/gu, '');
  }

  function createDocument(filter) {
    var buf = '';
    // The page's text so far, outside scripts: what the server already rendered.
    var renderedText = [];
    var renderedLength = 0;
    function noteRendered(html) {
      if (renderedLength > MAX_RENDERED) return;
      var p = plain(html.replace(/<[^>]*>/g, ' '));
      renderedText.push(p);
      renderedLength += p.length;
    }
    function rendered(text) {
      var key = plain(text).slice(0, RENDERED_KEY);
      if (key.length < MIN_RENDERED_KEY) return false;
      return renderedText.join('').indexOf(key) >= 0;
    }
    var result = { removed: 0, blanked: 0, whole: false };
    var SCRIPT_OPEN = /<script\b[^>]*>/ig;
    var SCRIPT_CLOSE = /<\/script\s*>/ig;
    var TAIL = 8;
    /** While a script waits for its end, where to look for it next (not from the start again). */
    var closeFrom = -1;

    function process(final) {
      var out = '';
      var pos = 0;
      for (;;) {
        SCRIPT_OPEN.lastIndex = pos;
        var open = SCRIPT_OPEN.exec(buf);
        var resumed = closeFrom >= 0 && open && open.index === 0;
        if (!open) {
          var keep = final ? buf.length : Math.max(pos, buf.length - TAIL);
          // A '<script' cut off at the end of this chunk waits for the rest.
          var cut = final ? -1 : buf.lastIndexOf('<', buf.length - 1);
          if (!final && cut >= keep - TAIL && cut >= pos) keep = cut;
          noteRendered(buf.slice(pos, keep));
          out += buf.slice(pos, keep);
          buf = buf.slice(keep);
          return out;
        }
        SCRIPT_CLOSE.lastIndex = resumed ? Math.max(open[0].length, closeFrom) : open.index + open[0].length;
        closeFrom = -1;
        var close = SCRIPT_CLOSE.exec(buf);
        if (!close) {
          noteRendered(buf.slice(pos, open.index));
          out += buf.slice(pos, open.index);
          buf = buf.slice(open.index);
          if (final || buf.length > MAX_SCRIPT) { out += buf; buf = ''; return out; }
          closeFrom = Math.max(0, buf.length - TAIL);
          return out;
        }
        var contentStart = open.index + open[0].length;
        var content = buf.slice(contentStart, close.index);
        var json = /type\s*=\s*["']?[^"'\s>]*json/i.test(open[0]);
        noteRendered(buf.slice(pos, open.index));
        var r = filterScript(filter, content, json, rendered);
        result.removed += r.removed;
        result.blanked += r.blanked;
        result.whole = result.whole || r.whole;
        out += buf.slice(pos, contentStart) + (r.changed ? r.text : content) + close[0];
        pos = close.index + close[0].length;
      }
    }

    /** Pieces of a script still waiting for its end, joined only once the end has arrived. */
    var waiting = [];
    var waitingTail = '';
    var waitingLength = 0;

    return {
      write: function (chunk) {
        if (closeFrom >= 0) {
          var probe = waitingTail + chunk;
          SCRIPT_CLOSE.lastIndex = 0;
          if (!SCRIPT_CLOSE.test(probe) && waitingLength + chunk.length <= MAX_SCRIPT) {
            waiting.push(chunk);
            waitingLength += chunk.length;
            waitingTail = probe.slice(-TAIL);
            return '';
          }
          buf += waiting.join('');
          closeFrom = Math.max(0, buf.length - TAIL);
          waiting = [];
          waitingLength = 0;
        }
        buf += chunk;
        var out = process(false);
        if (closeFrom >= 0) { waitingTail = buf.slice(-TAIL); waitingLength = buf.length; }
        return out;
      },
      end: function () {
        buf += waiting.join('');
        waiting = [];
        return process(true);
      },
      result: result,
    };
  }

  /** A script longer than this is passed through unfiltered rather than held. */
  var MAX_SCRIPT = 16 * 1024 * 1024;
  /** How much of a page's rendered text is kept for comparison. */
  var MAX_RENDERED = 4 * 1024 * 1024;
  /** An entry counts as rendered when this much of its matching text is in the page. */
  var RENDERED_KEY = 40;
  var MIN_RENDERED_KEY = 12;

  return { create: create, filterText: filterText, filterScript: filterScript, createDocument: createDocument, ITEM_LIMIT: ITEM_LIMIT };
})();

if (typeof module !== 'undefined') module.exports = HisarJsonFilter;
