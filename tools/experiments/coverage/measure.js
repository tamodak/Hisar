'use strict';
// Runs the extension's data filter, with the current lists, over the cached searches (see README).
const fs = require('fs');
const path = require('path');
const ext = path.join(__dirname, '../../../app/src/main/assets/extensions/hisar');
const M = require(path.join(ext, 'matcher.js'));
const J = require(path.join(ext, 'json-filter.js'));
const lines = (f) => fs.readFileSync(path.join(ext, 'lists', f), 'utf8').split('\n').map((s) => s.trim()).filter(Boolean);
const SUFFIXES = ['lerin', 'ların', 'leri', 'ları', 'lere', 'lara', 'lerde', 'larda', 'lerden', 'lardan', 'ler', 'lar', 'nın', 'nin', 'nun', 'nün', 'ın', 'in', 'un', 'ün', 'yla', 'yle', 'la', 'le', 'dan', 'den', 'tan', 'ten', 'da', 'de', 'ta', 'te', 'ya', 'ye', 'yı', 'yi', 'yu', 'yü', 'sı', 'si', 'su', 'sü', 'a', 'e', 'ı', 'i', 'u', 'ü'];
function list(files) {
  const entries = [].concat(...files.map(lines));
  const phrases = entries.map(M.tokens).filter((t) => t.length);
  const single = new Set(phrases.filter((p) => p.length === 1).map((p) => p[0]));
  return { phrases, symbols: entries.filter((w) => M.tokens(w).length === 0), embedded: lines('substring_words.txt').filter((w) => single.has(w)), stems: lines('suffix_stems.txt').filter((w) => single.has(w)) };
}
const matchers = M.fromLists({
  typed: list(['words.txt', 'search_words.txt']), shown: list(['words.txt']), suffixes: SUFFIXES,
  tags: lines('tag_words.txt'), hashtagPrefixes: lines('hashtag_prefixes.txt'), hashtagWords: lines('hashtag_words.txt'),
  allowed: lines('allowed_phrases.txt').map(M.tokens),
});
function titles(data) {
  const out = [];
  (function walk(o) {
    if (!o || typeof o !== 'object') return;
    for (const k in o) {
      if (k === 'videoWithContextRenderer') {
        const v = o[k];
        out.push((v.headline.runs || []).map((r) => r.text).join('') + '  [' + ((v.shortBylineText || {}).runs || []).map((r) => r.text).join('') + ']');
      }
      walk(o[k]);
    }
  })(data);
  return out;
}
for (const set of ['relevant', 'innocent']) {
  const dir = path.join(__dirname, 'data', set);
  if (!fs.existsSync(dir)) continue;
  let total = 0, removed = 0;
  const report = [];
  for (const f of fs.readdirSync(dir).sort()) {
    const data = JSON.parse(fs.readFileSync(path.join(dir, f), 'utf8'));
    const before = titles(data);
    J.create(matchers)(data);
    const after = new Set(titles(data));
    total += before.length;
    const gone = before.filter((t) => !after.has(t));
    removed += gone.length;
    const shown = set === 'relevant' ? before.filter((t) => after.has(t)).map((t) => '  kept    ' + t) : gone.map((t) => '  REMOVED ' + t);
    if (shown.length) report.push(f.slice(0, -5) + ': ' + gone.length + '/' + before.length + ' removed', ...shown);
  }
  console.log(`== ${set}: ${removed} of ${total} removed`);
  console.log(report.join('\n'));
}
