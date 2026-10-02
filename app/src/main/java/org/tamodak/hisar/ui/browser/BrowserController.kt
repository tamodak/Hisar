package org.tamodak.hisar.ui.browser

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.graphics.scale
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.PromptDelegate.FilePrompt
import org.mozilla.geckoview.StorageController
import org.mozilla.geckoview.WebResponse
import org.tamodak.hisar.MainActivity
import org.tamodak.hisar.R
import org.tamodak.hisar.data.model.UserSettings
import org.tamodak.hisar.data.repository.FaviconRepository
import org.tamodak.hisar.data.repository.FilterRepository
import org.tamodak.hisar.filter.BlockReason
import org.tamodak.hisar.filter.Decision
import org.tamodak.hisar.web.EngineEvents
import org.tamodak.hisar.web.GeckoEngine
import org.tamodak.hisar.web.GuardedGeckoView
import org.tamodak.hisar.web.LinkTarget
import org.tamodak.hisar.web.PagePrompt
import org.tamodak.hisar.web.PageStyles
import org.tamodak.hisar.web.TabCallbacks
import org.tamodak.hisar.web.TabSession

/** The activity's launchers for what needs another app: the file picker, speech, camera and photos. */
interface SystemLaunchers {
    fun pickFiles(intent: Intent)
    fun requestStoragePermission(onResult: (Boolean) -> Unit)
    fun recognizeSpeech(): Boolean
    fun takePhoto()
    fun pickPhoto()
}

/**
 * The browser's UI-layer state holder. It owns the tabs' pages — engine sessions and the view
 * they are shown in, which a ViewModel must never hold — and the state of the browser chrome
 * (address bar focus, toolbar, menus, sheets and the page's own dialogs). It runs the UI logic
 * that needs a View or the window, and hands everything else to [BrowserViewModel], whose state
 * it follows: when a tab's [TabState.pendingLoad] is set, this is what loads it.
 *
 * Created by the activity and destroyed with it; the activity keeps itself across configuration
 * changes, so the pages survive rotation.
 */
