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
  function escape(s) { return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'); }

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
    var embedded = list.embedded.length
      ? new RegExp(list.embedded.slice().sort(function (a, b) { return b.length - a.length; }).map(escape).join('|'))
      : null;

    function loose(word) {
      for (var s = 0; s < suffixes.length; s++) {
        var suffix = suffixes[s];
        if (word.length > suffix.length && word.slice(-suffix.length) === suffix && stems[word.slice(0, -suffix.length)] === true) return true;
      }
      return word.length > 6 && embedded !== null && embedded.test(word);
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
   * Both tests from the app's lists: [typed] for what is typed, searched or sent (both lists), and
   * [shown] for what pages show (the main list only; search-only words have innocent uses).
   */
  function fromLists(lists) {
    return { typed: create(lists.typed, lists.suffixes), shown: create(lists.shown, lists.suffixes) };
  }

  return { create: create, fromLists: fromLists, tokens: tokens };
})();

if (typeof module !== 'undefined') module.exports = HisarMatcher;
