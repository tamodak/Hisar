package org.tamodak.hisar.ui.browser

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
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
import androidx.core.view.drawToBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.json.JSONTokener
import org.tamodak.hisar.MainActivity
import org.tamodak.hisar.R
import org.tamodak.hisar.data.model.UserSettings
import org.tamodak.hisar.data.repository.FilterRepository
import org.tamodak.hisar.data.repository.OfflinePageRepository
import org.tamodak.hisar.web.LinkTarget
import org.tamodak.hisar.filter.BlockReason
import org.tamodak.hisar.filter.Decision
import org.tamodak.hisar.web.HisarWebViewClient
import org.tamodak.hisar.web.PageStyles
import org.tamodak.hisar.web.PageGuardScript
import org.tamodak.hisar.web.WebViewCallbacks
import org.tamodak.hisar.web.WebViewFactory

/** The activity's launchers for what needs another app: the file picker, speech, camera and photos. */
interface SystemLaunchers {
    fun pickFiles(intent: Intent)
    fun requestStoragePermission(onResult: (Boolean) -> Unit)
    fun recognizeSpeech(): Boolean
    fun takePhoto()
    fun pickPhoto()
}

/**
 * The browser's UI-layer state holder. It owns the tabs' WebViews — Views, which a ViewModel must
 * never hold — and the state of the browser chrome (address bar focus, toolbar, menus and sheets).
 * It runs the UI logic that needs a View or the window, and hands everything else to
 * [BrowserViewModel], whose state it follows: when a tab's [TabState.pendingLoad] is set, this
 * is what loads it.
 *
 * Created by the activity and destroyed with it; the activity keeps itself across configuration
 * changes, so WebViews and their pages survive rotation.
 */
