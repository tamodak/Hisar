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
 * - Pictures are held until the app's classifier has judged them (image-filter.js).
 * - The content script (content.js), registered here with the app's word lists, handles what
 *   happens on the page itself.
 *
 * Nothing is let through before the lists have arrived: requests wait for them.
 */

var APP = 'hisar';

var lists = null;
var matchers = null;
/** The data filter (json-filter.js), built with the lists. */
var dataFilter = null;
var guard = null;
var simpleCss = null;
/**
 * Strategy switches for comparing alternatives (debug builds only; tools/experiments). Null in
 * normal use: the defaults apply and nothing is measured.
 */
var experiment = null;
function option(name, fallback) { return experiment && experiment[name] !== undefined ? experiment[name] : fallback; }
var resolveReady;
/**
 * Settles once the lists are in and the content script is registered: a page loaded before that
 * would run without the page filter.
 */
var ready = new Promise(function (resolve) { resolveReady = resolve; });
var isReady = false;
ready.then(function () { isReady = true; });

// --- The app ---------------------------------------------------------------------------------

var app = null;
var nextId = 1;
var pending = new Map();

/**
 * The port to the app. Messages sent before the app is listening wait in GeckoView's queue
 * (firefox-source-docs: GeckoView, "Interacting with Web content"), so one connection is enough;
 * it is only reopened if it ever drops.
 */
function connect() {
  var port = browser.runtime.connectNative(APP);
  app = port;
  port.onMessage.addListener(onAppMessage);
  port.onDisconnect.addListener(function () {
    if (app === port) app = null;
    pending.forEach(function (p) { p.reject(new Error('disconnected')); });
    pending.clear();
    setTimeout(connect, RECONNECT_MILLIS);
  });
  port.postMessage({ type: 'hello' });
}

/** Asks the app something and waits for its reply. Rejects if the app does not answer in time. */
function ask(message, timeoutMillis) {
  return new Promise(function (resolve, reject) {
    if (!app) { reject(new Error('no app')); return; }
    var id = nextId++;
    message.id = id;
    pending.set(id, { resolve: resolve, reject: reject });
    app.postMessage(message);
    setTimeout(function () {
      if (pending.delete(id)) reject(new Error('timeout'));
    }, timeoutMillis || ASK_TIMEOUT_MILLIS);
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
        dataFilter = HisarJsonFilter.create(matchers);
        return registerContentScripts();
      }).then(function () {
        resolveReady();
        // The app holds pages back until now.
        tell({ type: 'ready' });
      });
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
    case 'config':
      experiment = message.config ? JSON.parse(message.config) : null;
      if (lists) registerContentScripts();
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
  var data = 'var HISAR_DATA = ' + JSON.stringify({ lists: lists, simpleCss: simpleCss, config: experiment, measure: experiment !== null, inlineImageCss: HisarImageFilter.INLINE_CSS }) + ';';
  registerShadowHook(option('shadow', 'sweep') === 'main');
  return browser.contentScripts.register({
    matches: ['<all_urls>'],
    allFrames: true,
    matchAboutBlank: true,
    runAt: 'document_start',
    js: [{ code: data }, { file: 'matcher.js' }, { file: 'content.js' }],
    css: option('images', true) ? [{ code: HisarImageFilter.INLINE_CSS }] : [],
  }).then(function (script) {
    registered = script;
    if (previous) previous.unregister();
  }, function (e) {
    tell({ type: 'metrics', kind: 'error', message: 'content script: ' + e.message });
    throw e;
  });
}

/** The page-world half of the 'main' shadow strategy (shadow-hook.js). */
var shadowHook = false;
function registerShadowHook(wanted) {
  if (wanted === shadowHook || !browser.scripting) return;
  shadowHook = wanted;
  if (wanted) {
    browser.scripting.registerContentScripts([{
      id: 'shadow-hook', js: ['shadow-hook.js'], matches: ['<all_urls>'], runAt: 'document_start', allFrames: true, world: 'MAIN',
    }]).catch(function (e) { tell({ type: 'metrics', kind: 'error', message: 'shadow hook: ' + e.message }); });
  } else {
    browser.scripting.unregisterContentScripts({ ids: ['shadow-hook'] }).catch(function () {});
  }
}

browser.runtime.onMessage.addListener(function (message, sender) {
  // The content script learns which engine tab it is in, and tells the app (which knows its own
  // tabs only by their sessions).
  if (message && message.type === 'whoami') return Promise.resolve({ tabId: sender.tab ? sender.tab.id : -1 });
  // A picture the page holds itself (data: or blob:), and the outline mask of a covered one.
  if (message && message.type === 'judgeImage') {
    return ready.then(function () { return images.judgeBytes(message.bytes, sender.tab ? sender.tab.id : -1); });
  }
  if (message && message.type === 'outlineImage') return images.outline(message.url);
  return undefined;
});

