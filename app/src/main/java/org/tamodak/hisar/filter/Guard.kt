package org.tamodak.hisar.filter

import java.util.Collections

/** Why something was blocked. */
enum class BlockReason {
    HARMFUL_SITE,
    AD_OR_TRACKER,
    BLOCKED_WORDS,
    PAGE_CONTENT,
    UNSAFE_SEARCH_ENGINE,
}

sealed interface Decision {
    data object Allow : Decision
    data class Redirect(val url: String) : Decision
    data class Block(val reason: BlockReason, val detail: String) : Decision
}

/**
 * Every filtering decision Hisar makes, independent of WebView so it can be tested on the JVM.
 *
 * Nothing here can be turned off: there is no setting, flag or allowlist. That is the product.
 */
class Guard(
    private val ads: DomainList,
    private val harmful: DomainList,
    private val words: WordFilter,
) {
    /**
     * Pages blocked for their content during this session. Recorded so that going back to one, or
     * reloading it, is refused before it renders rather than shown again until the next scan.
     */
    private val contentBlocked: MutableSet<String> = Collections.synchronizedSet(HashSet())

    val adDomainCount: Int get() = ads.size
    val harmfulDomainCount: Int get() = harmful.size
    val blockedWordCount: Int get() = words.size

    /** A top-level navigation: what the address bar is about to show. */
    fun checkNavigation(url: String): Decision {
        val parsed = Url.parse(url)
        // Checked for every scheme: a saved page found to be bad must stay blocked too.
        if (stripFragment(url) in contentBlocked) return Decision.Block(BlockReason.PAGE_CONTENT, parsed.host)
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
        words.findMatches(text).firstOrNull()?.let { return Decision.Block(BlockReason.BLOCKED_WORDS, it) }

        SafeSearch.enforce(url)?.let { return Decision.Redirect(it) }
        return Decision.Allow
    }

    /**
     * Everything a page loads: images, scripts, frames, XHR. Words are not checked here — asset
     * URLs are full of opaque tokens and would trip the list constantly.
     */
    fun checkSubresource(url: String): Decision {
        val parsed = Url.parse(url)
        if (parsed.scheme != "http" && parsed.scheme != "https") return Decision.Allow

        domainBlock(parsed.host)?.let { return it }
        // A search fetched in the background (instant results, "more results") cannot be
        // redirected, so one that would not carry strict SafeSearch is dropped instead.
        if (SafeSearch.enforce(url) != null) return Decision.Block(BlockReason.UNSAFE_SEARCH_ENGINE, parsed.host)
        return Decision.Allow
    }

    /**
     * The rendered text of a page. A single hit is not enough — news, medicine and literature
     * mention these words legitimately — so a page is blocked only once matches pile up.
     */
    fun checkPageText(url: String, text: String): Decision {
        val matches = words.findMatches(text)
        val distinct = matches.toSet()
        if (matches.size < PAGE_TOTAL_THRESHOLD && distinct.size < PAGE_DISTINCT_THRESHOLD) return Decision.Allow
        contentBlocked += stripFragment(url)
        return Decision.Block(BlockReason.PAGE_CONTENT, distinct.take(3).joinToString(", "))
    }

    /** True if [text] contains no blocked words. For text Hisar itself shows, like suggestions. */
    fun isClean(text: String): Boolean = words.findMatches(text).isEmpty()

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

    private fun stripFragment(url: String) = url.substringBefore('#')

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

        const val PAGE_TOTAL_THRESHOLD = 6
        const val PAGE_DISTINCT_THRESHOLD = 3
    }
}
