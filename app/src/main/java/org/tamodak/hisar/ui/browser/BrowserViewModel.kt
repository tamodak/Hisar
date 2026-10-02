package org.tamodak.hisar.ui.browser

import android.graphics.Bitmap
import android.net.Uri
import android.webkit.URLUtil
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.tamodak.hisar.R
import org.tamodak.hisar.data.model.DownloadStatus
import org.tamodak.hisar.data.model.DefaultShortcuts
import org.tamodak.hisar.data.model.Shortcut
import org.tamodak.hisar.data.model.OfflinePage
import org.tamodak.hisar.data.model.SavedSession
import org.tamodak.hisar.data.model.SavedTab
import org.tamodak.hisar.data.model.TabGroup
import org.tamodak.hisar.data.model.UserSettings
import org.tamodak.hisar.data.repository.BookmarkRepository
import org.tamodak.hisar.data.repository.DownloadRepository
import org.tamodak.hisar.data.repository.FaviconRepository
import org.tamodak.hisar.data.repository.FilterRepository
import org.tamodak.hisar.data.repository.HistoryRepository
import org.tamodak.hisar.data.repository.ImageSearchRepository
import org.tamodak.hisar.data.repository.OfflinePageRepository
import org.tamodak.hisar.data.repository.RecentTabsRepository
import org.tamodak.hisar.data.repository.SettingsRepository
import org.tamodak.hisar.data.repository.ShortcutRepository
import org.tamodak.hisar.data.repository.SuggestionRepository
import org.tamodak.hisar.data.repository.TabRepository
import org.tamodak.hisar.di.DefaultDispatcher
import org.tamodak.hisar.filter.BlockReason
import org.tamodak.hisar.filter.Decision
import org.tamodak.hisar.filter.SafeSearch
import org.tamodak.hisar.search.Omnibox
import java.io.File
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

