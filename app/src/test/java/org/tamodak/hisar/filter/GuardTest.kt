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

    @Test fun pageTextNeedsSeveralHitsAndIsRemembered() {
        assertEquals(Decision.Allow, guard.checkPageText("https://news.example/a", "An article that mentions sex education once."))
        val url = "https://example.org/page"
        assertTrue(guard.checkPageText(url, "porn sex nude") is Decision.Block)
        assertEquals(BlockReason.PAGE_CONTENT, reason(guard.checkNavigation("$url#frag")))
    }

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

    @Test fun nonHttpSchemesAreNotFiltered() = assertEquals(Decision.Allow, guard.checkNavigation("about:blank"))
}
