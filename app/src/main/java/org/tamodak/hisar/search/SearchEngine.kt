package org.tamodak.hisar.search

import org.tamodak.hisar.filter.SafeSearch
import org.tamodak.hisar.filter.Url
import java.net.URLEncoder

/**
 * The search engines the user can choose between. Only engines whose SafeSearch Hisar can enforce
 * are offered; the filter enforces it regardless, but building the URL strict to begin with saves a
 * redirect.
 */
enum class SearchEngine(
    val id: String,
    val label: String,
    private val engineName: String,
    private val searchBase: String,
    private val suggestBase: String,
) {
    GOOGLE(
        "google", "Google", "google",
        "https://www.google.com/search?q=",
        "https://suggestqueries.google.com/complete/search?client=firefox&ie=UTF-8&oe=UTF-8&q=",
    ),
    BING(
        "bing", "Bing", "bing",
        "https://www.bing.com/search?q=",
        "https://api.bing.com/osjson.aspx?query=",
    ),
    DUCKDUCKGO(
        "duckduckgo", "DuckDuckGo", "duckduckgo",
        "https://duckduckgo.com/?q=",
        "https://duckduckgo.com/ac/?type=list&q=",
    ),
    ;

    fun searchUrl(query: String): String {
        val url = searchBase + encode(query)
        return SafeSearch.enforce(url) ?: url
    }

    /** Returns suggestions as an OpenSearch JSON array: `["query", ["s1", "s2", …]]`. */
    fun suggestUrl(query: String): String = suggestBase + encode(query)

    companion object {
        fun fromId(id: String?): SearchEngine = entries.firstOrNull { it.id == id } ?: GOOGLE

        /** The search terms if [url] is a results page of any of these engines, so the address bar can show them. */
        fun queryOf(url: String): String? {
            val parsed = Url.parse(url)
            val labels = parsed.host.split('.')
            val engine = entries.firstOrNull { e ->
                val i = labels.indexOf(e.engineName)
                i >= 0 && labels.size - i - 1 in 1..2
            } ?: return null
            val isResults = when (engine) {
                GOOGLE -> parsed.path == "/search"
                BING -> parsed.path == "/search"
                DUCKDUCKGO -> parsed.path == "/"
            }
            return if (isResults) parsed.param("q")?.takeIf { it.isNotBlank() } else null
        }

        private fun encode(s: String) = URLEncoder.encode(s, "UTF-8")
    }
}
