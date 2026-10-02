package org.tamodak.hisar.web

import android.text.Html
import org.tamodak.hisar.data.model.SimpleFont

/**
 * What Hisar puts into pages: simple mode's stylesheet (applied by the extension's content script)
 * and the simplified view's page (its article comes from the content script, content.js).
 */
object PageStyles {
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
