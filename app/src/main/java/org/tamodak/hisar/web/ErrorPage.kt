package org.tamodak.hisar.web

import android.content.Context
import android.text.Html
import android.util.Base64
import org.mozilla.geckoview.WebRequestError
import org.tamodak.hisar.R

/** The page shown when a site can't be loaded: offline, unknown address, bad certificate. */
object ErrorPage {
    fun uri(context: Context, error: WebRequestError): String {
        val message = context.getString(
            when {
                error.code == WebRequestError.ERROR_OFFLINE -> R.string.page_error_offline
                error.code == WebRequestError.ERROR_UNKNOWN_HOST -> R.string.page_error_host
                error.category == WebRequestError.ERROR_CATEGORY_SECURITY -> R.string.page_error_security
                error.category == WebRequestError.ERROR_CATEGORY_SAFEBROWSING -> R.string.page_error_safebrowsing
                else -> R.string.page_error_other
            },
        )
        val title = context.getString(R.string.page_error_title)
        fun esc(s: String) = Html.escapeHtml(s)
        val html = """
            <!doctype html>
            <html><head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>${esc(title)}</title>
            <style>
              :root { color-scheme: light dark; --bg:#fafafa; --fg:#1d1d1d; --muted:#5f6368; }
              @media (prefers-color-scheme: dark) { :root { --bg:#121212; --fg:#e8e8e8; --muted:#9aa0a6; } }
              body { margin:0; min-height:100vh; background:var(--bg); color:var(--fg); font-family:sans-serif; }
              main { max-width:28rem; padding:4rem 1.5rem; }
              h1 { font-size:1.4rem; font-weight:normal; margin:0 0 1rem; }
              p { color:var(--muted); line-height:1.5; }
            </style>
            </head><body><main>
              <h1>${esc(title)}</h1>
              <p>${esc(message)}</p>
            </main></body></html>
        """.trimIndent()
        return "data:text/html;charset=utf-8;base64," + Base64.encodeToString(html.toByteArray(), Base64.NO_WRAP)
    }
}
