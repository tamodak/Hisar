package org.tamodak.hisar.filter

/** Why something was blocked. */
enum class BlockReason {
    HARMFUL_SITE,
    AD_OR_TRACKER,
    BLOCKED_WORDS,
    UNSAFE_SEARCH_ENGINE,
}

sealed interface Decision {
    data object Allow : Decision
    data class Redirect(val url: String) : Decision
    data class Block(val reason: BlockReason, val detail: String) : Decision
}

/**
 * Every filtering decision Hisar makes, independent of the browser engine so it can be tested on
 * the JVM. The engine's built-in extension applies the same rules to what pages load
 * (assets/extensions/hisar/guard.js), checked against the same cases.
 *
 * Nothing here can be turned off: there is no setting, flag or allowlist. That is the product.
 */
class Guard(
    private val ads: DomainList,
    private val harmful: DomainList,
    private val words: WordFilter,
    /**
     * Words with an innocent everyday meaning too ("olgun", "meme"): they stop searches and typed
     * requests like [words] do, but don't hide anything a page shows.
     */
    private val searchOnlyWords: WordFilter = WordFilter(emptyList()),
) {
    /**
     * The lists for the browser extension (web/GuardLists): everything blocks what is typed and
     * submitted; only [words] hides what pages show, as search-only words have innocent uses.
     */
    val searchPhrases: List<List<String>> get() = words.phraseList + searchOnlyWords.phraseList
    val searchSymbols: List<String> get() = words.symbolList + searchOnlyWords.symbolList
    val pagePhrases: List<List<String>> get() = words.phraseList
    val pageSymbols: List<String> get() = words.symbolList
    val searchEmbedded: List<String> get() = words.substringList + searchOnlyWords.substringList
    val searchStems: List<String> get() = words.stemList + searchOnlyWords.stemList
    val pageEmbedded: List<String> get() = words.substringList
    val pageStems: List<String> get() = words.stemList

    val adDomainCount: Int get() = ads.size
    val harmfulDomainCount: Int get() = harmful.size
    val blockedWordCount: Int get() = words.size + searchOnlyWords.size

    /** A top-level navigation: what the address bar is about to show. */
    fun checkNavigation(url: String): Decision {
        val parsed = Url.parse(url)
        if (parsed.scheme != "http" && parsed.scheme != "https") return Decision.Allow

        domainBlock(parsed.host)?.let { return it }
        // Google Translate's own address for a page ("translate.google.com/translate?u=…") must
        // not get a blocked page through.
        translateTarget(parsed)?.let { target ->
            (checkNavigation(target) as? Decision.Block)?.let { return it }
        }
        if (SafeSearch.isUnenforceable(url)) return Decision.Block(BlockReason.UNSAFE_SEARCH_ENGINE, parsed.host)

        // Search queries and paths: "/search?q=…", "/wiki/Some_Title", "/watch/some-slug".
        val text = (listOf(Url.decode(parsed.path)) + parsed.params.map { it.second }).joinToString(" ")
        searchMatch(text)?.let { return Decision.Block(BlockReason.BLOCKED_WORDS, it) }

        SafeSearch.enforce(url)?.let { return Decision.Redirect(it) }
        return Decision.Allow
    }

    /**
     * Everything a page loads: images, scripts, frames, XHR. Words are checked only in search
     * parameters (`q=`, `query=`, …): that is how typed text travels — Google's suggestions as you
     * type, instant results — while the rest of an asset URL is opaque tokens that would trip the
     * list at random.
     */
    fun checkSubresource(url: String): Decision {
        val parsed = Url.parse(url)
        if (parsed.scheme != "http" && parsed.scheme != "https") return Decision.Allow

        domainBlock(parsed.host)?.let { return it }
        // Searches in plain parameters (`q=…`), and inside JSON-valued ones such as GraphQL's
        // `variables={"rawQuery":"…"}` (X, Instagram).
        val typed = parsed.params.joinToString(" ") { (name, value) ->
            when {
                name.lowercase() in SEARCH_PARAMS -> value
                value.trimStart().startsWith("{") -> JSON_SEARCH_VALUE.findAll(value).joinToString(" ") { it.groupValues[1] }
                else -> ""
            }
        }
        searchMatch(typed)?.let { return Decision.Block(BlockReason.BLOCKED_WORDS, it) }
        // A search fetched in the background (instant results, "more results") cannot be
        // redirected, so one that would not carry strict SafeSearch is dropped instead.
        if (SafeSearch.enforce(url) != null) return Decision.Block(BlockReason.UNSAFE_SEARCH_ENGINE, parsed.host)
        return Decision.Allow
    }

    /** True if [text] contains no blocked words. For text Hisar itself shows, like suggestions. */
    fun isClean(text: String): Boolean = searchMatch(text) == null

    /** The first blocked word in text that would be sent somewhere: either list counts. */
    private fun searchMatch(text: String): String? =
        words.findMatches(text).firstOrNull() ?: searchOnlyWords.findMatches(text).firstOrNull()

    /** True if [url] would load rather than be blocked. For links Hisar itself offers, like tiles. */
    fun allows(url: String): Boolean = checkNavigation(url) !is Decision.Block

    /**
     * The domain lists, applied to [host] and, for a Google Translate proxy host, to the site it
     * stands for: "blocked-site-com.translate.goog" serves blocked-site.com.
     */
    private fun domainBlock(host: String): Decision.Block? {
        for (candidate in listOfNotNull(host, translatedHost(host))) {
            harmful.match(candidate)?.let { return Decision.Block(BlockReason.HARMFUL_SITE, it) }
            ads.match(candidate)?.let { return Decision.Block(BlockReason.AD_OR_TRACKER, it) }
        }
        return null
    }

    private fun translateTarget(parsed: Url): String? {
        val labels = parsed.host.split('.')
        val isTranslate = labels.firstOrNull() == "translate" && labels.getOrNull(1) == "google"
        return if (isTranslate) parsed.param("u")?.takeIf { it.startsWith("http") } else null
    }

    companion object {
        /**
         * The original host behind a Google Translate proxy host, or null. Translate encodes
         * "a-b.example.com" as "a--b-example-com.translate.goog": dots become "-", dashes "--".
         */
        fun translatedHost(host: String): String? {
            if (!host.endsWith(".translate.goog")) return null
            val label = host.removeSuffix(".translate.goog")
            return label.replace("--", "\u0000").replace('-', '.').replace('\u0000', '-')
        }

        /** Query parameters that carry what a user typed, on the sites people search. */
        private val SEARCH_PARAMS = setOf("q", "oq", "pq", "query", "search_query", "p", "text", "keyword", "keywords", "k", "term", "search", "wd")

        /** A search-like key's string value inside JSON. */
        private val JSON_SEARCH_VALUE = Regex(
            "\"(?:q|query|rawQuery|searchQuery|search_query|search|term|searchTerm|keyword|keywords|text)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"",
            RegexOption.IGNORE_CASE,
        )
    }
}
