package org.tamodak.hisar.filter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardTest {
    private val guard = Guard(
        ads = DomainList(listOf("doubleclick.net")),
        harmful = DomainList(listOf("badsite.com")),
        words = WordFilter(listOf("porn", "sex", "nude")),
    )

    private fun reason(d: Decision) = (d as Decision.Block).reason

    @Test fun harmfulDomainAndSubdomainsAreBlocked() {
        assertEquals(BlockReason.HARMFUL_SITE, reason(guard.checkNavigation("https://badsite.com/")))
        assertEquals(BlockReason.HARMFUL_SITE, reason(guard.checkNavigation("https://cdn.badsite.com:8443/x")))
        assertEquals(Decision.Allow, guard.checkNavigation("https://notbadsite.com/"))
    }

    @Test fun adsAreBlockedAsSubresources() {
        assertEquals(BlockReason.AD_OR_TRACKER, reason(guard.checkSubresource("https://ad.doubleclick.net/pixel")))
        assertEquals(Decision.Allow, guard.checkSubresource("https://example.com/app.js"))
    }

    @Test fun blockedWordsInSearchQueryAreBlocked() =
        assertEquals(BlockReason.BLOCKED_WORDS, reason(guard.checkNavigation("https://www.google.com/search?q=free+porn&safe=active")))

    @Test fun cleanSearchIsRedirectedToSafeSearch() =
        assertEquals(
            Decision.Redirect("https://www.google.com/search?q=kittens&safe=active"),
            guard.checkNavigation("https://www.google.com/search?q=kittens"),
        )

    @Test fun backgroundSearchWithoutSafeSearchIsDropped() =
        assertEquals(BlockReason.UNSAFE_SEARCH_ENGINE, reason(guard.checkSubresource("https://www.google.com/search?q=x&async=1")))

    @Test fun translateProxyCannotReachBlockedSites() {
        assertEquals("bad-site.example.com", Guard.translatedHost("bad--site-example-com.translate.goog"))
        assertEquals(BlockReason.HARMFUL_SITE, reason(guard.checkNavigation("https://www-badsite-com.translate.goog/?_x_tr_sl=auto")))
        assertEquals(BlockReason.HARMFUL_SITE, reason(guard.checkSubresource("https://cdn-badsite-com.translate.goog/a.jpg")))
        assertEquals(
            BlockReason.HARMFUL_SITE,
            reason(guard.checkNavigation("https://translate.google.com/translate?sl=auto&tl=tr&u=https%3A%2F%2Fbadsite.com%2F")),
        )
        assertEquals(Decision.Allow, guard.checkNavigation("https://en-wikipedia-org.translate.goog/wiki/Ankara"))
    }

    @Test fun typedWordsInBackgroundRequestsNeverLeave() {
        // Google's suggestions as you type, and instant results.
        assertEquals(BlockReason.BLOCKED_WORDS, reason(guard.checkSubresource("https://www.google.com/complete/s?q=nude&cp=4&client=mobile-gws-wiz-hp")))
        assertEquals(BlockReason.BLOCKED_WORDS, reason(guard.checkSubresource("https://www.google.com/complete/s?q=n&pq=nude")))
        assertEquals(BlockReason.BLOCKED_WORDS, reason(guard.checkSubresource("https://suggestqueries.google.com/complete/search?client=firefox&q=free+porn")))
        assertEquals(Decision.Allow, guard.checkSubresource("https://www.google.com/complete/s?q=kittens"))
        // Words elsewhere in an asset URL are left alone.
        assertEquals(Decision.Allow, guard.checkSubresource("https://cdn.example.com/sex-ed/book.png?id=nude123"))
    }

    @Test fun searchesInsideJsonParametersAreChecked() {
        val graphQl = "https://x.com/i/api/graphql/abc/SearchTimeline?variables=" +
            java.net.URLEncoder.encode("""{"rawQuery":"free porn","count":20}""", "UTF-8")
        assertEquals(BlockReason.BLOCKED_WORDS, reason(guard.checkSubresource(graphQl)))
        val clean = "https://x.com/i/api/graphql/abc/SearchTimeline?variables=" +
            java.net.URLEncoder.encode("""{"rawQuery":"kittens","product":"Top"}""", "UTF-8")
        assertEquals(Decision.Allow, guard.checkSubresource(clean))
    }

    @Test fun searchOnlyWordsStopSearchesButDontHidePageText() {
        val guard = Guard(DomainList(emptyList()), DomainList(emptyList()), WordFilter(listOf("porn")), WordFilter(listOf("olgun")))
        assertEquals(BlockReason.BLOCKED_WORDS, reason(guard.checkNavigation("https://www.google.com/search?q=olgun+kadin")))
        assertEquals(BlockReason.BLOCKED_WORDS, reason(guard.checkSubresource("https://www.google.com/complete/s?q=olgun")))
        assertTrue(!guard.isClean("olgun"))
        // A recipe that says "olgun" is still a recipe: the words pages are checked for leave it out.
        assertTrue(guard.pagePhrases.none { "olgun" in it })
        assertTrue(guard.searchPhrases.any { "olgun" in it })
    }

    @Test fun nonHttpSchemesAreNotFiltered() = assertEquals(Decision.Allow, guard.checkNavigation("about:blank"))
}
