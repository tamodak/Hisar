package org.tamodak.hisar.data.repository

/**
 * The browser engine's cookies, for the few requests the app makes itself on the page's behalf:
 * downloads (Android's DownloadManager has its own network stack) and the Google Lens upload,
 * which belongs to the user's Google session.
 */
interface CookieStore {
    /** The Cookie header the engine would send to [url], or null. */
    suspend fun cookieHeader(url: String): String?

    /** Stores Set-Cookie headers received from [url], as the engine would have. */
    suspend fun store(url: String, setCookieHeaders: List<String>)
}
