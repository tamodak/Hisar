package org.tamodak.hisar.filter

import java.io.InputStream

/**
 * A set of blocked domains. A listed domain also blocks every subdomain of it, so `example.com`
 * covers `cdn.example.com` — hosts files list exact names, but a site that moves to a fresh
 * subdomain should not slip past.
 */
class DomainList(domains: Collection<String>) {
    private val domains: Set<String> = domains.mapTo(HashSet(domains.size * 2)) { it.lowercase() }

    val size: Int get() = domains.size

    /** The listed domain that [host] falls under, or null if it is not blocked. */
    fun match(host: String): String? {
        var candidate = host.lowercase().trimEnd('.')
        while (candidate.isNotEmpty()) {
            if (candidate in domains) return candidate
            val dot = candidate.indexOf('.')
            if (dot < 0) return null
            candidate = candidate.substring(dot + 1)
        }
        return null
    }

    companion object {
        /** One domain per line; blank lines and `#` comments are skipped. */
        fun load(input: InputStream): DomainList = DomainList(
            input.bufferedReader().useLines { lines ->
                lines.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith('#') }.toList()
            }
        )
    }
}
