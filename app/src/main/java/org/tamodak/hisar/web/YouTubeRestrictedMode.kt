package org.tamodak.hisar.web

import android.net.Uri
import android.webkit.CookieManager

/**
 * Keeps YouTube in Restricted Mode.
 *
 * YouTube has no URL parameter for it; the setting lives in the `f2=8000000` flag of its `PREF`
 * cookie. YouTube rewrites that cookie as other preferences change, so the flag is merged back in
 * before every top-level YouTube load rather than set once.
 */
object YouTubeRestrictedMode {
    private const val FLAG = "f2"
    private const val RESTRICTED = "8000000"

    fun apply(uri: Uri) {
        val host = uri.host ?: return
        if (host != "youtube.com" && !host.endsWith(".youtube.com")) return

        val cookies = CookieManager.getInstance()
        val origin = "https://$host"
        val current = cookies.getCookie(origin)
            ?.split(';')
            ?.map { it.trim() }
            ?.firstOrNull { it.startsWith("PREF=") }
            ?.removePrefix("PREF=")
            .orEmpty()

        val pairs = current.split('&').filter { it.isNotEmpty() && !it.startsWith("$FLAG=") }
        val pref = (pairs + "$FLAG=$RESTRICTED").joinToString("&")
        if (pref == current) return
        cookies.setCookie(origin, "PREF=$pref; Domain=.youtube.com; Path=/; Secure; Max-Age=63072000")
        cookies.flush()
    }
}
