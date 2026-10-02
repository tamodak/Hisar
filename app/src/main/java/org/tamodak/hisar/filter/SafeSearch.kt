package org.tamodak.hisar.filter

/**
 * Enforces each search engine's own strict SafeSearch by rewriting its search URLs.
 *
 * The parameters are the engines' documented ones. They are set on every search request, replacing
 * whatever the page asked for, so a link carrying `safe=off` is corrected rather than honoured.
 * Engines with no URL-level switch Hisar can force are reported by [isUnenforceable] and blocked.
 */
object SafeSearch {
    private data class Rule(val param: String, val value: String, val appliesTo: (Url) -> Boolean)

    private val rules = listOf(
        // google.com, google.com.tr, images.google.de, …
        // Exact paths only: "/images/branding/…" and friends are Google's static assets, not searches.
        Rule("safe", "active") { it.isEngine("google") && (it.path == "/search" || (it.path == "/images" && it.param("q") != null)) },
        Rule("adlt", "strict") { it.isEngine("bing") && it.path.endsWith("/search") },
        Rule("kp", "1") { it.isEngine("duckduckgo") && it.param("q") != null },
        Rule("vm", "r") { it.host.endsWith("search.yahoo.com") && it.path.startsWith("/search") },
        Rule("safesearch", "strict") { it.host == "search.brave.com" && it.path in setOf("/search", "/images", "/videos") },
    )

    /** The URL with strict SafeSearch applied, or null if [url] is not a search or is already strict. */
    fun enforce(url: String): String? {
        val parsed = Url.parse(url)
        val rule = rules.firstOrNull { it.appliesTo(parsed) } ?: return null
        val current = parsed.params.filter { it.first == rule.param }.map { it.second }
        if (current == listOf(rule.value)) return null
        return parsed.withParam(rule.param, rule.value)
    }

    /** Search engines with no SafeSearch switch Hisar can force from the URL. */
    fun isUnenforceable(url: String): Boolean {
        val parsed = Url.parse(url)
        return (parsed.isEngine("yandex") && (parsed.path.startsWith("/search") || parsed.path.startsWith("/images"))) ||
            (parsed.isEngine("ecosia") && parsed.path == "/search") ||
            (parsed.isEngine("startpage") && parsed.path.startsWith("/sp/search"))
    }

    /**
     * The search terms if [url] is a results page of a search engine or YouTube, else null. Lets a
     * search blocked for its results stay blocked whatever else Google adds to its address.
     */
    fun searchQuery(url: String): String? {
        val parsed = Url.parse(url)
        val query = when {
            parsed.isEngine("google") && (parsed.path == "/search" || parsed.path == "/images") -> parsed.param("q")
            parsed.isEngine("bing") && parsed.path.endsWith("/search") -> parsed.param("q")
            parsed.isEngine("duckduckgo") -> parsed.param("q")
            parsed.host.endsWith("search.yahoo.com") -> parsed.param("p")
            parsed.host == "search.brave.com" -> parsed.param("q")
            parsed.isEngine("youtube") && parsed.path == "/results" -> parsed.param("search_query")
            else -> null
        }
        return query?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
    }

    /** True when the host is `<engine>.<tld>` or a subdomain of one, e.g. `www.google.com.tr`. */
    private fun Url.isEngine(name: String): Boolean {
        val labels = host.split('.')
        val i = labels.indexOf(name)
        // At most two labels of TLD after the name ("com", "com.tr", "co.uk").
        return i >= 0 && labels.size - i - 1 in 1..2
    }
}
