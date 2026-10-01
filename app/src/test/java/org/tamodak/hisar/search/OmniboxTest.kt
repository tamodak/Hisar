package org.tamodak.hisar.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OmniboxTest {
    private val google = SearchEngine.GOOGLE

    @Test fun urls() {
        assertEquals("https://example.com", Omnibox.toUrl("example.com", google))
        assertEquals("http://example.com/a", Omnibox.toUrl("  http://example.com/a ", google))
        assertEquals("https://wikipedia.org/wiki/Ankara", Omnibox.toUrl("wikipedia.org/wiki/Ankara", google))
    }

    @Test fun searchesUseTheChosenEngineWithSafeSearch() {
        assertEquals("https://www.google.com/search?q=hello+world&safe=active", Omnibox.toUrl("hello world", google))
        assertEquals("https://www.bing.com/search?q=kittens&adlt=strict", Omnibox.toUrl("kittens", SearchEngine.BING))
        assertEquals("https://duckduckgo.com/?q=kittens&kp=1", Omnibox.toUrl("kittens", SearchEngine.DUCKDUCKGO))
    }

    @Test fun empty() = assertNull(Omnibox.toUrl("   ", google))

    @Test fun displayShowsSearchTermsOrABareAddress() {
        assertEquals("kittens playing", Omnibox.display("https://www.google.com/search?q=kittens+playing&safe=active"))
        assertEquals("example.com", Omnibox.display("https://www.example.com/"))
        assertEquals("en.wikipedia.org/wiki/Ankara", Omnibox.display("https://en.wikipedia.org/wiki/Ankara"))
    }
}
