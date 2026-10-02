package org.tamodak.hisar.web

import org.json.JSONArray
import org.json.JSONObject
import org.tamodak.hisar.filter.Guard
import org.tamodak.hisar.filter.WordFilter

/**
 * The word lists as the browser extension takes them (assets/extensions/hisar/matcher.js),
 * already tokenised by [WordFilter] so both sides match the same way:
 * - `typed`: what is typed, searched or sent is checked against both lists;
 * - `shown`: what pages show is checked against the main list only, since search-only words
 *   ("olgun", "meme") have innocent everyday uses.
 */
object GuardLists {
    fun json(guard: Guard): JSONObject = JSONObject()
        .put("typed", list(guard.searchPhrases, guard.searchSymbols, guard.searchEmbedded, guard.searchStems))
        .put("shown", list(guard.pagePhrases, guard.pageSymbols, guard.pageEmbedded, guard.pageStems))
        .put("suffixes", JSONArray(WordFilter.TURKISH_SUFFIXES))

    private fun list(phrases: List<List<String>>, symbols: List<String>, embedded: List<String>, stems: List<String>) = JSONObject()
        .put("phrases", JSONArray(phrases.map(::JSONArray)))
        .put("symbols", JSONArray(symbols))
        .put("embedded", JSONArray(embedded))
        .put("stems", JSONArray(stems))
}
