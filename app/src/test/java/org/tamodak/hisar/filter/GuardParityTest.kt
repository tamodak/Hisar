package org.tamodak.hisar.filter

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.tamodak.hisar.web.GuardLists
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The app's rules and the browser extension's must agree: the cases in guard-cases.json are
 * checked here against [Guard], and against the extension's scripts by running
 * src/test/js/extension-test.js in Node with the lists as [GuardLists] hands them over.
 */
class GuardParityTest {
    private val cases = JSONObject(javaClass.getResource("/guard-cases.json")!!.readText())
    private val lists = cases.getJSONObject("lists")
    private fun strings(name: String) = lists.getJSONArray(name).let { a -> (0 until a.length()).map(a::getString) }

    private val words = WordFilter(strings("words"), strings("substring"), strings("stems"))
    private val guard = Guard(
        ads = DomainList(strings("ads")),
        harmful = DomainList(strings("harmful")),
        words = words,
        searchOnlyWords = WordFilter(strings("searchOnly"), strings("substring"), strings("stems")),
    )

    private fun Decision.name(): String = when (this) {
        Decision.Allow -> "allow"
        is Decision.Redirect -> "redirect"
        is Decision.Block -> when (reason) {
            BlockReason.HARMFUL_SITE -> "harmful"
            BlockReason.AD_OR_TRACKER -> "ad"
            BlockReason.BLOCKED_WORDS -> "words"
            BlockReason.UNSAFE_SEARCH_ENGINE -> "safesearch"
        }
    }

    @Test fun subresourcesFollowTheCases() = cases.getJSONArray("subresource").forEach { case ->
        assertEquals(case.getString("url"), case.getString("expect"), guard.checkSubresource(case.getString("url")).name())
    }

    @Test fun wordsFollowTheCases() = cases.getJSONArray("text").forEach { case ->
        val text = case.getString("text")
        assertEquals("typed: $text", case.getBoolean("typed"), !guard.isClean(text))
        assertEquals("shown: $text", case.getBoolean("shown"), words.findMatches(text).isNotEmpty())
    }

    @Test fun theExtensionFollowsTheSameCases() {
        val node = listOf("node", "--version").let { runCatching { ProcessBuilder(it).start().waitFor() == 0 }.getOrDefault(false) }
        assumeTrue("Node.js is needed to run the extension's scripts", node)
        val listsFile = File.createTempFile("guard-lists", ".json").apply {
            writeText(JSONObject().put("lists", GuardLists.json(guard)).put("ads", lists.getJSONArray("ads")).put("harmful", lists.getJSONArray("harmful")).toString())
            deleteOnExit()
        }
        val casesFile = File(javaClass.getResource("/guard-cases.json")!!.toURI())
        val process = ProcessBuilder("node", "src/test/js/extension-test.js", listsFile.path, casesFile.path)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(output, true, process.waitFor(60, TimeUnit.SECONDS))
        assertEquals(output, 0, process.exitValue())
    }

    private fun JSONArray.forEach(action: (JSONObject) -> Unit) = (0 until length()).forEach { action(getJSONObject(it)) }
}
