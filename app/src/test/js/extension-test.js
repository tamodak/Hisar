'use strict';
/*
 * Runs the browser extension's filter scripts against the cases the app's Guard is tested with
 * (src/test/resources/guard-cases.json). Started by GuardParityTest:
 *   node src/test/js/extension-test.js <lists.json> <cases.json>
 * where lists.json holds the word lists as the app hands them to the extension (GuardLists).
 */
const fs = require('fs');
const path = require('path');
const assert = require('assert');

const ext = path.join(__dirname, '../../main/assets/extensions/hisar');
const HisarMatcher = require(path.join(ext, 'matcher.js'));
const HisarGuard = require(path.join(ext, 'guard.js'));
const HisarHtmlFilter = require(path.join(ext, 'html-filter.js'));

const given = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
const cases = JSON.parse(fs.readFileSync(process.argv[3], 'utf8'));

const matchers = HisarMatcher.fromLists(given.lists);
const guard = HisarGuard.create({ ads: new Set(given.ads), harmful: new Set(given.harmful), typed: matchers.typed });

let failures = 0;
function check(name, fn) {
  try { fn(); } catch (e) { failures++; console.log('FAIL ' + name + ': ' + e.message); }
}

for (const c of cases.subresource) {
  check(c.url, () => assert.strictEqual(guard.subresource(c.url) || 'allow', c.expect));
}
for (const c of cases.text) {
  check('typed: ' + c.text, () => assert.strictEqual(matchers.typed(c.text), c.typed));
  check('shown: ' + c.text, () => assert.strictEqual(matchers.shown(c.text), c.shown));
}
for (const c of cases.html) {
  // Whole, and cut into pieces at every position: a word split between chunks is still found.
  check('html: ' + c.in, () => {
    const whole = HisarHtmlFilter.create(matchers.shown);
    assert.strictEqual(whole.write(c.in) + whole.end(), c.out);
    for (let cut = 1; cut < c.in.length; cut++) {
      const f = HisarHtmlFilter.create(matchers.shown);
      assert.strictEqual(f.write(c.in.slice(0, cut)) + f.write(c.in.slice(cut)) + f.end(), c.out, 'cut at ' + cut);
    }
  });
}

// Request bodies: search keys in JSON and forms, nothing else.
const typed = (s) => ({ raw: [{ bytes: new TextEncoder().encode(s).buffer }] });
check('json body', () => assert.strictEqual(guard.body(typed('{"context":{},"query":"free porn"}')), true));
check('nested json body', () => assert.strictEqual(guard.body(typed('{"variables":{"rawQuery":"nude"}}')), true));
check('clean json body', () => assert.strictEqual(guard.body(typed('{"query":"kittens","porn":"x"}')), false));
check('form body', () => assert.strictEqual(guard.body({ formData: { q: ['nude'] } }), true));
check('other form field', () => assert.strictEqual(guard.body({ formData: { comment: ['nude'] } }), false));

if (failures) { console.log(failures + ' failed'); process.exit(1); }
console.log('extension: all cases pass');
