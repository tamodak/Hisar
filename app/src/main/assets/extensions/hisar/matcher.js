'use strict';

/**
 * The word matcher, the same rules as the app's WordFilter: whole words and phrases, case-folded;
 * safe words also with a Turkish ending ("femboyun") or inside a joined-up name ("actuallesbians");
 * entries without letters (emoji) as plain substrings.
 *
 * The app builds the lists (GuardLists.kt), so tokenising them happens in one place. Used by the
 * background script, the content script and the Node tests.
 */
var HisarMatcher = (function () {
  /** Words remembered per matcher. */
  var MEMO_LIMIT = 50000;

  /** The words of [text] in normalised form, as WordFilter.tokenize makes them. */
  function tokens(text) {
    return String(text).toLowerCase().normalize('NFKC').replace(/i̇/g, 'i')
      .split(/[^\p{L}\p{N}]+/u).filter(function (x) { return x.length > 0; });
  }

  /**
   * A test for one list: { phrases: [[word…]…], symbols: […], embedded: […], stems: […] }, with
   * the shared Turkish [suffixes]. True if the text holds any entry.
   */
  function create(list, suffixes) {
    // Prototype-free tables: a page's word "constructor" must not find Object's built-ins.
    var byFirst = Object.create(null);
    list.phrases.forEach(function (p) { (byFirst[p[0]] = byFirst[p[0]] || []).push(p); });
    var stems = Object.create(null);
    list.stems.forEach(function (s) { stems[s] = true; });
    // Turkish endings by length, so a word needs one lookup per length rather than per ending.
    var endings = Object.create(null);
    var endingLengths = [];
    suffixes.forEach(function (suffix) {
      endings[suffix] = true;
      if (endingLengths.indexOf(suffix.length) < 0) endingLengths.push(suffix.length);
    });
    // Embedded words by length, looked up as substrings of the same lengths.
    var embedded = Object.create(null);
    var embeddedLengths = [];
    list.embedded.forEach(function (e) {
      embedded[e] = true;
      if (embeddedLengths.indexOf(e.length) < 0) embeddedLengths.push(e.length);
    });

    function looseUncached(word) {
      for (var i = 0; i < endingLengths.length; i++) {
        var n = endingLengths[i];
        if (word.length > n && endings[word.slice(-n)] === true && stems[word.slice(0, -n)] === true) return true;
      }
      if (word.length <= 6) return false;
      for (var j = 0; j < embeddedLengths.length; j++) {
        var len = embeddedLengths[j];
        for (var start = 0; start + len <= word.length; start++) {
          if (embedded[word.substr(start, len)] === true) return true;
        }
      }
      return false;
    }

    // Natural text repeats its words; each is judged once (up to a bound, for very long pages).
    var memo = new Map();
    function loose(word) {
      var known = memo.get(word);
      if (known !== undefined) return known;
      var result = looseUncached(word);
      if (memo.size > MEMO_LIMIT) memo.clear();
      memo.set(word, result);
      return result;
    }

    return function (value) {
      if (value == null) return false;
      var text = String(value);
      var w = tokens(text);
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
      for (var s = 0; s < list.symbols.length; s++) if (text.indexOf(list.symbols[s]) >= 0) return true;
      return false;
    };
  }

  /**
   * Tag words ("trans", "mtf"): a #hashtag of one marks what carries it, whatever else it says.
   * Returns { hashtagged(text), isTag(value) }: isTag tests a value of a tag field ("trans",
   * "#trans", "trans glow up").
   */
  function tagTest(tags) {
    var set = Object.create(null);
    (tags || []).forEach(function (t) { set[t] = true; });
    /** A tag field's value: tags are topics, so any one of its words being a tag word counts. */
    function isTag(value) {
      var w = tokens(String(value));
      for (var i = 0; i < w.length; i++) if (set[w[i]] === true) return true;
      return false;
    }
    var HASHTAG = /#([\p{L}\p{N}_]+)/gu;
    function hashtagged(text) {
      if (text == null) return false;
      var s = String(text);
      if (s.indexOf('#') < 0) return false;
      HASHTAG.lastIndex = 0;
      var m;
      while ((m = HASHTAG.exec(s)) !== null) {
        var w = tokens(m[1]);
        if (w.length === 1 && set[w[0]] === true) return true;
      }
      return false;
    }
    return { hashtagged: hashtagged, isTag: isTag };
  }

  /**
   * The tests from the app's lists: [typed] for what is typed, searched or sent (both lists);
   * [shown] for what pages show (the main list only — search-only words have innocent uses —
   * plus hashtags of tag words); [isTag] for values of tag fields in data.
   */
  function fromLists(lists) {
    var tags = tagTest(lists.tags);
    var shownWords = create(lists.shown, lists.suffixes);
    return {
      typed: create(lists.typed, lists.suffixes),
      shown: function (text) { return shownWords(text) || tags.hashtagged(text); },
      isTag: tags.isTag,
    };
  }

  return { create: create, fromLists: fromLists, tokens: tokens };
})();

if (typeof module !== 'undefined') module.exports = HisarMatcher;
