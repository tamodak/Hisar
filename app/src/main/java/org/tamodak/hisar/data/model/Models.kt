package org.tamodak.hisar.data.model

import org.tamodak.hisar.search.SearchEngine

data class HistoryEntry(val id: Long, val url: String, val title: String, val visitedAt: Long)

data class Bookmark(val url: String, val title: String, val createdAt: Long)

data class ClosedTab(val url: String, val title: String, val closedAt: Long)

data class OfflinePage(val id: Long, val url: String, val title: String, val fileName: String, val size: Long, val savedAt: Long)

data class DownloadItem(val id: Long, val fileName: String, val url: String, val mimeType: String?, val startedAt: Long)

data class DownloadStatus(val state: State, val downloaded: Long, val total: Long) {
    enum class State { PENDING, RUNNING, PAUSED, DONE, FAILED, MISSING }

    /** 0–1, or null while the size is unknown. */
    val fraction: Float? get() = if (total > 0) downloaded.toFloat() / total else null
}

/** A named, coloured set of tabs, as in Chrome's tab groups. */
data class TabGroup(val id: Long, val name: String, val colorIndex: Int) {
    companion object {
        /** Chrome's group colours: grey, blue, red, yellow, green, pink, purple, cyan. */
        val COLORS = listOf(0xFF5F6368, 0xFF1A73E8, 0xFFD93025, 0xFFF9AB00, 0xFF1E8E3E, 0xFFD01884, 0xFF9334E6, 0xFF007B83)
    }
}

/** A tab as saved between sessions: enough to put it back, not its live page. */
data class SavedTab(val id: Long, val url: String, val title: String, val groupId: Long?)

data class SavedSession(val tabs: List<SavedTab>, val groups: List<TabGroup>, val selectedId: Long?)

/** Fonts offered for simple mode, as CSS family names the browser engine resolves. */
enum class SimpleFont(val css: String) {
    SANS("sans-serif"),
    SERIF("serif"),
    MONO("monospace"),
    CONDENSED("sans-serif-condensed"),
}

/**
 * User preferences. Deliberately nothing here touches filtering: every protection is always on.
 */
data class UserSettings(
    val searchEngine: SearchEngine = SearchEngine.GOOGLE,
    /** Page text size in percent. */
    val textZoom: Int = 100,
    /** Whether typed text is sent to the search engine for suggestions. */
    val searchSuggestions: Boolean = true,
    /** Every page in one font, one size, white on black. */
    val simpleMode: Boolean = false,
    val simpleFont: SimpleFont = SimpleFont.SANS,
    /** Most-visited tiles the user removed from the new tab page. */
    val hiddenTiles: Set<String> = emptySet(),
)

/** A tile on the new tab page. [pinned] tiles were saved by the user and stay put. */
data class Shortcut(val title: String, val url: String, val pinned: Boolean = false)

/**
 * The new tab page's tiles before Hisar knows the user's own most-visited sites. They give way to
 * those as browsing history builds up, and the user can edit, pin or remove any of them.
 */
val DefaultShortcuts = listOf(
    Shortcut("YouTube", "https://www.youtube.com/"),
    Shortcut("Instagram", "https://www.instagram.com/"),
    Shortcut("Amazon", "https://www.amazon.com/"),
    Shortcut("Wikipedia", "https://www.wikipedia.org/"),
    Shortcut("Facebook", "https://www.facebook.com/"),
    Shortcut("X", "https://x.com/"),
    Shortcut("Netflix", "https://www.netflix.com/"),
    Shortcut("Spotify", "https://open.spotify.com/"),
)