/**
 * The browser's business logic and state: tabs and groups, what each tab is showing, and everything
 * that touches data (history, bookmarks, downloads, saved pages, the saved session).
 *
 * It never holds a WebView or a Context. The UI layer keeps the WebViews, reports their events
 * here, and loads whatever a tab's [TabState.pendingLoad] asks for.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class BrowserViewModel @Inject constructor(
    private val filters: FilterRepository,
    private val settingsRepository: SettingsRepository,
    private val history: HistoryRepository,
    private val shortcuts: ShortcutRepository,
    private val bookmarks: BookmarkRepository,
    private val recentTabs: RecentTabsRepository,
    private val offlinePageRepository: OfflinePageRepository,
    private val downloads: DownloadRepository,
    private val favicons: FaviconRepository,
    private val tabRepository: TabRepository,
    private val suggestionRepository: SuggestionRepository,
    private val imageSearch: ImageSearchRepository,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val _uiState = MutableStateFlow(BrowserUiState())
    val uiState: StateFlow<BrowserUiState> = _uiState.asStateFlow()

    val settings: StateFlow<UserSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, UserSettings())

    val bookmarkedUrls: StateFlow<Set<String>> = bookmarks.bookmarks
        .map { list -> list.mapTo(HashSet()) { it.url } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val offlinePages: StateFlow<List<OfflinePage>> = offlinePageRepository.pages
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * The new tab page's tiles, as in Chrome: the user's pinned shortcuts first, then their
     * most-visited sites, then popular defaults until there is history to replace them. Removed
     * tiles stay removed, and tiles are links Hisar offers, so nothing it would block appears.
     */
    val tiles: StateFlow<List<Shortcut>> = combine(
        shortcuts.pinned,
        history.mostVisited(24),
        settings.map { it.hiddenTiles }.distinctUntilChanged(),
    ) { pinned, visited, hidden ->
        // One tile per site; a removed tile hides its whole site, not just one address.
        val hiddenSites = hidden.mapTo(HashSet(), ::siteOf)
        val taken = pinned.mapTo(HashSet()) { siteOf(it.url) }
        val suggested = visited.map { Shortcut(it.title.ifBlank { siteOf(it.url) }, it.url) } + DefaultShortcuts
        pinned + suggested.filter { siteOf(it.url) !in hiddenSites && taken.add(siteOf(it.url)) }
    }.mapLatest { candidates ->
        val guard = filters.guard()
        candidates.filter { guard.allows(it.url) && guard.isClean(it.title) }.take(MAX_TILES)
    }.flowOn(defaultDispatcher).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val omniboxInput = MutableStateFlow("")

    /** Suggestions for what is being typed: local ones at once, the search engine's after a pause. */
    val suggestions: StateFlow<List<Suggestion>> = omniboxInput.transformLatest { input ->
        val text = input.trim()
        if (text.isEmpty()) {
            emit(emptyList())
            return@transformLatest
        }
        val local = localSuggestions(text)
        emit(local)
        val current = settings.value
        // Typed text with a blocked word is never sent for suggestions.
        if (!current.searchSuggestions || !filters.guard().isClean(text)) return@transformLatest
        delay(SUGGEST_DEBOUNCE_MILLIS) // One request per pause in typing, not per keystroke.
        val remote = suggestionRepository.suggestions(current.searchEngine, filters.guard(), text)
            .map { Suggestion(Suggestion.Kind.SEARCH, it, current.searchEngine.searchUrl(it)) }
        emit((local + remote).distinctBy { it.kind to it.text.lowercase() })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Tab and group ids. Seeded from the clock so tabs opened before the saved session is back
     * (a widget tap at cold start) can't collide with restored ones.
     */
    private val nextId = AtomicLong(System.currentTimeMillis())
    private val messageIds = AtomicLong(1)
    /** Links opened from outside before the last session was restored. */
    private val pendingExternal = mutableListOf<String>()

    init {
        viewModelScope.launch { restoreSession() }
    }

    private suspend fun restoreSession() {
        val saved = tabRepository.load()
        val maxSaved = (saved.tabs.map { it.id } + saved.groups.map { it.id }).maxOrNull() ?: 0
        nextId.updateAndGet { maxOf(it, maxSaved + 1) }
        val restoredTabs = saved.tabs.map { TabState(id = it.id, url = it.url, title = it.title, groupId = it.groupId) }
        _uiState.update {
            // Tabs opened while restoring (from the widget) stay, after the restored ones.
            val tabs = restoredTabs + it.tabs
            it.copy(
                restored = true,
                tabs = tabs,
                groups = saved.groups + it.groups,
                currentTabId = it.currentTabId
                    ?: saved.selectedId?.takeIf { id -> tabs.any { t -> t.id == id } }
                    ?: tabs.lastOrNull()?.id,
            )
        }
        pendingExternal.forEach { newTab(it) }
        pendingExternal.clear()
        if (_uiState.value.tabs.isEmpty()) newTab()
        // From here on, every change to the tabs is saved for next time.
        uiState.filter { it.restored }.map(::toSession).distinctUntilChanged().collect(tabRepository::save)
    }

    private fun toSession(state: BrowserUiState) = SavedSession(
        // Saved pages are reopened from Downloads, not restored as tabs.
        tabs = state.tabs.map { SavedTab(it.id, if (it.offlinePageId != null) "" else it.url, it.title, it.groupId) },
        groups = state.groups,
        selectedId = state.currentTabId,
    )

    // --- Tabs ------------------------------------------------------------------------------------

    /** Opens a tab. With no [url] it shows the new tab page. Returns its id. */
    fun newTab(
        url: String? = null,
        parentId: Long? = null,
        select: Boolean = true,
        index: Int? = null,
        groupId: Long? = null,
        offlinePageId: Long? = null,
    ): Long {
        val id = nextId.getAndIncrement()
        val tab = TabState(id = id, url = url.orEmpty(), parentId = parentId, groupId = groupId, offlinePageId = offlinePageId)
            .let { if (url != null) it.copy(pendingLoad = SafeSearch.enforce(url) ?: url) else it }
        _uiState.update { state ->
            val tabs = state.tabs.toMutableList()
            val position = index?.coerceIn(0, tabs.size)
                ?: parentId?.let { p -> tabs.indexOfFirst { it.id == p } + 1 }?.takeIf { it > 0 }
                ?: tabs.size
            tabs.add(position, tab)
            state.copy(tabs = tabs, currentTabId = if (select) id else state.currentTabId)
        }
        return id
    }

    /** Links opened from other apps get their own tab, as in Chrome. */
    fun openExternalUrl(url: String) {
        if (_uiState.value.restored) newTab(url) else pendingExternal += url
    }

    fun selectTab(id: Long) = _uiState.update { it.copy(currentTabId = id) }

    fun closeTab(id: Long, undoable: Boolean = true) {
        val state = _uiState.value
        val index = state.tabs.indexOfFirst { it.id == id }
        if (index < 0) return
        val tab = state.tabs[index]
        val group = state.groupOf(tab)
        _uiState.update { s ->
            val tabs = s.tabs.filterNot { it.id == id }
            val current = if (s.currentTabId != id) {
                s.currentTabId
            } else {
                (tabs.firstOrNull { it.id == tab.parentId } ?: tabs.getOrNull(index) ?: tabs.getOrNull(index - 1))?.id
            }
            s.copy(tabs = tabs, groups = s.groups.filter { g -> tabs.any { it.groupId == g.id } }, currentTabId = current)
        }
        viewModelScope.launch { recentTabs.add(tab.url, tab.title) }
        if (undoable) {
            showMessage(R.string.tab_closed, actionLabel = R.string.undo, action = MessageAction.UndoClose(tab, index, group))
        }
        if (_uiState.value.tabs.isEmpty() && !undoable) newTab()
    }

    fun closeAllTabs() {
        _uiState.update { it.copy(tabs = emptyList(), groups = emptyList(), currentTabId = null) }
        newTab()
    }

    fun reopenClosed(url: String) {
        viewModelScope.launch { recentTabs.remove(url) }
        newTab(url)
    }

    /** Swiping the toolbar: the neighbouring tab, within the group if the current tab has one. */
    fun switchToNeighbour(step: Int) {
        val state = _uiState.value
        val current = state.currentTab ?: return
        val pool = state.groupOf(current)?.let(state::tabsIn) ?: state.tabs
        pool.getOrNull(pool.indexOfFirst { it.id == current.id } + step)?.let { selectTab(it.id) }
    }

    /** A page asked for a new window (target=_blank, window.open): it gets a tab of its own. */
    fun createWindowTab(openerId: Long): Long {
        val opener = _uiState.value.tab(openerId)
        val id = newTab(parentId = openerId, groupId = opener?.groupId)
        updateTab(id) { it.copy(showNewTabPage = false, startedOnNewTabPage = false, pendingLoad = null) }
        return id
    }

    // --- Navigation ------------------------------------------------------------------------------

    /** What the user typed in the address bar, or spoke. */
    fun navigateInput(input: String, tabId: Long? = _uiState.value.currentTabId) {
        val url = Omnibox.toUrl(input, settings.value.searchEngine) ?: return
        navigate(url, tabId)
    }

    /** Sends [tabId] (the current tab by default) to [url]. SafeSearch is applied before the request goes out. */
    fun navigate(url: String, tabId: Long? = _uiState.value.currentTabId) {
        if (tabId == null) {
            newTab(url)
            return
        }
        val id = tabId
        val target = SafeSearch.enforce(url) ?: url
        // Checked before anything is sent: a blocked search or site never leaves the phone, and
        // the tab stays where it was instead of showing a block page.
        if (refuse(target)) return
        updateTab(id) {
            it.copy(
                url = target,
                pendingLoad = target,
                showNewTabPage = false,
                newTabPageCoversPage = false,
                readerMode = false,
                offlinePageId = null,
            )
        }
    }

    fun onPendingLoadConsumed(tabId: Long) = updateTab(tabId) { it.copy(pendingLoad = null) }

    fun goHome(tabId: Long) = updateTab(tabId) {
        if (it.showNewTabPage) it else it.copy(showNewTabPage = true, newTabPageCoversPage = true)
    }

    /** Back on the new tab page or a tab's first page; see [TabState.canGoBack]. */
    fun showNewTabPage(tabId: Long) = updateTab(tabId) { it.copy(showNewTabPage = true, newTabPageCoversPage = false) }
    fun hideNewTabPage(tabId: Long) = updateTab(tabId) { it.copy(showNewTabPage = false, newTabPageCoversPage = false) }

    fun setDesktopMode(tabId: Long, enabled: Boolean) = updateTab(tabId) { it.copy(desktopMode = enabled) }
    fun setPendingReader(tabId: Long) = updateTab(tabId) { it.copy(pendingReader = true) }

    fun showOriginal(tabId: Long) {
        val tab = _uiState.value.tab(tabId) ?: return
        navigate(tab.url, tabId)
    }

    /** Google Translate into the phone's language. The proxied page is filtered as its original. */
    fun translate(tabId: Long) {
        val tab = _uiState.value.tab(tabId)?.takeIf { it.onWebPage } ?: return
        val lang = Locale.getDefault().language
        navigate("https://translate.google.com/translate?sl=auto&tl=$lang&hl=$lang&u=" + URLEncoder.encode(tab.url, "UTF-8"), tabId)
    }

    /**
     * True, with a message, if Hisar would block [url]: it is not opened at all. Before the lists
     * have loaded (the first second after launch) the request filter still blocks it, on the phone.
     */
    private fun refuse(url: String): Boolean {
        val decision = filters.guardOrNull?.checkNavigation(url) as? Decision.Block ?: return false
        onNavigationBlocked(decision.reason)
        return true
    }

    /** Something was stopped before it left the phone; say so instead of showing a block page. */
    fun onNavigationBlocked(reason: BlockReason) = showMessage(
        when (reason) {
            BlockReason.BLOCKED_WORDS, BlockReason.PAGE_CONTENT -> R.string.search_blocked
            BlockReason.UNSAFE_SEARCH_ENGINE -> R.string.search_engine_blocked
            BlockReason.HARMFUL_SITE, BlockReason.AD_OR_TRACKER -> R.string.site_blocked
        },
    )

    // --- Events from the tab's WebView -----------------------------------------------------------

    fun onPageStarted(tabId: Long, url: String, favicon: Bitmap?) = updateTab(tabId) { tab ->
        val leftOffline = tab.offlinePageId != null && !isOfflineCopy(tab.offlinePageId, url)
        tab.copy(
            url = url,
            loading = true,
            blockedOnPage = 0,
            readerMode = tab.pendingReader,
            pendingReader = false,
            hasPage = true,
            favicon = favicon ?: favicons.cached(url.toUri().host),
            offlinePageId = if (leftOffline) null else tab.offlinePageId,
        )
    }

    fun onPageFinished(tabId: Long) = updateTab(tabId) { it.copy(loading = false) }

    fun onHistoryUpdated(tabId: Long, url: String, canGoBack: Boolean, canGoForward: Boolean, isReload: Boolean) {
        updateTab(tabId) { it.copy(url = url, historyBack = canGoBack, historyForward = canGoForward) }
        val tab = _uiState.value.tab(tabId) ?: return
        // The pre-SafeSearch URL is only a hop to the strict one; recording it would double entries.
        if (isReload || tab.offlinePageId != null || tab.readerMode || SafeSearch.enforce(url) != null) return
        viewModelScope.launch { history.record(url, tab.title) }
    }

    fun onProgress(tabId: Long, progress: Int) = updateTab(tabId) { it.copy(progress = progress) }

    fun onTitle(tabId: Long, title: String) {
        updateTab(tabId) { it.copy(title = title) }
        val tab = _uiState.value.tab(tabId) ?: return
        if (tab.offlinePageId == null && !tab.readerMode) viewModelScope.launch { history.updateTitle(tab.url, title) }
    }

    fun onIcon(tabId: Long, pageUrl: String?, icon: Bitmap) {
        updateTab(tabId) { it.copy(favicon = icon) }
        favicons.save(pageUrl?.let { it.toUri().host }, icon)
    }

    /** From WebView's network threads; [MutableStateFlow.update] is safe to call from any thread. */
    fun onAdBlocked(tabId: Long) = updateTab(tabId) { it.copy(blockedOnPage = it.blockedOnPage + 1) }

    /** The tab's WebView was lost with its renderer: the replacement reloads what it showed. */
    fun onRendererGone(tabId: Long) = updateTab(tabId) { tab ->
        // A saved page reloads from its archive, not from the address the archive reports.
        val offline = tab.offlinePageId?.let { id -> offlinePages.value.firstOrNull { it.id == id } }
        val reload = when {
            offline != null -> offlinePageRepository.contentUrl(offline)
            tab.showNewTabPage || tab.url.isEmpty() -> null
            else -> tab.url
        }
        tab.copy(loading = false, pendingLoad = reload)
    }

    private fun isOfflineCopy(pageId: Long, url: String): Boolean {
        val page = offlinePages.value.firstOrNull { it.id == pageId } ?: return false
        return url.startsWith("content://") || url == page.url
    }

    // --- Tab groups ------------------------------------------------------------------------------

    /** Puts [tabId] in a new group, with the next of Chrome's colours. */
    fun createGroup(tabId: Long): TabGroup {
        val group = TabGroup(nextId.getAndIncrement(), "", _uiState.value.groups.size % TabGroup.COLORS.size)
        _uiState.update { it.copy(groups = it.groups + group) }
        addToGroup(tabId, group.id)
        return group
    }

    /** Group members sit together in the grid and the strip, so a joining tab moves next to them. */
    fun addToGroup(tabId: Long, groupId: Long) = _uiState.update { state ->
        val tab = state.tab(tabId) ?: return@update state
        val tabs = state.tabs.filterNot { it.id == tabId }.toMutableList()
        val lastMember = tabs.indexOfLast { it.groupId == groupId }
        tabs.add(if (lastMember >= 0) lastMember + 1 else state.tabs.indexOf(tab), tab.copy(groupId = groupId))
        state.copy(tabs = tabs).pruneGroups()
    }

    fun removeFromGroup(tabId: Long) = _uiState.update { state ->
        state.copy(tabs = state.tabs.map { if (it.id == tabId) it.copy(groupId = null) else it }).pruneGroups()
    }

    fun ungroup(groupId: Long) = _uiState.update { state ->
        state.copy(tabs = state.tabs.map { if (it.groupId == groupId) it.copy(groupId = null) else it }).pruneGroups()
    }

    fun closeGroup(groupId: Long) {
        _uiState.value.tabs.filter { it.groupId == groupId }.forEach { closeTab(it.id, undoable = false) }
        if (_uiState.value.tabs.isEmpty()) newTab()
    }

    fun renameGroup(groupId: Long, name: String) = updateGroup(groupId) { it.copy(name = name) }
    fun recolorGroup(groupId: Long, colorIndex: Int) = updateGroup(groupId) { it.copy(colorIndex = colorIndex) }

    fun newTabInGroup(groupId: Long) {
        val last = _uiState.value.tabs.indexOfLast { it.groupId == groupId }
        newTab(index = if (last >= 0) last + 1 else null, groupId = groupId)
    }

    /** Chrome's "Open in new tab in group": joins the current tab's group, or starts one with it. */
    fun openInGroup(url: String) {
        if (refuse(url)) return
        val current = _uiState.value.currentTab ?: return openInBackground(url)
        val groupId = current.groupId ?: createGroup(current.id).id
        val last = _uiState.value.tabs.indexOfLast { it.groupId == groupId }
        newTab(url, parentId = current.id, select = false, index = last + 1, groupId = groupId)
        showMessage(R.string.opened_in_group)
    }

    fun openInBackground(url: String) {
        if (refuse(url)) return
        val id = newTab(url, parentId = _uiState.value.currentTabId, select = false)
        showMessage(R.string.opened_in_new_tab, actionLabel = R.string.switch_to_tab, action = MessageAction.SwitchToTab(id))
    }

    private fun BrowserUiState.pruneGroups() = copy(groups = groups.filter { g -> tabs.any { it.groupId == g.id } })

    private fun updateGroup(groupId: Long, change: (TabGroup) -> TabGroup) = _uiState.update { state ->
        state.copy(groups = state.groups.map { if (it.id == groupId) change(it) else it })
    }

    // --- Page actions ----------------------------------------------------------------------------

    fun toggleBookmark(url: String, title: String) {
        viewModelScope.launch {
            val added = bookmarks.toggle(url, title)
            showMessage(if (added) R.string.bookmarked else R.string.bookmark_removed)
        }
    }

    /** Chrome's "Download page": [write] saves the archive into the file it is handed. */
    fun savePageOffline(url: String, title: String, write: (File, (Boolean) -> Unit) -> Unit) {
        viewModelScope.launch {
            val file = offlinePageRepository.newFile()
            write(file) { saved ->
                if (!saved) {
                    showMessage(R.string.page_save_failed)
                } else {
                    viewModelScope.launch {
                        offlinePageRepository.add(url, title, file)
                        showMessage(R.string.page_saved, actionLabel = R.string.downloads, action = MessageAction.OpenDownloads)
                    }
                }
            }
        }
    }

    fun openOfflinePage(page: OfflinePage) {
        newTab(offlinePageRepository.contentUrl(page), offlinePageId = page.id)
    }

    /** Removes a tile; a pinned one is unpinned too. It doesn't come back as a suggestion. */
    fun removeShortcut(shortcut: Shortcut) {
        viewModelScope.launch {
            if (shortcut.pinned) shortcuts.unpin(shortcut.url)
            settingsRepository.hideTile(shortcut.url.trimEnd('/'))
        }
    }

    /**
     * Saves an edited tile, which pins it. Returns false if [address] isn't a web address. A
     * suggested tile that was edited into something else is hidden so it doesn't reappear.
     */
    fun editShortcut(original: Shortcut, title: String, address: String): Boolean {
        val text = address.trim()
        if (title.isBlank() || !Omnibox.looksLikeUrl(text)) return false
        val url = Omnibox.toUrl(text, settings.value.searchEngine) ?: return false
        viewModelScope.launch {
            shortcuts.pin(title.trim(), url, replacing = original.url.takeIf { original.pinned })
            if (!original.pinned && original.url != url) settingsRepository.hideTile(original.url.trimEnd('/'))
        }
        return true
    }

    private fun siteOf(url: String) = url.toUri().host?.removePrefix("www.") ?: url

    fun setSimpleMode(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setSimpleMode(enabled) }
    }

    fun clearHistoryData() {
        viewModelScope.launch {
            history.clear()
            favicons.clear()
        }
    }

    fun onOmniboxInput(text: String) {
        omniboxInput.value = text
    }

    private suspend fun localSuggestions(text: String): List<Suggestion> {
        val engine = settings.value.searchEngine
        val first = if (Omnibox.looksLikeUrl(text)) {
            Suggestion(Suggestion.Kind.URL, text, Omnibox.toUrl(text, engine)!!)
        } else {
            Suggestion(Suggestion.Kind.SEARCH, text, engine.searchUrl(text), engine.label)
        }
        val saved = bookmarks.search(text, 2).map { Suggestion(Suggestion.Kind.BOOKMARK, it.title, it.url, Omnibox.display(it.url)) }
        val visited = history.search(text, 6)
            .distinctBy { it.url }
            .filter { h -> saved.none { it.url == h.url } }
            .take(3)
            .map { Suggestion(Suggestion.Kind.HISTORY, it.title.ifBlank { it.url }, it.url, Omnibox.display(it.url)) }
        return listOf(first) + saved + visited
    }

    // --- Downloads -------------------------------------------------------------------------------

    /**
     * Whether a download may go ahead. Downloads go through the same filters as pages: nothing from
     * a blocked site or with a blocked name, and no app installers, which would let anyone install
     * an unfiltered browser.
     */
    fun checkDownload(url: String, contentDisposition: String?, mimeType: String?): Boolean {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            showMessage(R.string.download_unsupported)
            return false
        }
        val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
        val guard = filters.guardOrNull
        if (guard == null || guard.checkNavigation(url) is Decision.Block || !guard.isClean(fileName)) {
            showMessage(R.string.download_blocked)
            return false
        }
        if (mimeType == APK_MIME || fileName.endsWith(".apk", ignoreCase = true) || fileName.endsWith(".xapk", ignoreCase = true)) {
            showMessage(R.string.download_apk_blocked)
            return false
        }
        return true
    }

    fun startDownload(url: String, userAgent: String?, contentDisposition: String?, mimeType: String?, referer: String?) {
        val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
        viewModelScope.launch {
            downloads.enqueue(url, fileName, mimeType, userAgent, referer)
            showMessage(R.string.downloading, args = listOf(fileName), actionLabel = R.string.downloads, action = MessageAction.OpenDownloads)
        }
    }

    fun onDownloadNeedsStorage() = showMessage(R.string.download_needs_storage)

    /** DownloadManager's completion broadcast, for downloads Hisar started. */
    fun onDownloadComplete(downloadId: Long) {
        viewModelScope.launch {
            val item = downloads.find(downloadId) ?: return@launch
            when (downloads.status(downloadId).state) {
                DownloadStatus.State.DONE -> showMessage(
                    R.string.download_complete,
                    args = listOf(item.fileName),
                    actionLabel = R.string.open,
                    action = MessageAction.OpenDownload(downloadId),
                )
                DownloadStatus.State.FAILED -> showMessage(R.string.download_failed, args = listOf(item.fileName))
                else -> Unit
            }
        }
    }

    suspend fun downloadIntent(downloadId: Long) = downloads.openIntent(downloadId)

    fun onCannotOpenDownload() = showMessage(R.string.download_cannot_open)

    // --- Voice and image search ------------------------------------------------------------------

    /** What the recogniser heard. It becomes a search like typed text, so every filter applies. */
    fun onVoiceResult(text: String?, inNewTab: Boolean) {
        if (text.isNullOrBlank()) return
        val url = Omnibox.toUrl(text, settings.value.searchEngine) ?: return
        if (refuse(url)) return
        if (inNewTab) newTab(url) else navigate(url)
    }

    fun onVoiceUnavailable() = showMessage(R.string.voice_unavailable)

    fun searchImage(bitmap: Bitmap, inNewTab: Boolean, userAgent: String) = runImageSearch(inNewTab) { imageSearch.search(bitmap, userAgent) }
    fun searchImage(uri: Uri, inNewTab: Boolean, userAgent: String) = runImageSearch(inNewTab) { imageSearch.search(uri, userAgent) }

    /** Long-press "Search image": only images Hisar would load anyway are sent. */
    fun searchImageUrl(imageUrl: String, userAgent: String) {
        if (filters.guardOrNull?.checkSubresource(imageUrl) != Decision.Allow) {
            showMessage(R.string.image_search_failed)
            return
        }
        runImageSearch(inNewTab = true) { imageSearch.searchImageUrl(imageUrl, userAgent) }
    }

    private fun runImageSearch(inNewTab: Boolean, search: suspend () -> String?) {
        showMessage(R.string.image_search_running)
        viewModelScope.launch {
            val result = runCatching { search() }.getOrNull()
            when {
                result == null -> showMessage(R.string.image_search_failed)
                inNewTab -> newTab(result)
                else -> navigate(result)
            }
        }
    }

    // --- Messages --------------------------------------------------------------------------------

    fun onMessageShown(id: Long) = _uiState.update { s -> s.copy(messages = s.messages.filterNot { it.id == id }) }

    /** The snackbar's action. Ones that need the window (opening a file) are handled by the UI. */
    fun onMessageAction(action: MessageAction) {
        when (action) {
            is MessageAction.UndoClose -> {
                action.group?.let { g -> _uiState.update { s -> if (s.groups.any { it.id == g.id }) s else s.copy(groups = s.groups + g) } }
                viewModelScope.launch { recentTabs.remove(action.tab.url) }
                val url = action.tab.url.ifEmpty { null }
                newTab(url, index = action.index, groupId = action.group?.id)
            }
            is MessageAction.SwitchToTab -> if (_uiState.value.tab(action.tabId) != null) selectTab(action.tabId)
            is MessageAction.OpenDownload, MessageAction.OpenDownloads -> Unit
        }
    }

    fun showMessage(text: Int, args: List<String> = emptyList(), actionLabel: Int? = null, action: MessageAction? = null) {
        val message = UserMessage(messageIds.getAndIncrement(), text, args, actionLabel, action)
        // A new message replaces any still showing, as Chrome's do.
        _uiState.update { it.copy(messages = listOf(message)) }
    }

    private fun updateTab(id: Long, change: (TabState) -> TabState) = _uiState.update { state ->
        if (state.tabs.none { it.id == id }) state else state.copy(tabs = state.tabs.map { if (it.id == id) change(it) else it })
    }

    private companion object {
        const val APK_MIME = "application/vnd.android.package-archive"
        const val SUGGEST_DEBOUNCE_MILLIS = 150L
        /** Two rows of four, as on Chrome's new tab page. */
        const val MAX_TILES = 8
    }
}
