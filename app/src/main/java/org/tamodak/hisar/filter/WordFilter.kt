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
 * plain substrings instead, and so are entries in scripts written without spaces (Chinese,
 * Japanese, Korean), whose words run together in text.
 *
 * Text is read twice when it looks disguised ([Readings]): as written, and with leetspeak
 * ("p0rn"), spaced letters ("p o r n"), stretched letters ("sexxxy"), look-alike letters from
 * other alphabets, small capitals and invisible characters undone.
 *
 * Two looser rules apply to the words a dictionary check (tools/derive_word_rules.py) found safe:
 * - [substringWords]: matched inside other words, for names sites glue together
 *   ("r/actuallesbians");
 * - [suffixStems]: matched with a Turkish ending attached ("femboyun", "pornolar").
 * Each applies only to this filter's own words.
 */
class WordFilter(
    entries: Collection<String>,
    substringWords: Collection<String> = emptyList(),
    suffixStems: Collection<String> = emptyList(),
    /**
     * Phrases in which a blocked word means something else ("gay lussac", the gas law): their
     * words are skipped.
     */
    allowedPhrases: Collection<String> = emptyList(),
) {
    /** Phrases keyed by their first word; a single word is a one-word phrase. */
    private val phrases: Map<String, List<List<String>>>
    private val symbols: List<String>
    /** Entries in scripts without spaces, matched as substrings of the normalised text. */
    private val unspaced: List<String>

    /** Number of entries, for display. */
    val size: Int = entries.size

    init {
        val byFirst = HashMap<String, MutableList<List<String>>>()
        val symbolEntries = ArrayList<String>()
        val unspacedEntries = ArrayList<String>()
        for (entry in entries) {
            val words = tokenize(entry)
            if (Readings.isUnspaced(entry)) {
                normalize(entry).trim().takeIf { it.codePointCount(0, it.length) >= 2 }?.let(unspacedEntries::add)
            } else if (words.isNotEmpty() && words.all { w -> w.all(Char::isDigit) }) {
                // Numbers alone ("18" of "18+") would match every date and count.
                continue
            } else if (words.isEmpty()) {
                symbolForm(entry.trim()).takeIf { it.isNotEmpty() }?.let(symbolEntries::add)
            } else {
                byFirst.getOrPut(words.first()) { ArrayList() }.add(words)
            }
        }
        phrases = byFirst
        symbols = symbolEntries
        unspaced = unspacedEntries
    }


    private val allowed: Map<String, List<List<String>>> =
        allowedPhrases.map(::tokenize).filter { it.isNotEmpty() }.groupBy { it.first() }

    /** The allowed phrases, for the in-page script. */
    val allowedList: List<List<String>> get() = allowed.values.flatten()

    private val singleWords: Set<String> = phrases.values.flatten().filter { it.size == 1 }.mapTo(HashSet()) { it[0] }
    private val stems: Set<String> = suffixStems.map(::normalize).filterTo(HashSet()) { it in singleWords }
    private val embedded: List<String> = substringWords.map(::normalize).filter { it in singleWords }
    private val embeddedPattern: Regex? = embedded.takeIf { it.isNotEmpty() }
        ?.sortedByDescending { it.length }
        ?.joinToString("|") { Regex.escape(it) }
        ?.let(::Regex)

    /** The looser rules' words, for the in-page script. */
    val substringList: List<String> get() = embedded
    val stemList: List<String> get() = stems.toList()

    /**
     * The list in normalised form, for the in-page script that checks what pages send: each entry
     * as its words, and the entries matched as plain substrings.
     */
    val phraseList: List<List<String>> get() = phrases.values.flatten()
    val symbolList: List<String> get() = symbols + unspaced

    /** Every blocked entry found in [text], once per occurrence, joined back with spaces. */
    fun findMatches(text: String): List<String> {
        val found = ArrayList<String>()
        for (words in Readings.of(text)) matchWords(words, found)
        if (symbols.isNotEmpty()) {
            val symbolText = symbolForm(text)
            for (symbol in symbols) if (symbolText.contains(symbol)) found += symbol
        }
        if (unspaced.isNotEmpty()) {
            val normalized = normalize(text)
            for (entry in unspaced) if (normalized.contains(entry)) found += entry
        }
        return found
    }

    private fun matchWords(words: List<String>, found: MutableList<String>) {
        var skipUntil = 0
        for (i in words.indices) {
            val word = words[i]
            allowed[word]?.firstOrNull { p -> i + p.size <= words.size && p.indices.all { words[i + it] == p[it] } }
                ?.let { skipUntil = maxOf(skipUntil, i + it.size) }
            if (i < skipUntil) continue
            val before = found.size
            phrases[word]?.forEach { phrase ->
                if (i + phrase.size <= words.size && phrase.indices.all { words[i + it] == phrase[it] }) {
                    found += phrase.joinToString(" ")
                }
            }
            if (found.size == before) looseMatch(word)?.let(found::add)
        }
    }

    /** A blocked word with a Turkish ending, or embedded in a longer name. */
    private fun looseMatch(word: String): String? {
        for (suffix in TURKISH_SUFFIXES) {
            if (word.length > suffix.length && word.endsWith(suffix)) {
                val stem = word.dropLast(suffix.length)
                if (stem in stems) return stem
            }
        }
        if (word.length > MIN_EMBEDDED) embeddedPattern?.find(word)?.let { return it.value }
        return null
    }

    companion object {
        private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")
        private const val MIN_EMBEDDED = 6

        /** Turkish case, plural and possessive endings, longest first (as in derive_word_rules.py). */
        val TURKISH_SUFFIXES = listOf(
            "lerin", "ların", "leri", "ları", "lere", "lara", "lerde", "larda", "lerden", "lardan", "ler", "lar",
            "nın", "nin", "nun", "nün", "ın", "in", "un", "ün", "yla", "yle", "la", "le",
            "dan", "den", "tan", "ten", "da", "de", "ta", "te", "ya", "ye", "yı", "yi", "yu", "yü",
            "sı", "si", "su", "sü", "a", "e", "ı", "i", "u", "ü",
        ).sortedByDescending { it.length }

        /** Lines of a list file, without blanks and `#` comments. */
        fun readLines(input: InputStream): List<String> = input.bufferedReader().useLines { lines ->
            lines.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith('#') }.toList()
        }

        fun load(input: InputStream): WordFilter = WordFilter(
            input.bufferedReader().useLines { lines ->
                lines.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith('#') }.toList()
            }
        )

        private val SYMBOL_NOISE = Regex("[\\x{1F3FB}-\\x{1F3FF}\\uFE0F]")

        /** Emoji as compared: without skin tones and variation selectors (👉🏻👌 is 👉👌). */
        internal fun symbolForm(text: String): String = SYMBOL_NOISE.replace(text, "")

        internal fun normalize(text: String): String =
            // Lower-casing "İ" leaves "i" plus a combining dot; fold that back to a plain "i".
            Normalizer.normalize(text.lowercase(), Normalizer.Form.NFKC).replace("i\u0307", "i")

        internal fun tokenize(text: String): List<String> =
            normalize(text).split(NON_WORD).filter { it.isNotEmpty() }
    }
}
