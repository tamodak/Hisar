package org.tamodak.hisar.web

import android.text.Html
import org.json.JSONObject
import org.tamodak.hisar.data.model.SimpleFont

/** Scripts and HTML Hisar puts into pages: simple mode's restyling and reader mode. */
object PageStyles {
    private const val STYLE_ID = "hisar-simple-mode"

    /** One font, one size, white text on black, for every element. Links stay underlined so they can be found. */
    fun simpleModeCss(font: SimpleFont): String = """
        html, body, body * {
          background: #000 !important; background-image: none !important;
          color: #fff !important; font-family: ${font.css} !important;
          font-size: 18px !important; line-height: 1.5 !important;
          font-weight: normal !important; font-style: normal !important;
          text-shadow: none !important; box-shadow: none !important;
          border-color: #555 !important; text-decoration: none !important;
        }
        a, a * { text-decoration: underline !important; }
        img, video, svg, canvas, picture { background: transparent !important; }
    """.trimIndent()

    /** Installs (or, with null, removes) the simple-mode stylesheet in the current document. */
    fun applyScript(css: String?): String {
        val text = JSONObject.quote(css.orEmpty())
        return """
            (function(){
              var s = document.getElementById('$STYLE_ID');
              if (${css == null}) { if (s) s.remove(); return; }
              if (!s) { s = document.createElement('style'); s.id = '$STYLE_ID'; }
              s.textContent = $text;
              (document.head || document.documentElement).appendChild(s);
            })();
        """.trimIndent()
    }

    /**
     * Finds the article in a page: the element whose paragraphs hold the most text. Returns JSON
     * {title, html, length} with only reading markup kept (headings, paragraphs, lists, quotes,
     * images, links), or null-ish length 0 when the page is not an article.
     */
    const val READER_EXTRACT_JS = """
(function(){
  var best = null, bestScore = 0, scores = new Map();
  document.querySelectorAll('p').forEach(function(p){
    if (p.closest('nav,aside,footer,header,form,[role=navigation],[aria-hidden=true]')) return;
    var len = (p.innerText || '').trim().length;
    if (len < 40) return;
    var parent = p.parentElement;
    var s = (scores.get(parent) || 0) + len;
    scores.set(parent, s);
    if (s > bestScore) { bestScore = s; best = parent; }
  });
  if (!best || bestScore < 600) return JSON.stringify({length: 0});
  var root = best.closest('article') || best;
  var keep = {P:1,H1:1,H2:1,H3:1,H4:1,H5:1,H6:1,UL:1,OL:1,LI:1,BLOCKQUOTE:1,PRE:1,FIGURE:1,FIGCAPTION:1,EM:1,STRONG:1,B:1,I:1,A:1,BR:1,IMG:1,CODE:1};
  var skip = {SCRIPT:1,STYLE:1,NAV:1,ASIDE:1,FOOTER:1,FORM:1,BUTTON:1,IFRAME:1,NOSCRIPT:1,SVG:1};
  function esc(t){ return t.replace(/[&<>"]/g, function(c){ return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]; }); }
  function walk(n){
    if (n.nodeType === 3) return esc(n.textContent);
    if (n.nodeType !== 1 || skip[n.tagName]) return '';
    var inner = '';
    n.childNodes.forEach(function(c){ inner += walk(c); });
    var t = n.tagName;
    if (t === 'IMG') { var src = n.currentSrc || n.src; return src && src.indexOf('http') === 0 ? '<img src="' + esc(src) + '">' : ''; }
    if (t === 'A') { return n.href && n.href.indexOf('http') === 0 ? '<a href="' + esc(n.href) + '">' + inner + '</a>' : inner; }
    if (t === 'BR') return '<br>';
    if (keep[t]) return '<' + t.toLowerCase() + '>' + inner + '</' + t.toLowerCase() + '>';
    return inner;
  }
  var h1 = document.querySelector('h1');
  return JSON.stringify({
    title: (h1 && h1.innerText.trim()) || document.title,
    html: walk(root),
    length: bestScore
  });
})()
"""

    /** The reader page around extracted article HTML. */
    fun readerHtml(title: String, host: String, articleHtml: String, dark: Boolean, font: String): String {
        val bg = if (dark) "#121212" else "#fafafa"
        val fg = if (dark) "#e8e8e8" else "#1d1d1d"
        val muted = if (dark) "#9e9e9e" else "#666"
        val link = if (dark) "#8ab4f8" else "#1a5fd0"
        return """
            <!doctype html><html><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>${Html.escapeHtml(title)}</title>
            <style>
              body { margin: 0; background: $bg; color: $fg; font-family: $font; }
              main { max-width: 42rem; margin: 0 auto; padding: 1.5rem 1.25rem 4rem; font-size: 1.15rem; line-height: 1.65; }
              .host { color: $muted; font-size: .9rem; font-family: sans-serif; }
              h1 { font-size: 1.7rem; line-height: 1.25; margin: .4rem 0 1.2rem; }
              h2, h3, h4 { line-height: 1.3; margin-top: 1.6em; }
              img { max-width: 100%; height: auto; border-radius: 8px; display: block; margin: 1rem auto; }
              figcaption { color: $muted; font-size: .9rem; }
              blockquote { border-left: 3px solid $muted; margin: 1em 0; padding-left: 1em; color: $muted; }
              a { color: $link; }
              pre { white-space: pre-wrap; }
            </style></head>
            <body><main>
              <div class="host">${Html.escapeHtml(host)}</div>
              <h1>${Html.escapeHtml(title)}</h1>
              $articleHtml
            </main></body></html>
        """.trimIndent()
    }
}