// --- Requests --------------------------------------------------------------------------------

function onBeforeRequest(details) {
  if (!isReady) return ready.then(function () { return onBeforeRequest(details); });
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
 * Streams a response through [processor] ({ write(text), end(), result }), decoded in its own
 * charset and handed on as UTF-8: the Content-Type is rewritten to say so (the header outranks a
 * page's own <meta> declaration). [onResult] sees the processor's result after each piece and may
 * return true to cut the response off there.
 */
function streamThrough(details, headers, type, processor, onResult) {
  var value = type ? type.value : '';
  var declared = charsetOf(value);
  var filter = browser.webRequest.filterResponseData(details.requestId);
  var encoder = new TextEncoder();
  var decoder = declared ? decoderFor(declared) : null;
  var head = [];
  var headLength = 0;
  var spentMs = 0;
  var bytesIn = 0;
  var cut = false;

  function emit(text) {
    if (cut) return;
    if (text) filter.write(encoder.encode(text));
    if (onResult && onResult(processor.result)) cut = true;
  }

  filter.ondata = function (event) {
    var started = performance.now();
    var bytes = new Uint8Array(event.data);
    bytesIn += bytes.length;
    if (!decoder) {
      // No charset in the header: wait for enough of the document to find its <meta>.
      head.push(bytes);
      headLength += bytes.length;
      if (headLength < SNIFF_BYTES) return;
      bytes = join(head, headLength);
      head = [];
      decoder = decoderFor(sniffCharset(bytes));
    }
    emit(processor.write(decoder.decode(bytes, { stream: true })));
    spentMs += performance.now() - started;
  };
  filter.onstop = function () {
    var started = performance.now();
    if (!decoder) {
      var joined = join(head, headLength);
      decoder = decoderFor(sniffCharset(joined));
      emit(processor.write(decoder.decode(joined, { stream: true })));
    }
    emit(processor.write(decoder.decode()) + processor.end());
    filter.close();
    spentMs += performance.now() - started;
    if (experiment) {
      var r = processor.result || {};
      tell({ type: 'metrics', kind: 'data', resource: details.type, url: details.url.slice(0, 120), ms: Math.round(spentMs), bytes: bytesIn, removed: r.removed || 0, blanked: r.blanked || 0, whole: !!r.whole, samples: r.whole ? r.samples : undefined });
    }
  };
  filter.onerror = function () {};

  var rewritten = value.replace(/;\s*charset\s*=\s*"?[^";]+"?/i, '') + '; charset=utf-8';
  if (type) type.value = rewritten; else headers.push({ name: 'Content-Type', value: rewritten });
  return { responseHeaders: headers };
}

/**
 * Pages' HTML. By default only the data they embed is filtered (the JSON in their <script>s, which
 * pages like YouTube build themselves from): the page's own text is left to the content script,
 * since rewriting it makes frameworks' hydration fail (React errors on bbc.com). A page whose
 * embedded data is about blocked content (a video's player data with its tags) is cut off, and
 * the app leaves it.
 */
function onDocumentHeaders(details) {
  if (!guard || details.statusCode < 200 || details.statusCode >= 300) return {};
  var headers = details.responseHeaders || [];
  var type = header(headers, 'content-type');
  if (!/^\s*(text\/html|application\/xhtml\+xml)/i.test(type ? type.value : '')) return {};

  if (option('html', false)) {
    var html = HisarHtmlFilter.create(matchers.shown);
    return streamThrough(details, headers, type, { write: html.write, end: html.end, result: null });
  }
  if (!option('data', true)) return {};
  var doc = HisarJsonFilter.createDocument(dataFilter);
  var reported = false;
  return streamThrough(details, headers, type, doc, function (result) {
    if (!result.whole || reported) return false;
    reported = true;
    tell({ type: details.type === 'main_frame' ? 'pageBlocked' : 'dataBlocked', tabId: details.tabId });
    return details.type === 'main_frame';
  });
}

browser.webRequest.onHeadersReceived.addListener(onDocumentHeaders, { urls: ['<all_urls>'], types: ['main_frame', 'sub_frame'] }, ['blocking', 'responseHeaders']);

// --- Data pages fetch ------------------------------------------------------------------------

/**
 * JSON that pages fetch — search results, feeds, comments, a video's player data — filtered
 * whole before the page reads it (json-filter.js): entries with blocked words or tags are taken
 * out of their lists. A response that is about blocked content as a whole is replaced by an
 * empty object, and the app says so.
 */
function onDataHeaders(details) {
  if (!guard || !option('data', true) || details.statusCode < 200 || details.statusCode >= 300) return {};
  var headers = details.responseHeaders || [];
  var type = header(headers, 'content-type');
  if (!/json|text\/plain|javascript/i.test(type ? type.value : '')) return {};
  var parts = [];
  var length = 0;
  var processor = {
    result: { removed: 0, blanked: 0, whole: false },
    write: function (text) {
      // JSON can only be judged whole: held until the end, up to a limit.
      parts.push(text);
      length += text.length;
      return '';
    },
    end: function () {
      var text = parts.join('');
      parts = [];
      if (length > MAX_DATA) return text;
      var r = HisarJsonFilter.filterText(dataFilter, text);
      this.result = { removed: r.removed || 0, blanked: r.blanked || 0, whole: !!r.whole, samples: r.samples };
      if (r.whole) {
        tell({ type: 'dataBlocked', tabId: details.tabId });
        return '{}';
      }
      return r.text;
    },
  };
  return streamThrough(details, headers, type, processor, null);
}

browser.webRequest.onHeadersReceived.addListener(onDataHeaders, { urls: ['<all_urls>'], types: ['xmlhttprequest'] }, ['blocking', 'responseHeaders']);

function join(parts, length) {
  var out = new Uint8Array(length);
  var offset = 0;
  parts.forEach(function (p) { out.set(p, offset); offset += p.length; });
  return out;
}


// --- Pictures ------------------------------------------------------------------------------------

var images = HisarImageFilter.create(ask, tell, function (tabId, url, sure) {
  // Every frame of the tab hears of it; the one showing the picture acts (content.js).
  browser.tabs.sendMessage(tabId, { type: 'imageCovered', url: url, sure: sure }).catch(function () {});
}, function () { return experiment !== null; });

/**
 * Pictures, wherever they are loaded from: <img>, CSS backgrounds, posters, <picture> sources,
 * and pictures pages or their service workers fetch.
 */
function onImageHeaders(details) {
  // A picture revalidated from the cache (304) shows the cached bytes: judged like any other.
  var ok = (details.statusCode >= 200 && details.statusCode < 300) || details.statusCode === 304;
  if (!option('images', true) || !ok) return {};
  var type = header(details.responseHeaders || [], 'content-type');
  var value = type ? type.value : '';
  // Fetched data is judged only when it is a picture.
  if (details.type !== 'image' && details.type !== 'imageset' && !/^\s*image\//i.test(value)) return {};
  if (!isReady) return ready.then(function () { return onImageHeaders(details); });
  return images.onHeaders(details, value);
}

browser.webRequest.onHeadersReceived.addListener(onImageHeaders, { urls: ['<all_urls>'], types: ['image', 'imageset', 'xmlhttprequest', 'object', 'other'] }, ['blocking', 'responseHeaders']);

// --- YouTube Restricted Mode (off) -------------------------------------------------------------

/*
 * Off by default (user, 2026-10-03): in every level it hides all comments, which users don't
 * want; Hisar's own filtering of YouTube's data takes its place. Kept as an experiment option:
 * 'youtube': 'header' (Google's documented YouTube-Restrict header) or 'cookie' (the PREF flag).
 */
var YT_FLAG = 'f2';
var YT_RESTRICTED = '8000000';

function restrictedPref(current) {
  var pairs = (current || '').split('&').filter(function (p) { return p && p.indexOf(YT_FLAG + '=') !== 0; });
  pairs.push(YT_FLAG + '=' + YT_RESTRICTED);
  return pairs.join('&');
}

function onBeforeSendHeaders(details) {
  if (option('youtube', 'off') !== 'cookie') return {};
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

var YT_HEADER_HOSTS = ['*://www.youtube.com/*', '*://m.youtube.com/*', '*://youtubei.googleapis.com/*', '*://youtube.googleapis.com/*', '*://www.youtube-nocookie.com/*'];
browser.webRequest.onBeforeSendHeaders.addListener(function (details) {
  if (option('youtube', 'off') !== 'header') return {};
  var headers = (details.requestHeaders || []).filter(function (h) { return h.name.toLowerCase() !== 'youtube-restrict'; });
  headers.push({ name: 'YouTube-Restrict', value: 'Strict' });
  return { requestHeaders: headers };
}, { urls: YT_HEADER_HOSTS }, ['blocking', 'requestHeaders']);

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
/** Data responses longer than this (characters) are passed on unfiltered. */
var MAX_DATA = 16 * 1024 * 1024;

connect();

