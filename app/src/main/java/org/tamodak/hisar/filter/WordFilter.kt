package org.tamodak.hisar.filter

import java.io.InputStream
import java.text.Normalizer

/**
 * Finds blocked words and phrases in text.
 *
 * Matching is by whole word, never substring, so "ass" does not fire on "class" or "assignment"
 * (the Scunthorpe problem). Text and list entries go through the same [normalize] step — case
 * folding — so "ŞEREFSİZ" matches "şerefsiz". Accents are kept: stripping them would turn the
 * Turkish "göt" into the English "got" and flag every page that uses it.
 *
 * Entries that contain no letters or digits at all (emoji) cannot be tokenised and are matched as
 * plain substrings instead.
 */
class WordFilter(entries: Collection<String>) {
    /** Phrases keyed by their first word; a single word is a one-word phrase. */
    private val phrases: Map<String, List<List<String>>>
    private val symbols: List<String>

    /** Number of entries, for display. */
    val size: Int = entries.size

    init {
        val byFirst = HashMap<String, MutableList<List<String>>>()
        val symbolEntries = ArrayList<String>()
        for (entry in entries) {
            val words = tokenize(entry)
            if (words.isEmpty()) {
                entry.trim().takeIf { it.isNotEmpty() }?.let(symbolEntries::add)
            } else {
                byFirst.getOrPut(words.first()) { ArrayList() }.add(words)
            }
        }
        phrases = byFirst
        symbols = symbolEntries
    }

    /** Every blocked entry found in [text], once per occurrence, joined back with spaces. */
    fun findMatches(text: String): List<String> {
        val found = ArrayList<String>()
        val words = tokenize(text)
        for (i in words.indices) {
            val candidates = phrases[words[i]] ?: continue
            for (phrase in candidates) {
                if (i + phrase.size <= words.size && phrase.indices.all { words[i + it] == phrase[it] }) {
                    found += phrase.joinToString(" ")
                }
            }
        }
        for (symbol in symbols) {
            if (text.contains(symbol)) found += symbol
        }
        return found
    }

    companion object {
        private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")

        fun load(input: InputStream): WordFilter = WordFilter(
            input.bufferedReader().useLines { lines ->
                lines.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith('#') }.toList()
            }
        )

        internal fun normalize(text: String): String =
            // Lower-casing "İ" leaves "i" plus a combining dot; fold that back to a plain "i".
            Normalizer.normalize(text.lowercase(), Normalizer.Form.NFKC).replace("i\u0307", "i")

        internal fun tokenize(text: String): List<String> =
            normalize(text).split(NON_WORD).filter { it.isNotEmpty() }
    }
}
