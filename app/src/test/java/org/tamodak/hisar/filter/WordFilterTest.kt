package org.tamodak.hisar.filter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WordFilterTest {
    private val filter = WordFilter(listOf("ass", "big tits", "şerefsiz", "göt", "🖕"))

    @Test fun matchesWholeWordsOnly() {
        assertEquals(listOf("ass"), filter.findMatches("what an ASS!"))
        assertTrue(filter.findMatches("classic assignment in class").isEmpty())
    }

    @Test fun matchesPhrasesAcrossPunctuation() =
        assertEquals(listOf("big tits"), filter.findMatches("big-tits"))

    @Test fun foldsTurkishCase() = assertEquals(listOf("şerefsiz"), filter.findMatches("ŞEREFSİZ"))

    @Test fun keepsAccentsSoTurkishWordsDoNotMatchEnglishOnes() {
        assertTrue(filter.findMatches("He got there early").isEmpty())
        assertEquals(listOf("göt"), filter.findMatches("göt"))
    }

    @Test fun matchesSymbolEntries() = assertEquals(listOf("🖕"), filter.findMatches("hey 🖕"))
}
