package org.tamodak.hisar.filter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeSearchTest {
    @Test fun googleSearchGetsSafeActive() =
        assertEquals("https://www.google.com/search?q=cats&safe=active", SafeSearch.enforce("https://www.google.com/search?q=cats"))

    @Test fun googleSafeOffIsOverridden() =
        assertEquals("https://www.google.com.tr/search?q=x&safe=active", SafeSearch.enforce("https://www.google.com.tr/search?safe=off&q=x"))

    @Test fun lensResultsGetSafeSearch() =
        assertEquals(
            "https://www.google.com/search?vsrid=abc&udm=26&safe=active",
            SafeSearch.enforce("https://www.google.com/search?vsrid=abc&udm=26"),
        )

    @Test fun alreadyStrictIsLeftAlone() = assertNull(SafeSearch.enforce("https://www.google.com/search?q=x&safe=active"))

    @Test fun fragmentIsKept() =
        assertEquals("https://www.google.com/search?q=x&safe=active#top", SafeSearch.enforce("https://www.google.com/search?q=x#top"))

    @Test fun bingDuckDuckGoYahooBrave() {
        assertEquals("https://www.bing.com/images/search?q=x&adlt=strict", SafeSearch.enforce("https://www.bing.com/images/search?q=x"))
        assertEquals("https://duckduckgo.com/?q=x&kp=1", SafeSearch.enforce("https://duckduckgo.com/?q=x&kp=-2"))
        assertEquals("https://search.yahoo.com/search?p=x&vm=r", SafeSearch.enforce("https://search.yahoo.com/search?p=x"))
        assertEquals("https://search.brave.com/search?q=x&safesearch=strict", SafeSearch.enforce("https://search.brave.com/search?q=x"))
    }

    @Test fun nonSearchPagesAreUntouched() {
        assertNull(SafeSearch.enforce("https://www.google.com/"))
        assertNull(SafeSearch.enforce("https://maps.google.com/maps?q=x"))
        assertNull(SafeSearch.enforce("https://notgoogle.example.com/search?q=x"))
        assertNull(SafeSearch.enforce("https://duckduckgo.com/about"))
        assertNull(SafeSearch.enforce("https://www.google.com/images/branding/product/1x/gsa_android_144dp.png"))
    }

    @Test fun unenforceableEngines() {
        assertTrue(SafeSearch.isUnenforceable("https://yandex.com.tr/search/?text=x"))
        assertFalse(SafeSearch.isUnenforceable("https://www.google.com/search?q=x"))
    }
}
