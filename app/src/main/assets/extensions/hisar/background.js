'use strict';

/*
 * Hisar's filter, inside the browser engine. It sees every request any page, frame or service
 * worker makes, before it is sent, and every page's HTML before the page is parsed.
 *
 * - Top-level navigations are asked of the app (Guard.checkNavigation), so they follow exactly
 *   the rules the address bar does; the answer is to load, cancel, or rewrite for SafeSearch.
 * - Everything else is judged here (guard.js): blocked domains, blocked words in search
 *   parameters and request bodies, searches without strict SafeSearch.
 * - Pages' HTML has text with blocked words taken out as it streams in (html-filter.js).
 * - The content script (content.js), registered here with the app's word lists, handles what
 *   happens on the page itself.
 *
 * Nothing is let through before the lists have arrived: requests wait for them.
 */

var APP = 'hisar';

var lists = null;
var matchers = null;
var guard = null;
var simpleCss = null;
var resolveReady;
var ready = new Promise(function (resolve) { resolveReady = resolve; });

// --- The app ---------------------------------------------------------------------------------

var app = null;
var nextId = 1;
var pending = new Map();

/**
 * The port to the app. Opened at start and again whenever it drops; until the app answers the
 * hello, nothing is sent through it.
 */
function connect() {
  var port = browser.runtime.connectNative(APP);
  var answered = false;
  port.onMessage.addListener(function (message) {
    answered = true;
    app = port;
    onAppMessage(message);
  });
  port.onDisconnect.addListener(function () {
    if (app === port) app = null;
    pending.forEach(function (p) { p.reject(new Error('disconnected')); });
    pending.clear();
    setTimeout(connect, RECONNECT_MILLIS);
  });
  port.postMessage({ type: 'hello' });
  // The app may not be listening yet when the engine starts; try again until it is.
  setTimeout(function () { if (!answered) port.disconnect(); }, RECONNECT_MILLIS * 2);
}

/** Asks the app something and waits for its reply. Rejects if the app does not answer. */
function ask(message) {
  return new Promise(function (resolve, reject) {
    if (!app) { reject(new Error('no app')); return; }
    var id = nextId++;
    message.id = id;
    pending.set(id, { resolve: resolve, reject: reject });
    app.postMessage(message);
    setTimeout(function () {
      if (pending.delete(id)) reject(new Error('timeout'));
    }, ASK_TIMEOUT_MILLIS);
  });
}

function tell(message) {
  if (app) app.postMessage(message);
}

function onAppMessage(message) {
  switch (message.type) {
    case 'lists':
      lists = JSON.parse(message.lists);
      simpleCss = message.simpleCss || null;
      matchers = HisarMatcher.fromLists(lists);
      loadDomains().then(function (domains) {
        guard = HisarGuard.create({ ads: domains.ads, harmful: domains.harmful, typed: matchers.typed });
        return registerContentScripts();
      }).then(function () { resolveReady(); });
      break;
    case 'simpleMode':
      simpleCss = message.css || null;
      if (lists) registerContentScripts();
      // Pages already open are restyled in every frame; new ones get it from the registration.
      browser.tabs.query({}).then(function (tabs) {
        tabs.forEach(function (tab) {
          browser.tabs.sendMessage(tab.id, { type: 'simpleMode', css: simpleCss }).catch(function () {});
        });
      }, function () {});
      break;
    case 'welcome':
      // The app is listening; the lists follow once it has loaded them.
      break;
    case 'reply': {
      var p = pending.get(message.id);
      if (p) { pending.delete(message.id); p.resolve(message); }
      break;
    }
    case 'getCookies':
      cookieHeader(message.url).then(function (cookie) {
        tell({ type: 'reply', id: message.id, cookie: cookie });
      });
      break;
    case 'setCookies':
      Promise.all((message.cookies || []).map(function (c) { return setCookie(message.url, c); }))
        .then(function () { tell({ type: 'reply', id: message.id }); });
      break;
  }
}

function loadDomains() {
  function load(name) {
    return fetch(browser.runtime.getURL('lists/' + name + '.txt')).then(function (r) { return r.text(); }).then(function (text) {
      var set = new Set();
      text.split('\n').forEach(function (line) {
        var d = line.trim().toLowerCase();
        if (d && d[0] !== '#') set.add(d);
      });
      return set;
    });
  }
  return Promise.all([load('ads'), load('harmful')]).then(function (sets) { return { ads: sets[0], harmful: sets[1] }; });
}

// --- The content script ----------------------------------------------------------------------

var registered = null;

