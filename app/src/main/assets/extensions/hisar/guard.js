'use strict';

/**
 * What a page loads besides its own address — images, scripts, frames, XHR, fetches, service
 * workers — judged as the app's Guard.checkSubresource judges it (and tested against the same
 * cases, app/src/test/resources/guard-cases.json): blocked domains, blocked words in search
 * parameters, and searches without strict SafeSearch.
 *
 * Top-level navigations are decided by the app itself (Guard.checkNavigation), not here.
 */
var HisarGuard = (function () {
  /** Query parameters that carry what a user typed (Guard.SEARCH_PARAMS). */
  var SEARCH_PARAMS = { q: 1, oq: 1, pq: 1, query: 1, search_query: 1, p: 1, text: 1, keyword: 1, keywords: 1, k: 1, term: 1, search: 1, wd: 1 };
  /** A search-like key's string value inside JSON (Guard.JSON_SEARCH_VALUE). */
  var JSON_SEARCH_VALUE = /"(?:q|query|rawQuery|searchQuery|search_query|search|term|searchTerm|keyword|keywords|text)"\s*:\s*"((?:[^"\\]|\\.)*)"/gi;
  /** Search-like keys in request bodies. */
  var SEARCH_KEY = /^(q|query|rawquery|searchquery|search_query|search|term|searchterm|keyword|keywords|text|oq|pq)$/i;

  /** The original host behind a Google Translate proxy host ("a--b-example-com.translate.goog"). */
  function translatedHost(host) {
    if (!/\.translate\.goog$/.test(host)) return null;
    return host.slice(0, -'.translate.goog'.length).replace(/--/g, '\u0000').replace(/-/g, '.').replace(/\u0000/g, '-');
  }

  /** The listed domain [host] falls under, or null: a listed domain covers its subdomains. */
  function listed(set, host) {
    var candidate = host.toLowerCase().replace(/\.+$/, '');
    while (candidate) {
      if (set.has(candidate)) return candidate;
      var dot = candidate.indexOf('.');
      if (dot < 0) return null;
      candidate = candidate.slice(dot + 1);
    }
    return null;
  }

  /** `<engine>.<tld>` or a subdomain of one, e.g. www.google.com.tr (SafeSearch.isEngine). */
  function isEngine(host, name) {
    var labels = host.split('.');
    var i = labels.indexOf(name);
    var tld = labels.length - i - 1;
    return i >= 0 && tld >= 1 && tld <= 2;
  }

  /** The strict-SafeSearch parameter for a search URL, as SafeSearch.rules has them. */
  function safeSearchRule(url) {
    var host = url.hostname, path = url.pathname, params = url.searchParams;
    if (isEngine(host, 'google') && (path === '/search' || (path === '/images' && params.has('q')))) return ['safe', 'active'];
    if (isEngine(host, 'bing') && /\/search$/.test(path)) return ['adlt', 'strict'];
    if (isEngine(host, 'duckduckgo') && params.has('q')) return ['kp', '1'];
    if (/search\.yahoo\.com$/.test(host) && path.indexOf('/search') === 0) return ['vm', 'r'];
    if (host === 'search.brave.com' && ['/search', '/images', '/videos'].indexOf(path) >= 0) return ['safesearch', 'strict'];
    return null;
  }

  /** True for a search that would not carry strict SafeSearch (SafeSearch.enforce != null). */
  function lacksSafeSearch(url) {
    var rule = safeSearchRule(url);
    if (!rule) return false;
    var values = url.searchParams.getAll(rule[0]);
    return !(values.length === 1 && values[0] === rule[1]);
  }

  /**
   * [lists]: { ads: Set, harmful: Set, typed: matcher }. Returns the checks:
   * - subresource(url): null to allow, or the reason it is blocked ('harmful', 'ad', 'words',
   *   'safesearch');
   * - domain(host): the same for a host alone;
   * - body(requestBody): true if a request body sends a blocked word under a search-like key.
   */
  function create(lists) {
    function domain(host) {
      var hosts = [host];
      var original = translatedHost(host);
      if (original) hosts.push(original);
      for (var i = 0; i < hosts.length; i++) {
        if (listed(lists.harmful, hosts[i])) return 'harmful';
        if (listed(lists.ads, hosts[i])) return 'ad';
      }
      return null;
    }

    function subresource(raw) {
      var url;
      try { url = new URL(raw); } catch (e) { return null; }
      if (url.protocol !== 'http:' && url.protocol !== 'https:') return null;
      var byDomain = domain(url.hostname);
      if (byDomain) return byDomain;
      // Searches in plain parameters (q=…), and inside JSON-valued ones such as GraphQL's
      // variables={"rawQuery":"…"} (X, Instagram).
      var typed = [];
      url.searchParams.forEach(function (value, name) {
        if (SEARCH_PARAMS[name.toLowerCase()] === 1) {
          typed.push(value);
        } else if (/^\s*\{/.test(value)) {
          var m;
          JSON_SEARCH_VALUE.lastIndex = 0;
          while ((m = JSON_SEARCH_VALUE.exec(value)) !== null) typed.push(m[1]);
        }
      });
      if (typed.length && lists.typed(typed.join(' '))) return 'words';
      // A search fetched in the background (instant results) can't be redirected, so one that
      // would not carry strict SafeSearch is dropped.
      if (lacksSafeSearch(url)) return 'safesearch';
      return null;
    }

    function keyed(obj, depth) {
      if (!obj || typeof obj !== 'object' || depth > 6) return false;
      for (var k in obj) {
        if (!Object.prototype.hasOwnProperty.call(obj, k)) continue;
        var v = obj[k];
        if (typeof v === 'string' && SEARCH_KEY.test(k) && lists.typed(v)) return true;
        if (typeof v === 'object' && keyed(v, depth + 1)) return true;
      }
      return false;
    }

    /** A body as text: JSON is checked by key, anything else as form fields. */
    function textBlocked(text) {
      var t = text.trim();
      if (t[0] === '{' || t[0] === '[') {
        try { return keyed(JSON.parse(t), 0); } catch (e) { return false; }
      }
      var hit = false;
      try {
        new URLSearchParams(t).forEach(function (v, k) { if (SEARCH_KEY.test(k) && lists.typed(v)) hit = true; });
      } catch (e) {}
      return hit;
    }

    /** webRequest's requestBody: parsed form fields, or raw bytes (fetch and XHR bodies). */
    function body(requestBody) {
      if (!requestBody) return false;
      if (requestBody.formData) {
        for (var k in requestBody.formData) {
          if (!SEARCH_KEY.test(k)) continue;
          var values = requestBody.formData[k];
          for (var i = 0; i < values.length; i++) if (lists.typed(values[i])) return true;
        }
        return false;
      }
      if (requestBody.raw) {
        var decoder = new TextDecoder('utf-8');
        var text = '';
        for (var j = 0; j < requestBody.raw.length && text.length < MAX_BODY; j++) {
          var part = requestBody.raw[j];
          if (part.bytes) text += decoder.decode(part.bytes, { stream: true });
        }
        return textBlocked(text.slice(0, MAX_BODY));
      }
      return false;
    }

    return { subresource: subresource, domain: domain, body: body };
  }

  /** Bodies are read up to this many characters: searches are short, uploads are not. */
  var MAX_BODY = 65536;

  return { create: create, translatedHost: translatedHost };
})();

if (typeof module !== 'undefined') module.exports = HisarGuard;
