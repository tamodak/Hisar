package org.tamodak.hisar.ui.browser

import android.graphics.Bitmap
import androidx.annotation.StringRes
import androidx.core.net.toUri
import org.tamodak.hisar.data.model.TabGroup
import org.tamodak.hisar.search.Omnibox

/** One tab as the UI shows it. The live page itself is in the session the UI layer keeps for it. */
data class TabState(
    val id: Long,
    val url: String = "",
    val title: String = "",
    /** The tab this one was opened from; Back on its first page returns there, as in Chrome. */
    val parentId: Long? = null,
    val groupId: Long? = null,
    val progress: Int = 0,
    val loading: Boolean = false,
    val historyBack: Boolean = false,
    val historyForward: Boolean = false,
    val favicon: Bitmap? = null,
    val desktopMode: Boolean = false,
    /** Showing the simplified (reader) view of the page rather than the page itself. */
    val readerMode: Boolean = false,
    /** The next page to start is the reader view. */
    val pendingReader: Boolean = false,
    /** Ads and trackers blocked on the current page. */
    val blockedOnPage: Int = 0,
    val showNewTabPage: Boolean = url.isEmpty(),
    /** The tab began on the new tab page, so Back from its first web page returns to it. */
    val startedOnNewTabPage: Boolean = url.isEmpty(),
    /** The new tab page was opened over a page (Home button), so Back returns to that page. */
    val newTabPageCoversPage: Boolean = false,
    /** A page has loaded in this tab at some point, so there is something under the new tab page. */
    val hasPage: Boolean = false,
    /** A URL the tab should load next. The UI layer loads it into the tab's page and clears it. */
    val pendingLoad: String? = url.ifEmpty { null },
    /** Set while the tab shows a saved (offline) page. */
    val offlinePageId: Long? = null,
) {
    val canGoBack: Boolean
        get() = if (showNewTabPage) newTabPageCoversPage || parentId != null else historyBack || startedOnNewTabPage || parentId != null

    val canGoForward: Boolean
        get() = if (showNewTabPage) !newTabPageCoversPage && hasPage else historyForward

    val displayTitle: String get() = title.ifBlank { Omnibox.display(url) }
    val host: String? get() = url.toUri().host

    /** On a real web page (not the new tab page or a saved copy): sharing, bookmarking and the like apply. */
    val onWebPage: Boolean get() = !showNewTabPage && offlinePageId == null && (url.startsWith("http://") || url.startsWith("https://"))
}

data class BrowserUiState(
    /** False until the last session's tabs are back; the splash screen stays up until then. */
    val restored: Boolean = false,
    val tabs: List<TabState> = emptyList(),
    val groups: List<TabGroup> = emptyList(),
    val currentTabId: Long? = null,
    val messages: List<UserMessage> = emptyList(),
) {
    val currentTab: TabState? get() = tabs.firstOrNull { it.id == currentTabId }
    fun tab(id: Long): TabState? = tabs.firstOrNull { it.id == id }
    fun groupOf(tab: TabState): TabGroup? = tab.groupId?.let { id -> groups.firstOrNull { it.id == id } }
    fun tabsIn(group: TabGroup): List<TabState> = tabs.filter { it.groupId == group.id }
}

/** A message for the snackbar, shown once and then reported back with [BrowserViewModel.onMessageShown]. */
data class UserMessage(
    val id: Long,
    @StringRes val text: Int,
    val args: List<String> = emptyList(),
    @StringRes val actionLabel: Int? = null,
    val action: MessageAction? = null,
)

sealed interface MessageAction {
    data class UndoClose(val tab: TabState, val index: Int, val group: TabGroup?) : MessageAction
    data class SwitchToTab(val tabId: Long) : MessageAction
    data class OpenDownload(val downloadId: Long) : MessageAction
    data object OpenDownloads : MessageAction
}

/** One row under the address bar while typing. */
data class Suggestion(val kind: Kind, val text: String, val url: String, val subtitle: String? = null) {
    enum class Kind { SEARCH, URL, HISTORY, BOOKMARK }
}