/** The content script, with the word lists and simple mode's style ready at document start. */
function registerContentScripts() {
  var previous = registered;
  var data = 'var HISAR_DATA = ' + JSON.stringify({ lists: lists, simpleCss: simpleCss }) + ';';
  return browser.contentScripts.register({
    matches: ['<all_urls>'],
    allFrames: true,
    matchAboutBlank: true,
    runAt: 'document_start',
    js: [{ code: data }, { file: 'matcher.js' }, { file: 'content.js' }],
  }).then(function (script) {
    registered = script;
    if (previous) previous.unregister();
  });
}

browser.runtime.onMessage.addListener(function (message, sender) {
  // The content script learns which engine tab it is in, and tells the app (which knows its own
  // tabs only by their sessions).
  if (message && message.type === 'whoami') return Promise.resolve({ tabId: sender.tab ? sender.tab.id : -1 });
  return undefined;
});

// --- Requests --------------------------------------------------------------------------------

function onBeforeRequest(details) {
  if (!guard) return ready.then(function () { return onBeforeRequest(details); });
  if (!/^https?:/.test(details.url)) return {};

  if (details.type === 'main_frame') {
    if (guard.body(details.requestBody)) {
      tell({ type: 'blocked', tabId: details.tabId, reason: 'words' });
      return { cancel: true };
    }
    return ask({ type: 'checkNavigation', url: details.url, tabId: details.tabId }).then(function (verdict) {
      if (verdict.action === 'block') return { cancel: true };
      if (verdict.action === 'redirect') return { redirectUrl: verdict.url };
      return {};
    }, function () {
      // No answer from the app: nothing loads unchecked.
      return { cancel: true };
    });
  }

  var reason = guard.subresource(details.url);
  if (reason === 'ad' || reason === 'harmful') {
    tell({ type: 'adBlocked', tabId: details.tabId });
    return { cancel: true };
  }
  if (reason) return { cancel: true };
  // What pages send in the background: YouTube's searches, GraphQL queries, form posts.
  if (guard.body(details.requestBody)) {
    tell({ type: 'blocked', tabId: details.tabId, reason: 'words' });
    return { cancel: true };
  }
  return {};
}

browser.webRequest.onBeforeRequest.addListener(onBeforeRequest, { urls: ['<all_urls>'] }, ['blocking', 'requestBody']);

// --- Pages' HTML -----------------------------------------------------------------------------

function header(headers, name) {
  for (var i = 0; i < headers.length; i++) if (headers[i].name.toLowerCase() === name) return headers[i];
  return null;
}

/** The charset a Content-Type names, or null. */
function charsetOf(contentType) {
  var m = /charset\s*=\s*"?([^";\s]+)/i.exec(contentType || '');
  return m ? m[1].toLowerCase() : null;
}