class BrowserController(
    private val activity: ComponentActivity,
    val viewModel: BrowserViewModel,
    private val filters: FilterRepository,
    private val launchers: SystemLaunchers,
) : WebViewCallbacks {

    private val factory = WebViewFactory(activity, filters, this, activity.lifecycleScope, Dispatchers.Default)
    /**
     * The tabs' WebViews. Snapshot state, so the screen attaches a WebView the moment it is
     * created: a page that starts loading in a detached, 0×0 WebView looks like a bot to Google.
     */
    private val webViews = mutableStateMapOf<Long, WebView>()
    private val simpleModeScripts = HashMap<Long, ScriptHandler>()
    /** The in-page guard, built once the word list is loaded. */
    private var pageGuardScript: String? = null
    private var settings = UserSettings()
    private var currentTabId: Long? = null

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
    /** Full-screen video, shown over everything. */
    var fullscreenView by mutableStateOf<View?>(null)
        private set
    /** Each tab as it was last seen, for the tab grid. */
    val thumbnails = mutableStateMapOf<Long, Bitmap>()

    private var imageSearchInNewTab = false
    private var voiceSearchInNewTab = false
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private var scrollRun = 0
    private val density = activity.resources.displayMetrics.density

    init {
        activity.lifecycleScope.launch {
            val script = PageGuardScript.build(filters.guard())
            pageGuardScript = script
            // WebViews created while the list was loading get it now.
            webViews.values.forEach { factory.installPageGuard(it, script) }
        }
        // Follow the ViewModel's state while the activity is visible, as the lifecycle guidance
        // recommends: WebViews are created, loaded and released to match the tabs.
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.uiState.collect(::sync) }
                launch { viewModel.settings.collect(::applySettings) }
            }
        }
    }

    fun webView(tabId: Long?): WebView? = tabId?.let(webViews::get)

    private fun sync(state: BrowserUiState) {
        // Release the WebViews of closed tabs.
        (webViews.keys - state.tabs.map { it.id }.toSet()).forEach(::destroyWebView)
        val current = state.currentTab
        if (current?.id != currentTabId) {
            webView(currentTabId)?.onPause()
            currentTabId = current?.id
            findInPageOpen = false
            toolbarVisible = true
            openGroupId = null
        }
        if (current != null && !current.showNewTabPage) webViewFor(current).onResume()
        // Load what tabs ask for: the current one always, others once they have a WebView.
        for (tab in state.tabs) {
            val url = tab.pendingLoad ?: continue
            val view = if (tab.id == current?.id && !tab.showNewTabPage) webViewFor(tab) else webViews[tab.id] ?: continue
            viewModel.onPendingLoadConsumed(tab.id)
            // Opened on purpose: a page this tab blocked for its content gets a fresh look.
            (view.webViewClient as? HisarWebViewClient)?.forget(url)
            // Saved pages need content access, for this load only (see HisarWebViewClient.onPageStarted).
            if (OfflinePageRepository.isOfflineUrl(activity, url)) view.settings.allowContentAccess = true
            view.loadUrl(url)
        }
    }

    private fun webViewFor(tab: TabState): WebView = webViews.getOrPut(tab.id) {
        factory.create(tab.id, tab.desktopMode, settings.textZoom).also { view ->
            pageGuardScript?.let { factory.installPageGuard(view, it) }
            applySimpleMode(tab.id, view)
        }
    }

    private fun destroyWebView(tabId: Long) {
        val view = webViews.remove(tabId) ?: return
        simpleModeScripts.remove(tabId)
        thumbnails.remove(tabId)
        (view.parent as? ViewGroup)?.removeView(view)
        view.stopLoading()
        view.destroy()
    }

    private fun applySettings(new: UserSettings) {
        val simpleChanged = new.simpleMode != settings.simpleMode || new.simpleFont != settings.simpleFont
        settings = new
        webViews.values.forEach { it.settings.textZoom = new.textZoom }
        if (simpleChanged) webViews.forEach { (id, view) -> applySimpleMode(id, view) }
    }

    /**
     * Installs simple mode's stylesheet as a document-start script, so new pages are restyled
     * before their first paint, and applies it to the page already showing.
     */
    private fun applySimpleMode(tabId: Long, view: WebView) {
        val css = if (settings.simpleMode) PageStyles.simpleModeCss(settings.simpleFont) else null
        simpleModeScripts.remove(tabId)?.remove()
        if (css != null && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            simpleModeScripts[tabId] = WebViewCompat.addDocumentStartJavaScript(view, PageStyles.applyScript(css), setOf("*"))
        }
        view.evaluateJavascript(PageStyles.applyScript(css), null)
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
        val view = webViews[tab.id] ?: return
        if (tab.showNewTabPage || view.width == 0 || view.height == 0) return
        runCatching { view.drawToBitmap().let { it.scale(it.width / 3, it.height / 3) } }.getOrNull()?.let { thumbnails[tab.id] = it }
    }

    /** Returns false when there is nowhere to go back to, so the system can leave the app. */
    fun goBack(): Boolean {
        val state = viewModel.uiState.value
        val tab = state.currentTab ?: return false
        val view = webViews[tab.id]
        val steps = view?.let(::stepsBackToAllowedPage)
        when {
            tab.showNewTabPage && tab.newTabPageCoversPage -> viewModel.hideNewTabPage(tab.id)
            !tab.showNewTabPage && steps != null -> view.goBackOrForward(-steps)
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
     * How many steps back the nearest page Hisar would show is, skipping blocked pages (and
     * SafeSearch hops, which would only bounce forward again). Null if there is none: going back
     * must never reload something blocked, nor land on a block page.
     */
    private fun stepsBackToAllowedPage(view: WebView): Int? {
        val guard = filters.guardOrNull ?: return if (view.canGoBack()) 1 else null
        val tabMemory = view.webViewClient as? HisarWebViewClient
        val history = view.copyBackForwardList()
        for (index in history.currentIndex - 1 downTo 0) {
            val url = history.getItemAtIndex(index).url
            val blockedHere = tabMemory?.isContentBlocked(url) == true
            if (!blockedHere && guard.checkNavigation(url) is Decision.Allow) return history.currentIndex - index
        }
        return null
    }

    fun goForward() {
        val tab = viewModel.uiState.value.currentTab ?: return
        if (tab.showNewTabPage && tab.hasPage) viewModel.hideNewTabPage(tab.id) else webViews[tab.id]?.goForward()
    }

    fun reload() = webView(currentTabId)?.reload()
    fun stop() = webView(currentTabId)?.stopLoading()

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
        webViews[id]?.let {
            WebViewFactory.applyUserAgent(it, enabled)
            it.reload()
        }
    }

    // --- Page tools ----------------------------------------------------------------------------

    /** Chrome's "Simplified view": the article alone, in a clean reading layout. */
    fun showSimplifiedView() {
        val tab = viewModel.uiState.value.currentTab ?: return
        val view = webViews[tab.id] ?: return
        view.evaluateJavascript(PageStyles.READER_EXTRACT_JS) { json ->
            val result = runCatching { JSONObject(JSONTokener(json).nextValue() as String) }.getOrNull()
            if (result == null || result.optInt("length") == 0) {
                viewModel.showMessage(R.string.reader_unavailable)
                return@evaluateJavascript
            }
            val font = if (settings.simpleMode) settings.simpleFont.css else "Georgia, serif"
            val html = PageStyles.readerHtml(result.optString("title"), tab.host.orEmpty(), result.optString("html"), isDark(), font)
            viewModel.setPendingReader(tab.id)
            view.loadDataWithBaseURL(tab.url, html, "text/html", "utf-8", tab.url)
        }
    }

    fun savePageOffline() {
        val tab = viewModel.uiState.value.currentTab?.takeIf { it.onWebPage } ?: return
        val view = webViews[tab.id] ?: return
        viewModel.savePageOffline(tab.url, tab.title) { file, done ->
            view.saveWebArchive(file.path, false) { saved -> done(saved != null) }
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
            webViews.values.forEach { it.clearHistory() }
        }
        if (cookies) {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            WebStorage.getInstance().deleteAllData()
        }
        if (cache) {
            // The cache is shared by every WebView; any one can clear it.
            val view = webViews.values.firstOrNull() ?: WebView(activity).also { it.destroy() }
            view.clearCache(true)
        }
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

    private fun userAgent() = WebViewFactory.mobileUserAgent(WebSettings.getDefaultUserAgent(activity))

    private fun isDark(): Boolean = settings.simpleMode ||
        (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    // --- WebViewCallbacks ----------------------------------------------------------------------

    override fun onPageStarted(tabId: Long, url: String, favicon: Bitmap?) {
        viewModel.onPageStarted(tabId, url, favicon)
        if (tabId == currentTabId) toolbarVisible = true
    }

    override fun onPageVisible(tabId: Long, view: WebView) = reapplySimpleMode(view)

    override fun onPageFinished(tabId: Long, view: WebView, url: String) {
        reapplySimpleMode(view)
        viewModel.onPageFinished(tabId)
    }

    /** For WebViews without document-start scripts, simple mode is re-applied as pages load. */
    private fun reapplySimpleMode(view: WebView) {
        if (settings.simpleMode) view.evaluateJavascript(PageStyles.applyScript(PageStyles.simpleModeCss(settings.simpleFont)), null)
    }

    override fun onHistoryUpdated(tabId: Long, view: WebView, url: String, isReload: Boolean) =
        viewModel.onHistoryUpdated(tabId, url, view.canGoBack(), view.canGoForward(), isReload)

    override fun onProgress(tabId: Long, progress: Int) = viewModel.onProgress(tabId, progress)
    override fun onTitle(tabId: Long, title: String) = viewModel.onTitle(tabId, title)
    override fun onIcon(tabId: Long, pageUrl: String?, icon: Bitmap) = viewModel.onIcon(tabId, pageUrl, icon)
    override fun onAdBlocked(tabId: Long) = viewModel.onAdBlocked(tabId)

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

    override fun onDownload(tabId: Long, url: String, userAgent: String?, contentDisposition: String?, mimeType: String?) {
        val tab = viewModel.uiState.value.tab(tabId)
        download(url, userAgent, contentDisposition, mimeType, referer = tab?.url)
        // A link that turns out to be a file leaves its tab empty; Chrome closes such a tab.
        if (tab != null && webViews[tabId]?.copyBackForwardList()?.size == 0) viewModel.closeTab(tabId, undoable = false)
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

    override fun onCreateWindow(openerTabId: Long): WebView? {
        captureThumbnail()
        val id = viewModel.createWindowTab(openerTabId)
        val tab = viewModel.uiState.value.tab(id) ?: return null
        return webViewFor(tab)
    }

    override fun onCloseWindow(view: WebView) {
        webViews.entries.firstOrNull { it.value === view }?.let { viewModel.closeTab(it.key, undoable = false) }
    }

    override fun onShowFullscreen(view: View, callback: WebChromeClient.CustomViewCallback) {
        fullscreenView = view
        fullscreenCallback = callback
        WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onHideFullscreen() = exitFullscreen()

    fun exitFullscreen() {
        if (fullscreenView == null) return
        WindowCompat.getInsetsController(activity.window, activity.window.decorView).show(WindowInsetsCompat.Type.systemBars())
        fullscreenCallback?.onCustomViewHidden()
        fullscreenCallback = null
        fullscreenView = null
    }

    override fun onShowFileChooser(callback: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams): Boolean {
        fileChooserCallback?.onReceiveValue(null)
        fileChooserCallback = callback
        return try {
            launchers.pickFiles(params.createIntent())
            true
        } catch (_: ActivityNotFoundException) {
            fileChooserCallback = null
            false
        }
    }

    fun onFilesPicked(resultCode: Int, data: Intent?) {
        fileChooserCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
        fileChooserCallback = null
    }

    override fun onNavigationBlocked(tabId: Long, reason: BlockReason) = viewModel.onNavigationBlocked(reason)

    override fun onBlockedPageBack(tabId: Long) {
        if (tabId != currentTabId) return
        // Nothing allowed behind the block page: the new tab page stands in.
        if (!goBack()) viewModel.showNewTabPage(tabId)
    }

    override fun onRendererGone(tabId: Long, view: WebView) {
        destroyWebView(tabId)
        viewModel.onRendererGone(tabId)
    }

    // --- Lifecycle -----------------------------------------------------------------------------

    fun onStop() {
        webViews.values.firstOrNull()?.pauseTimers()
        CookieManager.getInstance().flush()
    }

    fun onStart() {
        webViews.values.firstOrNull()?.resumeTimers()
    }

    fun destroy() {
        webViews.keys.toList().forEach(::destroyWebView)
    }

    private companion object {
        const val TOP_SLOP_DP = 24
        const val HIDE_AFTER_DP = 32
        const val SHOW_AFTER_DP = 16
    }
}
