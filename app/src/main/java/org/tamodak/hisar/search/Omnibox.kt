package org.tamodak.hisar.search

/** Address-bar logic: what typed text means, and how a URL is shown. */
object Omnibox {

    /** The URL for [input], or null if there is nothing to load. Non-URLs become a search on [engine]. */
    fun toUrl(input: String, engine: SearchEngine): String? {
        val text = input.trim()
        if (text.isEmpty()) return null
        if (looksLikeUrl(text)) {
            return if (text.contains("://")) text else "https://$text"
        }
        return engine.searchUrl(text)
    }

    fun looksLikeUrl(text: String): Boolean {
        val t = text.trim()
        if (t.startsWith("http://", ignoreCase = true) || t.startsWith("https://", ignoreCase = true)) return true
        val host = t.substringBefore('/')
        return ' ' !in t && '.' in host && !host.endsWith('.') && !host.startsWith('.')
    }

    /**
     * The address bar's resting text, as Chrome shows it: the search terms on a results page,
     * otherwise the URL without scheme, `www.` and a bare trailing slash.
     */
    fun display(url: String): String {
        if (url.isEmpty()) return ""
        SearchEngine.queryOf(url)?.let { return it }
        return url
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("www.")
            .let { if (it.count { c -> c == '/' } == 1 && it.endsWith('/')) it.dropLast(1) else it }
    }
}