/** A <meta> charset declaration in the first bytes of a document, or null. */
function sniffCharset(bytes) {
  var head = new TextDecoder('latin1').decode(bytes.slice(0, SNIFF_BYTES));
  var m = /<meta[^>]+charset\s*=\s*["']?([a-z0-9_-]+)/i.exec(head);
  return m ? m[1].toLowerCase() : null;
}

function decoderFor(charset) {
  try { return new TextDecoder(charset || 'utf-8'); } catch (e) { return new TextDecoder('utf-8'); }
}

/**
 * Cleans a document's HTML. It is decoded in its own charset and handed on as UTF-8, so the
 * Content-Type is rewritten to say so (the header outranks the page's own <meta> declaration).
 */
function onHeadersReceived(details) {
  if (!guard || details.statusCode < 200 || details.statusCode >= 300) return {};
  var headers = details.responseHeaders || [];
  var type = header(headers, 'content-type');
  var value = type ? type.value : '';
  if (!/^\s*(text\/html|application\/xhtml\+xml)/i.test(value)) return {};

  var declared = charsetOf(value);
  var filter = browser.webRequest.filterResponseData(details.requestId);
  var html = HisarHtmlFilter.create(matchers.shown);
  var encoder = new TextEncoder();
  var decoder = declared ? decoderFor(declared) : null;
  var head = [];
  var headLength = 0;

  function emit(text) { if (text) filter.write(encoder.encode(text)); }
  function start(bytes) {
    decoder = decoderFor(sniffCharset(bytes));
  }

  filter.ondata = function (event) {
    var bytes = new Uint8Array(event.data);
    if (!decoder) {
      // No charset in the header: wait for enough of the document to find its <meta>.
      head.push(bytes);
      headLength += bytes.length;
      if (headLength < SNIFF_BYTES) return;
      var joined = join(head, headLength);
      head = [];
      start(joined);
      bytes = joined;
    }
    emit(html.write(decoder.decode(bytes, { stream: true })));
  };
  filter.onstop = function () {
    if (!decoder) {
      var joined = join(head, headLength);
      start(joined);
      emit(html.write(decoder.decode(joined, { stream: true })));
    }
    emit(html.write(decoder.decode()) + html.end());
    filter.close();
  };
  filter.onerror = function () {};

  var rewritten = value.replace(/;\s*charset\s*=\s*"?[^";]+"?/i, '') + '; charset=utf-8';
  if (type) type.value = rewritten; else headers.push({ name: 'Content-Type', value: rewritten });
  return { responseHeaders: headers };
}

function join(parts, length) {
  var out = new Uint8Array(length);
  var offset = 0;
  parts.forEach(function (p) { out.set(p, offset); offset += p.length; });
  return out;
}

browser.webRequest.onHeadersReceived.addListener(onHeadersReceived, { urls: ['<all_urls>'], types: ['main_frame', 'sub_frame'] }, ['blocking', 'responseHeaders']);

// --- YouTube Restricted Mode -------------------------------------------------------------------

/*
 * YouTube has no URL parameter for Restricted Mode; it is the f2=8000000 flag in its PREF cookie,
 * which YouTube rewrites as other preferences change. So the flag is merged back into every
 * top-level YouTube request, and stored for the requests that follow.
 */
var YT_FLAG = 'f2';
var YT_RESTRICTED = '8000000';

function restrictedPref(current) {
  var pairs = (current || '').split('&').filter(function (p) { return p && p.indexOf(YT_FLAG + '=') !== 0; });
  pairs.push(YT_FLAG + '=' + YT_RESTRICTED);
  return pairs.join('&');
}

function onBeforeSendHeaders(details) {
  var host = new URL(details.url).hostname;
  if (host !== 'youtube.com' && !/\.youtube\.com$/.test(host)) return {};
  var headers = details.requestHeaders || [];
  var cookie = header(headers, 'cookie');
  var cookies = cookie ? cookie.value.split(/;\s*/) : [];
  var current = null;
  cookies = cookies.filter(function (c) {
    if (c.indexOf('PREF=') === 0) { current = c.slice(5); return false; }
    return c.length > 0;
  });
  var pref = restrictedPref(current);
  cookies.push('PREF=' + pref);
  if (cookie) cookie.value = cookies.join('; '); else headers.push({ name: 'Cookie', value: cookies.join('; ') });
  if (pref !== current) {
    browser.cookies.set({
      url: 'https://' + host + '/', name: 'PREF', value: pref, domain: '.youtube.com', path: '/', secure: true,
      expirationDate: Math.floor(Date.now() / 1000) + 2 * 365 * 24 * 3600,
    }).catch(function () {});
  }
  return { requestHeaders: headers };
}

browser.webRequest.onBeforeSendHeaders.addListener(onBeforeSendHeaders, { urls: ['*://*.youtube.com/*', '*://youtube.com/*'], types: ['main_frame'] }, ['blocking', 'requestHeaders']);

// --- Cookies for the app ---------------------------------------------------------------------

/*
 * The engine keeps the cookies; the app needs them for downloads (Android's DownloadManager has
 * its own network stack) and for the Google Lens upload, which belongs to the Google session.
 */
function cookieHeader(url) {
  return browser.cookies.getAll({ url: url }).then(function (all) {
    return all.map(function (c) { return c.name + '=' + c.value; }).join('; ') || null;
  }, function () { return null; });
}

/** Stores a Set-Cookie header the app received, as the engine would have. */
function setCookie(url, setCookieHeader) {
  var parts = setCookieHeader.split(';').map(function (p) { return p.trim(); });
  var first = parts.shift() || '';
  var eq = first.indexOf('=');
  if (eq <= 0) return Promise.resolve();
  var cookie = { url: url, name: first.slice(0, eq), value: first.slice(eq + 1) };
  parts.forEach(function (p) {
    var i = p.indexOf('=');
    var key = (i < 0 ? p : p.slice(0, i)).toLowerCase();
    var value = i < 0 ? '' : p.slice(i + 1);
    if (key === 'domain') cookie.domain = value;
    else if (key === 'path') cookie.path = value;
    else if (key === 'secure') cookie.secure = true;
    else if (key === 'httponly') cookie.httpOnly = true;
    else if (key === 'max-age') cookie.expirationDate = Math.floor(Date.now() / 1000) + parseInt(value, 10);
    else if (key === 'expires' && cookie.expirationDate === undefined) {
      var t = Date.parse(value);
      if (!isNaN(t)) cookie.expirationDate = Math.floor(t / 1000);
    } else if (key === 'samesite') {
      var s = value.toLowerCase();
      cookie.sameSite = s === 'none' ? 'no_restriction' : s === 'strict' ? 'strict' : 'lax';
    }
  });
  return browser.cookies.set(cookie).catch(function () {});
}

var RECONNECT_MILLIS = 500;
var ASK_TIMEOUT_MILLIS = 10000;
var SNIFF_BYTES = 1024;

connect();
