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

    @Test fun turkishEndingsAndJoinedNames() {
        val f = WordFilter(listOf("femboy", "lesbian", "gay"), substringWords = listOf("lesbian", "femboy"), suffixStems = listOf("femboy", "lesbian"))
        assertEquals(listOf("femboy"), f.findMatches("bir femboyun olması"))
        assertEquals(listOf("lesbian"), f.findMatches("r/actuallesbians"))
        assertEquals(listOf("lesbian"), f.findMatches("LesbianActually"))
        // Words without a safe rule stay whole-word: "gayet" (quite) is not "gay".
        assertTrue(f.findMatches("gayet iyi").isEmpty())
    }

    @Test fun matchesSymbolEntries() = assertEquals(listOf("🖕"), filter.findMatches("hey 🖕"))
}
