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

  /** [text] in normalised form, as WordFilter.normalize makes it. */
  function normalize(text) {
    return String(text).toLowerCase().normalize('NFKC').replace(/i̇/g, 'i');
  }

  /** The words of [text] in normalised form, as WordFilter.tokenize makes them. */
  function tokens(text) {
    return normalize(text).split(/[^\p{L}\p{N}]+/u).filter(function (x) { return x.length > 0; });
  }

  // --- Readings: the text as written, and with disguises undone (Readings.kt, kept in step) -----

  var INVISIBLE = /[\u00AD\u200B-\u200F\u2060-\u2064\uFEFF]/g;
  var WORD_RUN = /[\p{L}\p{N}@$!|€+]+/gu;
  var TRIPLE = /(\p{L})\1{2,}/gu;
  var UNSPACED = /[\p{Script=Han}\p{Script=Hiragana}\p{Script=Katakana}\p{Script=Hangul}]/u;
  var LEET = { '0': 'o', '1': 'i', '3': 'e', '4': 'a', '5': 's', '7': 't', '8': 'b', '@': 'a', '$': 's', '!': 'i', '|': 'l', '€': 'e', '+': 't' };
  var LOOKALIKE = {
    'а': 'a', 'в': 'b', 'е': 'e', 'ё': 'e', 'к': 'k', 'м': 'm', 'н': 'h', 'о': 'o', 'р': 'p', 'с': 'c', 'т': 't', 'у': 'y', 'х': 'x',
    'і': 'i', 'ј': 'j', 'ѕ': 's', 'ԁ': 'd', 'ԛ': 'q', 'ԝ': 'w',
    'α': 'a', 'β': 'b', 'ε': 'e', 'ι': 'i', 'κ': 'k', 'ν': 'v', 'ο': 'o', 'ρ': 'p', 'τ': 't', 'υ': 'u', 'χ': 'x', 'γ': 'y',
  };
  var SMALL_CAPS = {};
  // Small capitals and their letters (there are none for x).
  'ᴀa ʙb ᴄc ᴅd ᴇe ꜰf ɢg ʜh ɪi ᴊj ᴋk ʟl ᴍm ɴn ᴏo ᴘp ꞯq ʀr ꜱs ᴛt ᴜu ᴠv ᴡw ʏy ᴢz'.split(' ').forEach(function (pair) { var c = Array.from(pair); SMALL_CAPS[c[0]] = c[1]; });
  function isLetter(c) { return /\p{L}/u.test(c); }
  function words(text) { return text.split(/[^\p{L}\p{N}]+/u).filter(function (x) { return x.length > 0; }); }

  function hasLetterRun(text) {
    var run = 0, w = words(text);
    for (var i = 0; i < w.length; i++) {
      run = w[i].length === 1 && isLetter(w[i]) ? run + 1 : 0;
      if (run >= 3) return true;
    }
    return false;
  }

  var SMALL_CAP_RE = /[ᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘꞯʀꜱᴛᴜᴠᴡʏᴢ]/g;
  var LOOKALIKE_RE = /[авеёкмнорстухіјѕԁԛԝαβεικνορτυχγ]/;
  var SIGN_RE = /[0134578@$!|€+]/;
  var LETTERS_RE = /\p{L}/gu;
  var SIGNS_RE = /[0134578@$!|€+]/g;

  /** The text with disguises undone, or null when it shows none (the common case, kept cheap). */
  function undisguise(text) {
    var t = text.replace(INVISIBLE, '');
    var changed = t.length !== text.length;
    var capped = t.replace(SMALL_CAP_RE, function (c) { return SMALL_CAPS[c]; });
    if (capped !== t) { t = capped; changed = true; }
    var regional = t.replace(/[\u{1F1E6}-\u{1F1FF}]/gu, function (c) { return String.fromCharCode(97 + c.codePointAt(0) - 0x1F1E6); });
    if (regional !== t) { t = regional; changed = true; }
    t = t.replace(WORD_RUN, function (word) {
      var mixed = LOOKALIKE_RE.test(word) && /[a-z]/.test(word);
      // Plain words, the vast majority, have nothing to undo.
      if (!mixed && !SIGN_RE.test(word)) return word;
      var result = word;
      if (mixed) result = result.replace(/./gu, function (c) { return LOOKALIKE[c] !== undefined ? LOOKALIKE[c] : c; });
      var letters = (result.match(LETTERS_RE) || []).length;
      var signs = (result.match(SIGNS_RE) || []).length;
      if (signs > 0 && letters >= 2 && signs <= letters) {
        result = result.replace(SIGNS_RE, function (c) { return LEET[c]; });
      }
      if (result !== word) changed = true;
      return result;
    });
    TRIPLE.lastIndex = 0;
    if (!changed && !TRIPLE.test(t) && !hasLetterRun(t)) return null;
    return t;
  }

  /** Runs of three or more single letters joined into one word. */
  function join(w) {
    var out = [];
    for (var i = 0; i < w.length;) {
      var j = i;
      while (j < w.length && w[j].length === 1 && isLetter(w[j])) j++;
      if (j - i >= 3) { out.push(w.slice(i, j).join('')); i = j; } else { out.push(w[i]); i++; }
    }
    return out;
  }

  /** Emoji as compared: without skin tones and variation selectors (👉🏻👌 is 👉👌). */
  var SYMBOL_NOISE = /[\u{1F3FB}-\u{1F3FF}\uFE0F]/gu;
  function symbolForm(text) { return String(text).replace(SYMBOL_NOISE, ''); }

  function same(a, b) {
    if (a.length !== b.length) return false;
    for (var i = 0; i < a.length; i++) if (a[i] !== b[i]) return false;
    return true;
  }

  /**
   * Whether [text] could hold a disguise at all: an invisible character, a small capital, a
   * regional-indicator letter, a Cyrillic or Greek letter, a digit or sign next to a letter, a
   * letter three times over or a single letter standing alone. Ordinary text has none, and skips
   * the second reading (a shortcut only: undisguise() would find nothing to undo).
   */
  var MAYBE_DISGUISED = /[\u00AD\u200B-\u200F\u2060-\u2064\uFEFF\u{1F1E6}-\u{1F1FF}ᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘꞯʀꜱᴛᴜᴠᴡʏᴢ\u0370-\u03FF\u0400-\u052F]|\p{L}[\p{N}@$!|€+]|[\p{N}@$!|€+]\p{L}|(\p{L})\1\1|(?:^|[^\p{L}\p{N}])\p{L}(?:[^\p{L}\p{N}]|$)/u;

  /** The word lists to match: as written, then the undisguised readings that differ. */
  function readings(text) {
    var plain = tokens(text);
    if (!MAYBE_DISGUISED.test(text)) return [plain];
    var undisguised = undisguise(normalize(text));
    if (undisguised === null) return [plain];
    var once = join(words(undisguised));
    var list = [plain];
    var first = once.map(function (w) { return w.replace(TRIPLE, '$1'); });
    if (!same(first, plain)) list.push(first);
    var second = once.map(function (w) { return w.replace(TRIPLE, '$1$1'); });
    if (!same(second, plain) && !same(second, first)) list.push(second);
    return list;
  }

  /**
   * A test for one list: { phrases: [[word…]…], symbols: […], embedded: […], stems: […] }, with
   * the shared Turkish [suffixes]. True if the text holds any entry.
   */
  function create(list, suffixes, allowedPhrases) {
    var allowed = Object.create(null);
    (allowedPhrases || []).forEach(function (p) { (allowed[p[0]] = allowed[p[0]] || []).push(p); });
    function startsAt(w, i, p) {
      if (i + p.length > w.length) return false;
      for (var j = 0; j < p.length; j++) if (w[i + j] !== p[j]) return false;
      return true;
    }
    // Prototype-free tables: a page's word "constructor" must not find Object's built-ins.
    var byFirst = Object.create(null);
    list.phrases.forEach(function (p) {
      // Numbers alone ("18" of "18+") would match every date and count.
      if (p.every(function (w) { return /^[0-9]+$/.test(w); })) return;
      (byFirst[p[0]] = byFirst[p[0]] || []).push(p);
    });
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

    var unspaced = list.symbols.filter(function (s) { return UNSPACED.test(s); });
    var symbols = list.symbols.filter(function (s) { return !UNSPACED.test(s); }).map(symbolForm);

    function matchWords(w) {
      var skipUntil = 0;
      for (var i = 0; i < w.length; i++) {
        var allow = allowed[w[i]];
        if (allow) for (var a = 0; a < allow.length; a++) if (startsAt(w, i, allow[a])) skipUntil = Math.max(skipUntil, i + allow[a].length);
        if (i < skipUntil) continue;
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
      return false;
    }

    return function (value) {
      if (value == null) return false;
      var text = String(value);
      var r = readings(text);
      for (var k = 0; k < r.length; k++) if (matchWords(r[k])) return true;
      if (symbols.length) {
        var symbolText = symbolForm(text);
        for (var s = 0; s < symbols.length; s++) if (symbolText.indexOf(symbols[s]) >= 0) return true;
      }
      if (unspaced.length) {
        var normalized = normalize(text);
        for (var u = 0; u < unspaced.length; u++) if (normalized.indexOf(unspaced[u]) >= 0) return true;
      }
      return false;
    };
  }

  /**
   * Tag words ("trans", "mtf"): a #hashtag of one marks what carries it, whatever else it says.
   * Returns { hashtagged(text), isTag(value) }: isTag tests a value of a tag field ("trans",
   * "#trans", "trans glow up").
   */
  function tagTest(tags, prefixes, joinable) {
    var set = Object.create(null);
    (tags || []).forEach(function (t) { set[t] = true; });
    var prefixList = (prefixes || []).slice().sort(function (a, b) { return b.length - a.length; });
    var words = Object.create(null);
    var longest = 0;
    (joinable || []).forEach(function (w) { words[w] = true; longest = Math.max(longest, w.length); });

    /** True if [rest] is a number, or splits wholly into joinable words ("couplegoals"). */
    function joins(rest) {
      if (/^[0-9]+$/.test(rest)) return true;
      var can = [true];
      for (var end = 1; end <= rest.length; end++) {
        can[end] = false;
        for (var start = Math.max(0, end - longest); start < end && !can[end]; start++) {
          if (can[start] && words[rest.slice(start, end)] === true) can[end] = true;
        }
      }
      return can[rest.length] === true;
    }

    /** A hashtag that joins an identity word with joinable words: #gaycouple, #transman, #pride2024. */
    function compound(tag) {
      for (var i = 0; i < prefixList.length; i++) {
        var p = prefixList[i];
        if (tag.length > p.length && tag.slice(0, p.length) === p && joins(tag.slice(p.length))) return true;
      }
      return false;
    }
    /** A tag field's value: tags are topics, so any one of its words being a tag word counts. */
    function isTag(value) {
      var w = tokens(String(value));
      for (var i = 0; i < w.length; i++) if (set[w[i]] === true || compound(w[i])) return true;
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
        var w = tokens(m[1].replace(/_/g, ''));
        if (w.length === 1 && (set[w[0]] === true || compound(w[0]))) return true;
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
    var tags = tagTest(lists.tags, lists.hashtagPrefixes, lists.hashtagWords);
    var shownWords = create(lists.shown, lists.suffixes, lists.allowed);
    return {
      typed: create(lists.typed, lists.suffixes, lists.allowed),
      shown: function (text) { return shownWords(text) || tags.hashtagged(text); },
      isTag: tags.isTag,
    };
  }

  return { create: create, fromLists: fromLists, tokens: tokens, readings: readings };
})();

if (typeof module !== 'undefined') module.exports = HisarMatcher;
