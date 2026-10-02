package org.tamodak.hisar.web

import android.content.Context
import android.text.Html
import android.webkit.WebResourceResponse
import org.json.JSONObject
import org.tamodak.hisar.R
import org.tamodak.hisar.filter.BlockReason
import java.io.ByteArrayInputStream

/** The HTML Hisar serves in place of anything it refuses to show. */
object BlockedPage {

    fun response(context: Context, reason: BlockReason, detail: String): WebResourceResponse =
        html(render(context, reason, detail))

    /** An empty reply for a blocked image, script or XHR; the page just sees it fail. */
    fun emptyResponse(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", 403, "Blocked by Hisar", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    /**
     * Sends the tab to [url] in place of the requested page. WebView cannot answer an intercepted
     * request with an HTTP redirect, so this is a page that replaces itself.
     */
    fun redirect(url: String): WebResourceResponse {
        val js = JSONObject.quote(url)
        return html("<!doctype html><meta name=referrer content=no-referrer><script>location.replace($js)</script>")
    }

    private fun html(body: String) = WebResourceResponse(
        "text/html", "utf-8", 200, "OK",
        mapOf("Cache-Control" to "no-store"),
        ByteArrayInputStream(body.toByteArray()),
    )

    private fun render(context: Context, reason: BlockReason, detail: String): String {
        val explanation = context.getString(
            when (reason) {
                BlockReason.HARMFUL_SITE -> R.string.blocked_harmful
                BlockReason.AD_OR_TRACKER -> R.string.blocked_ads
                BlockReason.BLOCKED_WORDS -> R.string.blocked_words
                BlockReason.PAGE_CONTENT -> R.string.blocked_content
                BlockReason.UNSAFE_SEARCH_ENGINE -> R.string.blocked_search_engine
            },
        )
        // The matched word is withheld for word and content blocks: the page exists to stop the
        // reader seeing it.
        val shownDetail = when (reason) {
            BlockReason.HARMFUL_SITE, BlockReason.AD_OR_TRACKER, BlockReason.UNSAFE_SEARCH_ENGINE -> detail
            BlockReason.BLOCKED_WORDS, BlockReason.PAGE_CONTENT -> ""
        }
        fun esc(s: String) = Html.escapeHtml(s)
        return """
            <!doctype html>
            <html><head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>${esc(context.getString(R.string.blocked_title))}</title>
            <style>
              :root { color-scheme: light dark; --bg:#f6f7f9; --fg:#1d2330; --muted:#5b6475; --accent:#2f6fed; }
              @media (prefers-color-scheme: dark) { :root { --bg:#14171d; --fg:#e8ebf1; --muted:#9aa3b5; --accent:#7aa2ff; } }
              body { margin:0; min-height:100vh; display:flex; align-items:center; justify-content:center;
                     background:var(--bg); color:var(--fg); font-family:system-ui,sans-serif; }
              main { max-width:28rem; padding:2rem 1.5rem; text-align:center; }
              .shield { font-size:3.5rem; line-height:1; }
              h1 { font-size:1.4rem; margin:1rem 0 .5rem; }
              p { color:var(--muted); line-height:1.5; margin:.5rem 0; }
              code { font-size:.9rem; word-break:break-all; }
              button { margin-top:1.5rem; padding:.7rem 1.6rem; border:0; border-radius:999px;
                       background:var(--accent); color:#fff; font-size:1rem; }
            </style>
            </head><body><main>
              <div class="shield">🛡️</div>
              <h1>${esc(context.getString(R.string.blocked_title))}</h1>
              <p>${esc(explanation)}</p>
              ${if (shownDetail.isNotEmpty()) "<p><code>${esc(shownDetail)}</code></p>" else ""}
              <button onclick="location.href='hisar://back'">${esc(context.getString(R.string.go_back))}</button>
            </main></body></html>
        """.trimIndent()
    }
}
