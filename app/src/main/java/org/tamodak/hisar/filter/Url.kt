package org.tamodak.hisar.filter

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Minimal, tolerant URL parsing for the filters.
 *
 * java.net.URI rejects much of what browsers accept (spaces, `|`, unescaped unicode), and
 * android.net.Uri is unavailable to JVM unit tests, so the few parts the filters need are pulled
 * out by hand. Anything unparseable yields an empty host, which no list matches.
 */
internal class Url private constructor(
    val scheme: String,
    val host: String,
    val path: String,
    val query: String?,
    private val prefix: String,
    private val fragment: String?,
) {
    /** Query parameters in order, decoded. Repeated keys appear repeatedly. */
    val params: List<Pair<String, String>> by lazy {
        query.orEmpty().split('&').filter { it.isNotEmpty() }.map { part ->
            val eq = part.indexOf('=')
            if (eq < 0) decode(part) to "" else decode(part.substring(0, eq)) to decode(part.substring(eq + 1))
        }
    }

    fun param(name: String): String? = params.firstOrNull { it.first == name }?.second

    /** This URL with [name] set to [value], replacing every existing occurrence. */
    fun withParam(name: String, value: String): String {
        val kept = query.orEmpty().split('&').filter { part ->
            part.isNotEmpty() && decode(part.substringBefore('=')) != name
        }
        val newQuery = (kept + "${encode(name)}=${encode(value)}").joinToString("&")
        return prefix + path + "?" + newQuery + (fragment?.let { "#$it" } ?: "")
    }

    companion object {
        fun parse(raw: String): Url {
            val schemeEnd = raw.indexOf("://")
            if (schemeEnd <= 0) return Url("", "", "", null, raw, null)
            val scheme = raw.substring(0, schemeEnd).lowercase()

            val fragmentStart = raw.indexOf('#').let { if (it < 0) raw.length else it }
            val fragment = if (fragmentStart < raw.length) raw.substring(fragmentStart + 1) else null
            val body = raw.substring(0, fragmentStart)

            val authorityStart = schemeEnd + 3
            val authorityEnd = body.indexOfAny(charArrayOf('/', '?'), authorityStart)
                .let { if (it < 0) body.length else it }
            val authority = body.substring(authorityStart, authorityEnd)
            val hostPort = authority.substringAfterLast('@')
            val host = if (hostPort.startsWith("[")) {
                hostPort.substringBefore(']') + "]" // IPv6 literal; no list contains these.
            } else {
                hostPort.substringBefore(':')
            }.lowercase().trimEnd('.')

            val rest = body.substring(authorityEnd)
            val queryStart = rest.indexOf('?')
            val path = if (queryStart < 0) rest else rest.substring(0, queryStart)
            val query = if (queryStart < 0) null else rest.substring(queryStart + 1)

            return Url(scheme, host, path, query, body.substring(0, authorityEnd), fragment)
        }

        fun decode(s: String): String = try {
            URLDecoder.decode(s, "UTF-8")
        } catch (_: IllegalArgumentException) {
            s // Malformed escapes: match on the raw text rather than skipping it.
        }

        private fun encode(s: String): String = URLEncoder.encode(s, "UTF-8")
    }
}
