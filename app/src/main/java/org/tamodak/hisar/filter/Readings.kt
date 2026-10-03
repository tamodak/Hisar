package org.tamodak.hisar.filter

/**
 * The ways a text is read for blocked words: as written, and — when it looks disguised — with the
 * disguise undone. Mirrored exactly by readings() in assets/extensions/hisar/matcher.js; the
 * shared cases in guard-cases.json keep the two in step.
 *
 * Undone, in this order:
 * - invisible characters (zero-width spaces and joiners, soft hyphens) are dropped;
 * - small capitals ("ᴘᴏʀɴ") and regional-indicator letters become plain letters, and so do
 *   look-alike Cyrillic and Greek letters inside words that mix them with Latin ones ("pоrn");
 * - leetspeak in words with at least two letters and no more look-alike digits or signs than
 *   letters ("p0rn", "s3x", "$exy" — but not "a55", a phone model);
 * - three or more single letters in a row are joined ("p o r n", "s.e.x");
 * - letters repeated three or more times are read once ("sexxxy" -> "sexy") and, in a third
 *   reading, twice ("pornnn" -> "pornn", for words spelt with a double letter).
 */
internal object Readings {
    private val INVISIBLE = Regex("[\\u00AD\\u200B-\\u200F\\u2060-\\u2064\\uFEFF]")
    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")
    /** A word with its look-alike signs: letters, digits and @ $ ! | € +. */
    private val WORD_RUN = Regex("[\\p{L}\\p{N}@$!|€+]+")
    private val TRIPLE = Regex("(\\p{L})\\1{2,}")
    private val LATIN = Regex("[a-z]")
    private val UNSPACED = Regex("[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}\\p{IsHangul}]")

    private val LEET = mapOf('0' to 'o', '1' to 'i', '3' to 'e', '4' to 'a', '5' to 's', '7' to 't', '8' to 'b',
        '@' to 'a', '$' to 's', '!' to 'i', '|' to 'l', '€' to 'e', '+' to 't')

    private val LOOKALIKE = mapOf(
        'а' to 'a', 'в' to 'b', 'е' to 'e', 'ё' to 'e', 'к' to 'k', 'м' to 'm', 'н' to 'h', 'о' to 'o', 'р' to 'p',
        'с' to 'c', 'т' to 't', 'у' to 'y', 'х' to 'x', 'і' to 'i', 'ј' to 'j', 'ѕ' to 's', 'ԁ' to 'd', 'ԛ' to 'q', 'ԝ' to 'w',
        'α' to 'a', 'β' to 'b', 'ε' to 'e', 'ι' to 'i', 'κ' to 'k', 'ν' to 'v', 'ο' to 'o', 'ρ' to 'p', 'τ' to 't',
        'υ' to 'u', 'χ' to 'x', 'γ' to 'y',
    )

    /** Small capitals and their letters (there are none for x). */
    private val SMALL_CAPS = "ᴀa ʙb ᴄc ᴅd ᴇe ꜰf ɢg ʜh ɪi ᴊj ᴋk ʟl ᴍm ɴn ᴏo ᴘp ꞯq ʀr ꜱs ᴛt ᴜu ᴠv ᴡw ʏy ᴢz".split(' ').associate { it[0] to it[1] }

    /** True for text in a script written without spaces between words. */
    fun isUnspaced(text: String): Boolean = UNSPACED.containsMatchIn(text)

    /** The token lists to match: the text as written, then its undisguised readings if they differ. */
    fun of(text: String): List<List<String>> {
        val plain = WordFilter.tokenize(text)
        val undisguised = undisguise(WordFilter.normalize(text)) ?: return listOf(plain)
        val once = join(undisguised.split(NON_WORD).filter { it.isNotEmpty() })
        val readings = arrayListOf(plain)
        val first = once.map { it.replace(TRIPLE, "$1") }
        if (first != plain) readings += first
        val second = once.map { it.replace(TRIPLE, "$1$1") }
        if (second != plain && second != first) readings += second
        return readings
    }

    /** The text with disguises undone, or null when it shows none (the common case, kept cheap). */
    private fun undisguise(text: String): String? {
        var t = INVISIBLE.replace(text, "")
        var changed = t.length != text.length
        t = buildString(t.length) {
            for (ch in t) {
                val plainLetter = SMALL_CAPS[ch]
                if (plainLetter != null) { append(plainLetter); changed = true } else append(ch)
            }
        }
        val regional = regionalLetters(t)
        if (regional != t) { t = regional; changed = true }
        t = WORD_RUN.replace(t) { m ->
            val word = m.value
            var result = word
            if (LATIN.containsMatchIn(word) && word.any { it in LOOKALIKE }) {
                result = result.map { LOOKALIKE[it] ?: it }.joinToString("")
            }
            val letters = result.count { it.isLetter() }
            val signs = result.count { it in LEET }
            if (signs > 0 && letters >= 2 && signs <= letters) {
                result = result.map { LEET[it] ?: it }.joinToString("")
            }
            if (result != word) changed = true
            result
        }
        if (!changed && !TRIPLE.containsMatchIn(t) && !hasLetterRun(t)) return null
        return t
    }

    /** Regional indicator symbols (🇦..🇿) as the letters they stand for. */
    private fun regionalLetters(text: String): String {
        if (text.none { it.isSurrogate() }) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (cp in 0x1F1E6..0x1F1FF) out.append('a' + (cp - 0x1F1E6)) else out.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        return out.toString()
    }

    /** True when three single letters stand in a row, as in "p o r n". */
    private fun hasLetterRun(text: String): Boolean {
        var run = 0
        for (token in text.split(NON_WORD)) {
            if (token.isEmpty()) continue
            run = if (token.length == 1 && token[0].isLetter()) run + 1 else 0
            if (run >= 3) return true
        }
        return false
    }

    /** Runs of three or more single letters joined into one word. */
    private fun join(tokens: List<String>): List<String> {
        val out = ArrayList<String>(tokens.size)
        var i = 0
        while (i < tokens.size) {
            var j = i
            while (j < tokens.size && tokens[j].length == 1 && tokens[j][0].isLetter()) j++
            if (j - i >= 3) { out += tokens.subList(i, j).joinToString(""); i = j } else { out += tokens[i]; i++ }
        }
        return out
    }
}
