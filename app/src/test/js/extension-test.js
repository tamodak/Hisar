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
const pending = [];
function check(name, fn) {
  const fail = (e) => { failures++; console.log('FAIL ' + name + ': ' + (e && e.stack || e)); };
  try {
    const r = fn();
    if (r && r.then) pending.push(r.catch(fail));
  } catch (e) { fail(e); }
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

// Data sites send: the smallest entry with a match goes; a response about one blocked thing is
// blocked whole.
const HisarJsonFilter = require(path.join(ext, 'json-filter.js'));
const dataFilter = HisarJsonFilter.create(matchers);
for (const c of cases.json) {
  check('json: ' + c.name, () => {
    const value = JSON.parse(JSON.stringify(c.in));
    const r = dataFilter(value);
    assert.strictEqual(r.whole, c.whole, 'whole');
    if (c.keep) {
      const texts = JSON.stringify(value);
      for (const k of c.keep) assert.ok(texts.includes(k), 'keeps ' + k);
      const kept = (value.contents || value.items).length;
      assert.strictEqual(kept, c.keep.length, 'entries kept');
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

// The background script starts, takes the lists from the app, and filters a data response,
// with a stand-in for the browser's extension APIs.
check('background starts', () => {
  const vm = require('vm');
  const listeners = {};
  const event = (name) => ({ addListener: (fn) => { (listeners[name] = listeners[name] || []).push(fn); } });
  const sent = [];
  let appListener = null;
  const port = { onMessage: { addListener: (fn) => { appListener = fn; } }, onDisconnect: event('disconnect'), postMessage: (m) => sent.push(m), disconnect: () => {} };
  const filters = [];
  const browser = {
    runtime: { connectNative: () => port, onMessage: event('runtime.onMessage'), getURL: (p) => p },
    webRequest: {
      onBeforeRequest: event('onBeforeRequest'), onHeadersReceived: event('onHeadersReceived'), onBeforeSendHeaders: event('onBeforeSendHeaders'),
      filterResponseData: () => { const f = { written: [], write: (b) => f.written.push(Buffer.from(b).toString()), close: () => { f.closed = true; } }; filters.push(f); return f; },
    },
    contentScripts: { register: () => Promise.resolve({ unregister: () => {} }) },
    scripting: { registerContentScripts: () => Promise.resolve(), unregisterContentScripts: () => Promise.resolve() },
    cookies: { getAll: () => Promise.resolve([]), set: () => Promise.resolve() },
    tabs: { query: () => Promise.resolve([]), sendMessage: () => Promise.resolve() },
  };
  const context = vm.createContext({
    browser, console, setTimeout, clearTimeout, Promise, URL, URLSearchParams, TextEncoder, TextDecoder, performance,
    fetch: () => Promise.resolve({ text: () => Promise.resolve(given.harmful.join('\n')) }),
  });
  for (const f of JSON.parse(fs.readFileSync(path.join(ext, 'manifest.json'), 'utf8')).background.scripts) {
    vm.runInContext(fs.readFileSync(path.join(ext, f), 'utf8'), context, { filename: f });
  }
  assert.ok(sent.some((m) => m.type === 'hello'), 'says hello to the app');
  assert.ok((listeners.onBeforeRequest || []).length > 0, 'filters requests');
  assert.ok((listeners.onHeadersReceived || []).length >= 2, 'filters documents and data');
  context.__lists = JSON.stringify(given.lists);
  appListener({ type: 'lists', lists: context.__lists, simpleCss: null });
  return new Promise((resolve) => setTimeout(resolve, 50)).then(() => {
    // A data response: the blocked entry goes, the rest stays.
    const dataListener = listeners.onHeadersReceived[1];
    dataListener({ requestId: 1, statusCode: 200, type: 'xmlhttprequest', tabId: 1, url: 'https://example.com/api',
      responseHeaders: [{ name: 'Content-Type', value: 'application/json' }] });
    const f = filters[filters.length - 1];
    f.ondata({ data: new TextEncoder().encode(JSON.stringify({ items: [
      { title: 'free porn here', channel: 'someone', views: '10 views' },
      { title: 'kittens playing', channel: 'cats', views: '20 views' },
    ] })).buffer });
    f.onstop();
    const out = JSON.parse(f.written.join(''));
    assert.deepStrictEqual(out.items.map((i) => i.title), ['kittens playing']);
  });
});

Promise.all(pending).then(() => {
  if (failures) { console.log(failures + ' failed'); process.exit(1); }
  console.log('extension: all cases pass');
});
