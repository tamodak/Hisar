package org.tamodak.hisar.web

import org.junit.Assert.assertTrue
import org.junit.Test
import org.tamodak.hisar.filter.DomainList
import org.tamodak.hisar.filter.Guard
import org.tamodak.hisar.filter.WordFilter
import java.io.File

class PageGuardScriptTest {
    @Test fun buildsTheScriptWithTheLists() {
        val words = WordFilter(listOf("lesbian", "doggy style"), substringWords = listOf("lesbian"), suffixStems = listOf("lesbian"))
        val script = PageGuardScript.build(Guard(DomainList(emptyList()), DomainList(emptyList()), words))
        assertTrue(script.contains("\"lesbian\""))
        // Written out for a JavaScript syntax check (node --check build/tmp/page-guard.js).
        File("build/tmp").mkdirs()
        File("build/tmp/page-guard.js").writeText(script)
    }
}
