'use strict';

/**
 * Pictures, judged before they are drawn. Every image a page, frame or service worker loads is
 * held back whole (webRequest.filterResponseData, as the Wingman Jr. extension does) until the
 * app's classifier has looked at it:
 *
 * - safe: the original bytes go on unchanged;
 * - not safe (a woman's or girl's bare body beyond her face and hands, or nudity): a plain grey
 *   picture of the same size goes on instead, so the layout stays and nothing of it is ever shown.
 *   The page is told (content.js): a video's thumbnail the classifier is sure of takes its video
 *   out of the list; any other picture asks for the slower outline mask, which covers only the
 *   people in it.
 *
 * Pictures are decoded here, by the engine, which reads every format the page could show, and
 * handed to the app shrunk to the classifier's needs. Icons and pictures too small to show a
 * person are let through. When the app gives no answer, the picture is covered: nothing unjudged
 * is shown.
 */
var HisarImageFilter = (function () {
  /** Pictures this small (bytes, or pixels on the short side) are icons, dots and spacers. */
  var MIN_BYTES = 1200;
  var MIN_SIDE = 48;
  /** The classifier gets pictures at most this big (long side); it looks at 224 x 224. */
  var CLASSIFY_SIDE = 448;
  /** The outline mask gets them at most this big. */
  var OUTLINE_SIDE = 1024;
  var JPEG_QUALITY = 0.9;
  /** Verdicts remembered by address, so a picture shown again (from the cache) is not judged again. */
  var MAX_VERDICTS = 4000;
  /** Covered originals kept for the outline mask, by total size. */
  var MAX_KEPT_BYTES = 48 * 1024 * 1024;
  var PLACEHOLDER = '#e8eaed';
  /** Image types that are drawings of shapes (icons, logos) rather than pictures. */
  var NOT_PICTURE = /^\s*image\/(svg\+xml|x-icon|vnd\.microsoft\.icon)/i;
  /**
   * Pictures a page holds itself are invisible until content.js has had them judged; tiny ones
   * (icons) it marks at once.
   */
  var INLINE_CSS = 'img[src^="data:image/"]:not([src^="data:image/svg"]):not([data-hisar-image-ok]), img[src^="blob:"]:not([data-hisar-image-ok]) { visibility: hidden !important; }';

  /**
   * [ask(message, timeoutMillis)]: a promise of the app's reply. [tell(message)]: a message to the
   * app. [onCovered(tabId, url, sure)]: a covered picture, for its page; [sure] when the classifier
   * is confident, not only cautious.
   */
  function create(ask, tell, onCovered, measure) {
    var verdicts = new Map();
    var kept = new Map();
    var keptBytes = 0;
    var nextInline = 1;

    function remember(url, verdict) {
      if (!url) return;
      verdicts.delete(url);
      verdicts.set(url, verdict);
      if (verdicts.size > MAX_VERDICTS) verdicts.delete(verdicts.keys().next().value);
    }

    function keep(url, blob) {
      if (!url || kept.has(url)) return;
      kept.set(url, blob);
      keptBytes += blob.size;
      while (keptBytes > MAX_KEPT_BYTES && kept.size > 1) {
        var oldest = kept.keys().next().value;
        keptBytes -= kept.get(oldest).size;
        kept.delete(oldest);
      }
    }

    /** A grey PNG of [width] x [height]. */
    function placeholder(width, height) {
      var canvas = new OffscreenCanvas(width, height);
      var g = canvas.getContext('2d');
      g.fillStyle = PLACEHOLDER;
      g.fillRect(0, 0, width, height);
      return canvas.convertToBlob({ type: 'image/png' });
    }

    /** [bitmap] shrunk to fit [side], as base64 JPEG. */
    function encode(bitmap, side) {
      var scale = Math.min(1, side / Math.max(bitmap.width, bitmap.height));
      var w = Math.max(1, Math.round(bitmap.width * scale));
      var h = Math.max(1, Math.round(bitmap.height * scale));
      var canvas = new OffscreenCanvas(w, h);
      var g = canvas.getContext('2d');
      // Transparent pictures are judged on white, as pages usually show them.
      g.fillStyle = '#fff';
      g.fillRect(0, 0, w, h);
      g.imageSmoothingQuality = 'high';
      g.drawImage(bitmap, 0, 0, w, h);
      return canvas.convertToBlob({ type: 'image/jpeg', quality: JPEG_QUALITY }).then(base64);
    }

    function base64(blob) {
      return blob.arrayBuffer().then(function (buffer) {
        var bytes = new Uint8Array(buffer);
        var parts = [];
        for (var i = 0; i < bytes.length; i += 0x8000) parts.push(String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000)));
        return btoa(parts.join(''));
      });
    }

    /**
     * Judges a picture's bytes. Resolves to null when it may be shown as it is, else to
     * { placeholder: a Blob to show instead, sure }.
     */
    function judge(blob, url, tabId) {
      if (blob.size < MIN_BYTES) return Promise.resolve(null);
      var started = performance.now();
      return createImageBitmap(blob).then(function (bitmap) {
        if (Math.min(bitmap.width, bitmap.height) < MIN_SIDE) { bitmap.close(); return null; }
        var width = bitmap.width, height = bitmap.height;
        return encode(bitmap, CLASSIFY_SIDE).then(function (image) {
          bitmap.close();
          return ask({ type: 'classifyImage', image: image, tabId: tabId }, CLASSIFY_TIMEOUT_MILLIS).then(function (reply) {
            return { cover: reply.cover !== false, sure: reply.sure === true };
          }, function () { return { cover: true, sure: false }; });
        }).then(function (verdict) {
          var cover = verdict.cover;
          if (measure && measure()) tell({ type: 'metrics', kind: 'image', url: (url || '').slice(0, 120), ms: Math.round(performance.now() - started), cover: cover, width: width, height: height });
          remember(url, cover ? { width: width, height: height, sure: verdict.sure } : SAFE);
          if (!cover) return null;
          keep(url, blob);
          return placeholder(width, height).then(function (png) { return { placeholder: png, sure: verdict.sure }; });
        });
      }, function () {
        // Not a picture the engine can draw: it shows nothing either way.
        return null;
      });
    }

    /** A picture's response: held whole, judged, then passed on or replaced. */
    function onHeaders(details, contentType) {
      if (NOT_PICTURE.test(contentType || '')) return {};
      var known = verdicts.get(details.url);
      if (known === SAFE) return {};
      var filter = browser.webRequest.filterResponseData(details.requestId);
      if (known) {
        // Covered before (shown again, from the cache): its placeholder goes on at once.
        filter.ondata = function () {};
        filter.onstop = function () {
          if (details.tabId >= 0) onCovered(details.tabId, details.url, known.sure);
          placeholder(known.width, known.height).then(function (out) { return out.arrayBuffer(); }).then(function (buffer) {
            filter.write(buffer);
            filter.close();
          }, function () { filter.close(); });
        };
        filter.onerror = function () {};
        return {};
      }
      var parts = [];
      filter.ondata = function (event) { parts.push(event.data); };
      filter.onstop = function () {
        var blob = new Blob(parts);
        parts = [];
        judge(blob, details.url, details.tabId).then(function (covered) {
          if (!covered) return blob;
          if (details.tabId >= 0) onCovered(details.tabId, details.url, covered.sure);
          return covered.placeholder;
        }).then(function (out) {
          return out.arrayBuffer();
        }).then(function (buffer) {
          filter.write(buffer);
          filter.close();
        }, function () {
          // Something failed on the way: nothing is shown.
          filter.close();
        });
      };
      filter.onerror = function () {};
      return {};
    }

    /** A picture a page holds itself (a data: or blob: address), sent by content.js. */
    function judgeBytes(buffer, tabId) {
      var blob = new Blob([buffer]);
      // Its key stands for an address when the page asks for its outline mask.
      var key = 'inline:' + (nextInline++);
      return judge(blob, key, tabId).then(function (covered) {
        if (!covered) return { cover: false };
        return base64(covered.placeholder).then(function (png) {
          return { cover: true, sure: covered.sure, placeholder: 'data:image/png;base64,' + png, key: key };
        });
      });
    }

    /** The outline-masked picture for a covered one, as a data: address, or null. */
    function outline(url) {
      var blob = kept.get(url);
      if (!blob) return Promise.resolve(null);
      return createImageBitmap(blob).then(function (bitmap) {
        return encode(bitmap, OUTLINE_SIDE).then(function (image) {
          bitmap.close();
          return ask({ type: 'outlineImage', image: image }, OUTLINE_TIMEOUT_MILLIS);
        });
      }).then(function (reply) {
        return reply && reply.image ? 'data:image/jpeg;base64,' + reply.image : null;
      }, function () { return null; });
    }

    return { onHeaders: onHeaders, judgeBytes: judgeBytes, outline: outline };
  }

  var SAFE = { safe: true };
  /** The classifier queues pictures; a page with many waits its turn, but never forever. */
  var CLASSIFY_TIMEOUT_MILLIS = 30000;
  var OUTLINE_TIMEOUT_MILLIS = 120000;

  return { create: create, INLINE_CSS: INLINE_CSS };
})();

if (typeof module !== 'undefined') module.exports = HisarImageFilter;