class BrowserController(
    private val activity: ComponentActivity,
    val viewModel: BrowserViewModel,
    private val filters: FilterRepository,
    private val engine: GeckoEngine,
    private val favicons: FaviconRepository,
    private val launchers: SystemLaunchers,
) : TabCallbacks, EngineEvents {

    /**
     * The one view pages are shown in; the current tab's session is attached to it. On-screen
     * keyboard input is checked here before any page sees it.
     */
    val pageView: GuardedGeckoView = GuardedGeckoView(activity, filters) {
        viewModel.onNavigationBlocked(BlockReason.BLOCKED_WORDS)
    }

    /** The tabs' pages, created when a tab first shows one. */
    private val sessions = mutableStateMapOf<Long, TabSession>()
    /** The engine's own tab ids, which its background reports use, to ours. */
    private val engineTabs = HashMap<Int, Long>()
    private var settings = UserSettings()
    private var currentTabId by mutableStateOf<Long?>(null)
    /** The last address each tab reported, to tell a reload from a new page. */
    private val lastLocation = HashMap<Long, String>()

    // --- Browser chrome state ---------------------------------------------------------------------

    /** Set while the address bar is being edited; the page gives way to suggestions. */
    var omniboxFocused by mutableStateOf(false)
    /** A one-shot request (new tab page search box, widget) to focus the address bar. */
    var omniboxFocusRequested by mutableStateOf(false)
    /** The toolbar slides away as the page scrolls down and back as it scrolls up, as Chrome's does. */
    var toolbarVisible by mutableStateOf(true)
        private set
    /** The new tab page's own search box is on screen, so the toolbar's address bar stays hidden. */
    var ntpSearchBoxVisible by mutableStateOf(false)
    var findInPageOpen by mutableStateOf(false)
    var linkMenu by mutableStateOf<LinkTarget?>(null)
    var pageInfoOpen by mutableStateOf(false)
    var clearDataOpen by mutableStateOf(false)
    var imageSearchOpen by mutableStateOf(false)
        private set
    var openGroupId by mutableStateOf<Long?>(null)
    /** A page (a video) is full screen: only the page shows. */
    var fullscreen by mutableStateOf(false)
        private set
    /** Each tab as it was last seen, for the tab grid. */
    val thumbnails = mutableStateMapOf<Long, Bitmap>()

    /** Dialogs pages opened, oldest first, with the tab that opened each. */
    private val prompts = mutableStateListOf<Pair<Long, PagePrompt>>()
    /** The current tab's oldest open dialog, shown over the page. */
    val prompt: PagePrompt? by derivedStateOf { prompts.firstOrNull { it.first == currentTabId }?.second }
    private var filePrompt: PagePrompt? = null

    private var imageSearchInNewTab = false
    private var voiceSearchInNewTab = false
    private var scrollRun = 0
    private val density = activity.resources.displayMetrics.density

    init {
        engine.events = this
        // Follow the ViewModel's state while the activity is visible, as the lifecycle guidance
        // recommends: sessions are created, loaded and released to match the tabs.
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.uiState.collect(::sync) }
                launch { viewModel.settings.collect(::applySettings) }
            }
        }
    }

    /** The tab's page, if it has one yet. */
    fun page(tabId: Long?): TabSession? = tabId?.let(sessions::get)

    private fun sync(state: BrowserUiState) {
        // Release the pages of closed tabs.
        (sessions.keys - state.tabs.map { it.id }.toSet()).forEach(::destroySession)
        val current = state.currentTab
        if (current?.id != currentTabId) {
            page(currentTabId)?.session?.setActive(false)
            currentTabId = current?.id
            findInPageOpen = false
            toolbarVisible = true
            openGroupId = null
        }
        if (current != null && !current.showNewTabPage) show(sessionFor(current))
        // Load what tabs ask for: the current one always, others once they have a page.
        for (tab in state.tabs) {
            val url = tab.pendingLoad ?: continue
            val page = if (tab.id == current?.id && !tab.showNewTabPage) sessionFor(tab) else sessions[tab.id] ?: continue
            viewModel.onPendingLoadConsumed(tab.id)
            page.load(url)
        }
    }

    /** Puts [page] in the view, as the page showing. */
    private fun show(page: TabSession) {
        if (pageView.session !== page.session) {
            pageView.releaseSession()
            pageView.setSession(page.session)
        }
        page.session.setActive(true)
    }

    private fun sessionFor(tab: TabState, open: Boolean = true): TabSession = sessions.getOrPut(tab.id) {
        TabSession(tab.id, activity, engine, filters, this, tab.desktopMode, open)
    }

    private fun destroySession(tabId: Long) {
        val page = sessions.remove(tabId) ?: return
        thumbnails.remove(tabId)
        lastLocation.remove(tabId)
        engineTabs.values.removeAll { it == tabId }
        prompts.filter { it.first == tabId }.forEach { (_, p) -> p.dismiss() }
        prompts.removeAll { it.first == tabId }
        if (pageView.session === page.session) pageView.releaseSession()
        page.close()
    }

    private fun applySettings(new: UserSettings) {
        val simpleChanged = new.simpleMode != settings.simpleMode || new.simpleFont != settings.simpleFont
        settings = new
        engine.setTextZoom(new.textZoom)
        if (simpleChanged) {
            val css = if (new.simpleMode) PageStyles.simpleModeCss(new.simpleFont) else null
            engine.setSimpleModeCss(css)
            // Open pages are restyled now; new ones get the style before their first paint.
            sessions.values.forEach { page ->
                activity.lifecycleScope.launch { page.command("simpleMode", JSONObject().put("css", css ?: JSONObject.NULL)) }
            }
        }
    }

    // --- Tabs and navigation ------------------------------------------------------------------------

    fun selectTab(tabId: Long) {
        captureThumbnail()
        viewModel.selectTab(tabId)
    }

    fun newTab() {
        captureThumbnail()
        viewModel.newTab()
    }

    /** Called before showing the tab grid, so the current tab's card shows the page as it is now. */
    fun captureThumbnail() {
        val tab = viewModel.uiState.value.currentTab ?: return
        if (tab.showNewTabPage || pageView.width == 0 || pageView.height == 0 || pageView.session !== page(tab.id)?.session) return
        pageView.capturePixels().accept({ bitmap ->
            bitmap?.let { thumbnails[tab.id] = it.scale(it.width / 3, it.height / 3) }
        }, { })
    }

    /** Returns false when there is nowhere to go back to, so the system can leave the app. */
    fun goBack(): Boolean {
        val state = viewModel.uiState.value
        val tab = state.currentTab ?: return false
        val page = sessions[tab.id]
        val target = page?.let(::allowedBackIndex)
        when {
            fullscreen -> exitFullscreen()
            tab.showNewTabPage && tab.newTabPageCoversPage -> viewModel.hideNewTabPage(tab.id)
            !tab.showNewTabPage && target != null -> if (target == page.historyIndex - 1) page.goBack() else page.goToHistoryIndex(target)
            !tab.showNewTabPage && tab.startedOnNewTabPage -> viewModel.showNewTabPage(tab.id)
            // First page of a tab opened from another: close it and return to the opener.
            tab.parentId != null && state.tab(tab.parentId) != null -> {
                viewModel.closeTab(tab.id, undoable = false)
                viewModel.selectTab(tab.parentId)
            }
            else -> return false
        }
        return true
    }

    /**
     * The nearest earlier page Hisar would show, skipping blocked pages (and SafeSearch hops,
     * which would only bounce forward again). Null if there is none: going back must never
     * reload something blocked.
     */
    private fun allowedBackIndex(page: TabSession): Int? {
        val guard = filters.guardOrNull
        if (guard == null || page.history.isEmpty()) return if (page.canGoBack) page.historyIndex - 1 else null
        for (index in page.historyIndex - 1 downTo 0) {
            if (guard.checkNavigation(page.history[index]) is Decision.Allow) return index
        }
        return null
    }

    fun goForward() {
        val tab = viewModel.uiState.value.currentTab ?: return
        if (tab.showNewTabPage && tab.hasPage) viewModel.hideNewTabPage(tab.id) else sessions[tab.id]?.goForward()
    }

    fun reload() {
        val page = page(currentTabId) ?: return
        page.url?.let { lastLocation[page.tabId] = it }
        page.reload()
    }

    fun stop() = page(currentTabId)?.stop()

    fun goHome() {
        currentTabId?.let(viewModel::goHome)
        findInPageOpen = false
    }

    fun focusOmnibox() {
        omniboxFocusRequested = true
    }

    fun setDesktopMode(enabled: Boolean) {
        val id = currentTabId ?: return
        viewModel.setDesktopMode(id, enabled)
        sessions[id]?.setDesktopMode(enabled)
    }

    // --- Page tools ----------------------------------------------------------------------------

    /** Chrome's "Simplified view": the article alone, in a clean reading layout. */
    fun showSimplifiedView() {
        val tab = viewModel.uiState.value.currentTab ?: return
        val page = sessions[tab.id] ?: return
        activity.lifecycleScope.launch {
            val article = page.command("reader")
            if (article == null || article.optInt("length") == 0) {
                viewModel.showMessage(R.string.reader_unavailable)
                return@launch
            }
            val font = if (settings.simpleMode) settings.simpleFont.css else "Georgia, serif"
            val html = PageStyles.readerHtml(article.optString("title"), tab.host.orEmpty(), article.optString("html"), isDark(), font)
            viewModel.setPendingReader(tab.id)
            page.session.load(GeckoSession.Loader().data(html, "text/html"))
        }
    }

    fun savePageOffline() {
        val tab = viewModel.uiState.value.currentTab?.takeIf { it.onWebPage } ?: return
        val page = sessions[tab.id] ?: return
        activity.lifecycleScope.launch {
            val html = page.command("save")?.optString("text")?.takeIf { it.isNotEmpty() }
            viewModel.savePageOffline(tab.url, tab.title, html)
        }
    }

    fun share(url: String, title: String? = null) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
            title?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
        }
        activity.startActivity(Intent.createChooser(send, null))
    }

    fun copy(text: String) {
        val clipboard = activity.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText(text, text))
        // Android 13 and later confirm copies themselves; a second message would be noise.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) viewModel.showMessage(R.string.copied)
    }

    fun addToHomeScreen() {
        val tab = viewModel.uiState.value.currentTab?.takeIf { it.onWebPage } ?: return
        val icon = tab.favicon?.let { IconCompat.createWithBitmap(it.scale(192, 192)) }
            ?: IconCompat.createWithResource(activity, R.mipmap.ic_launcher)
        val intent = Intent(Intent.ACTION_VIEW, tab.url.toUri()).setClass(activity, MainActivity::class.java)
        val shortcut = ShortcutInfoCompat.Builder(activity, "hisar-${tab.url.hashCode()}")
            .setShortLabel(tab.displayTitle.take(24))
            .setIcon(icon)
            .setIntent(intent)
            .build()
        if (!ShortcutManagerCompat.requestPinShortcut(activity, shortcut, null)) viewModel.showMessage(R.string.add_to_home_unsupported)
    }

    fun clearBrowsingData(history: Boolean, cookies: Boolean, cache: Boolean) {
        if (history) {
            viewModel.clearHistoryData()
            sessions.values.forEach { it.session.purgeHistory() }
        }
        var flags = 0L
        if (cookies) flags = flags or StorageController.ClearFlags.COOKIES or StorageController.ClearFlags.DOM_STORAGES or StorageController.ClearFlags.AUTH_SESSIONS
        if (cache) flags = flags or StorageController.ClearFlags.NETWORK_CACHE or StorageController.ClearFlags.IMAGE_CACHE
        if (flags != 0L) engine.runtime.storageController.clearData(flags)
        viewModel.showMessage(R.string.data_cleared)
    }

    fun openDownload(downloadId: Long) {
        activity.lifecycleScope.launch {
            val intent = viewModel.downloadIntent(downloadId)
            try {
                if (intent == null) throw ActivityNotFoundException()
                activity.startActivity(intent)
            } catch (_: ActivityNotFoundException) {
                viewModel.onCannotOpenDownload()
            }
        }
    }

    // --- Voice and image search ----------------------------------------------------------------

    fun startVoiceSearch(inNewTab: Boolean = false) {
        voiceSearchInNewTab = inNewTab
        if (!launchers.recognizeSpeech()) viewModel.onVoiceUnavailable()
    }

    fun onSpeechResult(text: String?) = viewModel.onVoiceResult(text, voiceSearchInNewTab)

    fun openImageSearch(inNewTab: Boolean = false) {
        imageSearchInNewTab = inNewTab
        imageSearchOpen = true
    }

    fun dismissImageSearch() {
        imageSearchOpen = false
    }

    fun takePhoto() = launchers.takePhoto()
    fun pickPhoto() = launchers.pickPhoto()

    fun onPhoto(bitmap: Bitmap?) {
        imageSearchOpen = false
        bitmap?.let { viewModel.searchImage(it, imageSearchInNewTab, userAgent()) }
    }

    fun onPhoto(uri: Uri?) {
        imageSearchOpen = false
        uri?.let { viewModel.searchImage(it, imageSearchInNewTab, userAgent()) }
    }

    fun searchImageUrl(imageUrl: String) = viewModel.searchImageUrl(imageUrl, userAgent())

    private fun userAgent(): String = GeckoSession.getDefaultUserAgent()

    private fun isDark(): Boolean = settings.simpleMode ||
        (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    // --- TabCallbacks ----------------------------------------------------------------------------

    override fun onPageStarted(tabId: Long, url: String) {
        viewModel.onPageStarted(tabId, url, null)
        if (tabId == currentTabId) toolbarVisible = true
    }

    override fun onPageFinished(tabId: Long) {
        viewModel.onPageFinished(tabId)
        loadFavicon(tabId)
    }

    /** The engine doesn't hand over site icons; they are fetched from sites the filters allow. */
    private fun loadFavicon(tabId: Long) {
        val url = viewModel.uiState.value.tab(tabId)?.url?.takeIf { it.startsWith("http") } ?: return
        val host = url.toUri().host ?: return
        activity.lifecycleScope.launch {
            val icon = favicons.load(host) ?: if (filters.guard().allows("https://$host/")) favicons.fetch(host) else null
            icon?.let { viewModel.onIcon(tabId, url, it) }
        }
    }

    /**
     * Every address a tab shows: a page loaded, or a single-page app moved with pushState, which
     * sends no request the filters could stop. Such a move to a blocked address is undone.
     */
    override fun onLocationChange(tabId: Long, url: String) {
        val page = sessions[tabId] ?: return
        val reload = lastLocation.put(tabId, url) == url
        when (val decision = filters.guardOrNull?.checkNavigation(url)) {
            is Decision.Block -> {
                viewModel.onNavigationBlocked(decision.reason)
                if (page.canGoBack) page.goBack() else viewModel.showNewTabPage(tabId)
                return
            }
            is Decision.Redirect -> {
                page.load(decision.url)
                return
            }
            else -> Unit
        }
        viewModel.onHistoryUpdated(tabId, url, page.canGoBack, page.canGoForward, reload)
    }

    override fun onHistoryState(tabId: Long, canGoBack: Boolean, canGoForward: Boolean) =
        viewModel.onHistoryState(tabId, canGoBack, canGoForward)

    override fun onProgress(tabId: Long, progress: Int) = viewModel.onProgress(tabId, progress)
    override fun onTitle(tabId: Long, title: String) = viewModel.onTitle(tabId, title)

    /** Hides the toolbar after a deliberate scroll down, shows it after a scroll up or at the top. */
    override fun onScroll(tabId: Long, y: Int, oldY: Int) {
        if (tabId != currentTabId || omniboxFocused || findInPageOpen) return
        val dy = y - oldY
        if (y <= TOP_SLOP_DP * density) {
            toolbarVisible = true
            scrollRun = 0
            return
        }
        // A run of scrolling in one direction; a change of direction starts a new run.
        scrollRun = if ((dy > 0) == (scrollRun > 0)) scrollRun + dy else dy
        if (scrollRun > HIDE_AFTER_DP * density) toolbarVisible = false
        if (scrollRun < -SHOW_AFTER_DP * density) toolbarVisible = true
    }

    override fun onLongPress(target: LinkTarget) {
        linkMenu = target
    }

    override fun onDownload(tabId: Long, response: WebResponse) {
        // DownloadManager fetches the file itself, with the engine's cookies.
        runCatching { response.body?.close() }
        val tab = viewModel.uiState.value.tab(tabId)
        fun header(name: String) = response.headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
        download(response.uri, null, header("Content-Disposition"), header("Content-Type")?.substringBefore(';')?.trim(), referer = tab?.url)
        // A link that turns out to be a file leaves its tab empty; Chrome closes such a tab.
        if (tab != null && sessions[tabId]?.history?.isEmpty() == true && !tab.hasPage) viewModel.closeTab(tabId, undoable = false)
    }

    fun download(url: String, userAgent: String? = null, contentDisposition: String? = null, mimeType: String? = null, referer: String? = null) {
        if (!viewModel.checkDownload(url, contentDisposition, mimeType)) return
        val start = { viewModel.startDownload(url, userAgent ?: userAgent(), contentDisposition, mimeType, referer) }
        // Saving to the shared Downloads folder needs storage access only before Android 10.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(activity, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            launchers.requestStoragePermission { granted -> if (granted) start() else viewModel.onDownloadNeedsStorage() }
        } else {
            start()
        }
    }

    override fun onNewWindow(openerTabId: Long, uri: String): GeckoSession? {
        captureThumbnail()
        val id = viewModel.createWindowTab(openerTabId)
        val tab = viewModel.uiState.value.tab(id) ?: return null
        // The engine opens the new window's session itself.
        return sessionFor(tab, open = false).session
    }

    override fun onCloseWindow(tabId: Long) = viewModel.closeTab(tabId, undoable = false)

    override fun onFullscreen(tabId: Long, fullscreen: Boolean) {
        if (tabId != currentTabId) return
        this.fullscreen = fullscreen
        WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
            if (fullscreen) {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    fun exitFullscreen() {
        page(currentTabId)?.session?.exitFullScreen()
        currentTabId?.let { onFullscreen(it, false) }
    }

    override fun onPrompt(tabId: Long, prompt: PagePrompt) {
        val files = prompt.prompt as? FilePrompt
        if (files == null) {
            prompts += tabId to prompt
            return
        }
        // Files come from the system picker, not a dialog of ours.
        filePrompt?.dismiss()
        filePrompt = prompt
        if (files.type == FilePrompt.Type.FOLDER) return dismissFilePrompt()
        val types = files.mimeTypes?.filter { it.isNotBlank() }.orEmpty()
        val intent = Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).apply {
            type = types.singleOrNull() ?: "*/*"
            if (types.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, types.toTypedArray())
            if (files.type == FilePrompt.Type.MULTIPLE) putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        try {
            launchers.pickFiles(intent)
        } catch (_: ActivityNotFoundException) {
            dismissFilePrompt()
        }
    }

    /** The page's dialog was answered (or dismissed) and goes away. */
    fun onPromptDone(prompt: PagePrompt) {
        prompts.removeAll { it.second === prompt }
    }

    fun onFilesPicked(resultCode: Int, data: Intent?) {
        val pending = filePrompt ?: return
        filePrompt = null
        val files = pending.prompt as FilePrompt
        val uris = buildList {
            data?.clipData?.let { clip -> for (i in 0 until clip.itemCount) add(clip.getItemAt(i).uri) }
            if (isEmpty()) data?.data?.let(::add)
        }
        if (resultCode != Activity.RESULT_OK || uris.isEmpty()) pending.dismiss() else pending.respond(files.confirm(activity, uris.toTypedArray()))
    }

    private fun dismissFilePrompt() {
        filePrompt?.dismiss()
        filePrompt = null
    }

    override fun onNavigationBlocked(tabId: Long, reason: BlockReason) = viewModel.onNavigationBlocked(reason)

    override fun onEngineTab(engineTabId: Int, tabId: Long) {
        engineTabs[engineTabId] = tabId
    }

    override fun onCrash(tabId: Long) {
        destroySession(tabId)
        viewModel.onRendererGone(tabId)
    }

    // --- EngineEvents ----------------------------------------------------------------------------

    override fun onRequestBlocked(engineTabId: Int) = viewModel.onNavigationBlocked(BlockReason.BLOCKED_WORDS)

    override fun onAdBlocked(engineTabId: Int) {
        engineTabs[engineTabId]?.let(viewModel::onAdBlocked)
    }

    // --- Lifecycle -----------------------------------------------------------------------------

    fun onStop() {
        page(currentTabId)?.session?.setActive(false)
    }

    fun onStart() {
        val tab = viewModel.uiState.value.currentTab ?: return
        if (!tab.showNewTabPage) page(tab.id)?.session?.setActive(true)
    }

    fun destroy() {
        if (engine.events === this) engine.events = null
        prompts.forEach { (_, p) -> p.dismiss() }
        prompts.clear()
        dismissFilePrompt()
        pageView.releaseSession()
        sessions.keys.toList().forEach(::destroySession)
    }

    private companion object {
        const val TOP_SLOP_DP = 24
        const val HIDE_AFTER_DP = 32
        const val SHOW_AFTER_DP = 16
    }
}
