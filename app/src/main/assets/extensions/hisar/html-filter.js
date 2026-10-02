'use strict';

/**
 * Cleans a page's HTML as it streams in, before the browser parses it: a run of text with a
 * blocked word is replaced by an empty <hisar-hidden> marker, so the words never reach the page,
 * and the content script then hides the item (link, list entry, card) the marker sits in.
 *
 * It is a tokenizer, not a parser: tags pass through untouched, and only text between tags is
 * judged. Text inside <script>, <style>, <textarea>, <noscript> and <template> is not page text
 * and is left alone; a <title> with a blocked word is emptied. Anything it misreads is still
 * caught by the content script once the page builds its DOM.
 */
var HisarHtmlFilter = (function () {
  /** Elements whose content is raw text, not markup. */
  var RAW = { script: 1, style: 1, textarea: 1, noscript: 1, template: 1, title: 1, xmp: 1, plaintext: 1 };
  var MARKER = '<hisar-hidden></hisar-hidden>';
  /** Text carried over to the next chunk, so a word split between chunks is judged whole. */
  var MAX_CARRY = 65536;
  /** Longer than any raw element's end tag ("</plaintext>"). */
  var RAW_TAIL = 16;

  var ENTITIES = { amp: '&', lt: '<', gt: '>', quot: '"', apos: "'", nbsp: ' ' };
  /** Text as the reader sees it: character references resolved. */
  function decodeEntities(text) {
    if (text.indexOf('&') < 0) return text;
    return text.replace(/&(#x[0-9a-f]+|#[0-9]+|[a-z]+);?/gi, function (all, ref) {
      if (ref[0] === '#') {
        var code = ref[1] === 'x' || ref[1] === 'X' ? parseInt(ref.slice(2), 16) : parseInt(ref.slice(1), 10);
        try { return String.fromCodePoint(code); } catch (e) { return all; }
      }
      var named = ENTITIES[ref.toLowerCase()];
      return named === undefined ? all : named;
    });
  }

  /** A filter for one document. [blocked] tests text; write() and end() return cleaned HTML. */
  function create(blocked) {
    var carry = '';
    /** The raw-text element we are inside, or null. */
    var raw = null;
    var removed = 0;

    function text(segment) {
      if (raw === 'title') return blocked(decodeEntities(segment)) ? (removed++, '') : segment;
      if (raw !== null) return segment;
      if (!/\S/.test(segment)) return segment;
      if (!blocked(decodeEntities(segment))) return segment;
      removed++;
      return MARKER;
    }

    function tag(markup) {
      var m = /^<(\/?)([a-zA-Z][a-zA-Z0-9-]*)/.exec(markup);
      if (m && m[1] !== '/' && RAW[m[2].toLowerCase()] === 1 && !/\/\s*>$/.test(markup)) raw = m[2].toLowerCase();
      return markup;
    }

    /** Where the current raw-text element ends in [html] from [from], or -1. */
    function rawEnd(html, from) {
      var re = new RegExp('</' + raw + '[\\s>/]', 'ig');
      re.lastIndex = from;
      var m = re.exec(html);
      return m ? m.index : -1;
    }

    /** Cleans [html]; returns the cleaned part. */
    function process(html) {
      var out = '';
      var i = 0;
      while (i < html.length) {
        if (raw !== null) {
          // Raw text runs to its end tag, whatever '<' it contains ("if (a<b)" in a script).
          var end = rawEnd(html, i);
          if (end < 0) { out += text(html.slice(i)); break; }
          out += text(html.slice(i, end));
          raw = null;
          i = end;
          continue;
        }
        var lt = html.indexOf('<', i);
        if (lt < 0) { out += text(html.slice(i)); break; }
        if (lt > i) out += text(html.slice(i, lt));
        if (html.startsWith('<!--', lt)) {
          var close = html.indexOf('-->', lt + 4);
          var endComment = close < 0 ? html.length : close + 3;
          out += raw === null ? html.slice(lt, endComment) : text(html.slice(lt, endComment));
          i = endComment;
          continue;
        }
        var gt = html.indexOf('>', lt);
        if (gt < 0) { out += html.slice(lt); break; }
        out += tag(html.slice(lt, gt + 1));
        i = gt + 1;
      }
      return out;
    }

    /** How much of [html] can be cleaned now: up to the last complete tag. */
    function safeEnd(html) {
      // In raw text (a long script), all but a tail that could be the start of its end tag.
      if (raw !== null && rawEnd(html, 0) < 0) return Math.max(0, html.length - RAW_TAIL);
      var lt = html.lastIndexOf('<');
      var gt = html.lastIndexOf('>');
      // An open comment or tag at the end waits for the rest of it.
      var openComment = html.lastIndexOf('<!--');
      if (openComment >= 0 && html.indexOf('-->', openComment) < 0) return openComment;
      if (lt > gt) return lt;
      // Text after the last tag may be cut mid-word; it waits too, unless it is very long.
      if (html.length - (gt + 1) > MAX_CARRY) return html.length;
      return gt + 1;
    }

    return {
      write: function (chunk) {
        var html = carry + chunk;
        var end = safeEnd(html);
        if (end <= 0 && html.length < MAX_CARRY * 2) { carry = html; return ''; }
        if (end <= 0) end = html.length;
        carry = html.slice(end);
        return process(html.slice(0, end));
      },
      end: function () {
        var rest = carry;
        carry = '';
        return process(rest);
      },
      get removed() { return removed; },
    };
  }

  return { create: create, MARKER_TAG: 'hisar-hidden' };
})();

if (typeof module !== 'undefined') module.exports = HisarHtmlFilter;
