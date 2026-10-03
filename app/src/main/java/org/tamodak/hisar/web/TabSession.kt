package org.tamodak.hisar.web

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.util.Log
import androidx.core.net.toUri
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.BasicSelectionActionDelegate
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.ContentDelegate.ContextElement
import org.mozilla.geckoview.GeckoSession.NavigationDelegate.LoadRequest
import org.mozilla.geckoview.GeckoSession.PermissionDelegate
import org.mozilla.geckoview.GeckoSession.PromptDelegate
import org.mozilla.geckoview.GeckoSession.PromptDelegate.PromptResponse
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebRequestError
import org.mozilla.geckoview.WebResponse
import org.tamodak.hisar.data.repository.FilterRepository
import org.tamodak.hisar.data.repository.OfflinePageRepository
import org.tamodak.hisar.filter.BlockReason
import org.tamodak.hisar.filter.Decision

/**
 * One tab's page: a GeckoSession with Hisar's rules on its navigation, and the link to the
 * content script in its page for the app's page commands.
 *
 * - [NavigationDelegate.onLoadRequest] is where a top-level navigation is decided before any
 *   request goes out: blocked ones are refused (the tab stays where it was), searches get strict
 *   SafeSearch, and schemes that would hand the page to another app are refused. The extension
 *   checks every request again, top-level ones included, so nothing gets past by another route
 *   (back, reload, a redirect).
 * - Everything else the page does reaches the UI layer through [TabCallbacks].
 */
class TabSession(
    val tabId: Long,
    private val activity: Activity,
    private val engine: GeckoEngine,
    private val filters: FilterRepository,
    private val callbacks: TabCallbacks,
    desktopMode: Boolean,
    /** False for a window a page opens: the engine opens its session itself. */
    open: Boolean = true,
) {
    val session = GeckoSession(
        GeckoSessionSettings.Builder()
            .userAgentMode(if (desktopMode) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP else GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
            .viewportMode(if (desktopMode) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP else GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
            .suspendMediaWhenInactive(true)
            .build(),
    )

    /** The tab's history, as the engine last reported it. */
    var history: List<String> = emptyList()
        private set
    var historyIndex: Int = -1
        private set
    var canGoBack = false
        private set
    var canGoForward = false
        private set
    var url: String? = null
        private set

    private var scrollY = 0
    private var contentPort: WebExtension.Port? = null
    private var nextCommand = 1
    private val replies = HashMap<Int, CompletableDeferred<JSONObject?>>()
    private val longReplies = HashMap<Int, StringBuilder>()


    /** True when the page is scrolled down, for pull-to-refresh. */
    val isScrolled: Boolean get() = scrollY > 0

    fun load(url: String) = engine.whenFilterReady { session.loadUri(url) }
    fun reload() = session.reload()
    fun stop() = session.stop()
    fun goBack() = session.goBack()
    fun goForward() = session.goForward()
    fun goToHistoryIndex(index: Int) = session.gotoHistoryIndex(index)

    fun setDesktopMode(enabled: Boolean) {
        session.settings.userAgentMode = if (enabled) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP else GeckoSessionSettings.USER_AGENT_MODE_MOBILE
        session.settings.viewportMode = if (enabled) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP else GeckoSessionSettings.VIEWPORT_MODE_MOBILE
        session.reload()
    }

    /**
     * Find in page. A null [query] moves to the next (or previous) match of the last one.
     * [onResult] gets the match shown (from 1) and how many there are.
     */
    fun find(query: String?, backwards: Boolean = false, onResult: (Int, Int) -> Unit) {
        session.finder.displayFlags = GeckoSession.FINDER_DISPLAY_HIGHLIGHT_ALL
        val flags = if (backwards) GeckoSession.FINDER_FIND_BACKWARDS else GeckoSession.FINDER_FIND_FORWARD
        session.finder.find(query, flags).accept({ result ->
            result?.let { onResult(if (it.found) it.current else 0, if (it.found) it.total else 0) }
        }, { })
    }

    fun clearFind() = session.finder.clear()

    fun close() {
        replies.values.forEach { it.complete(null) }
        replies.clear()
        session.close()
    }

    // --- Commands to the page's content script ----------------------------------------------------

    /**
     * Asks the content script in the page for something: "reader" (the simplified view's article),
     * "save" (the page as one HTML file, in [reply's][JSONObject] "text"), "simpleMode". Null if
     * the page has no content script (an error page, a saved page) or does not answer.
     */
    suspend fun command(cmd: String, extra: JSONObject = JSONObject()): JSONObject? {
        val port = contentPort ?: return null
        val id = nextCommand++
        val reply = CompletableDeferred<JSONObject?>()
        replies[id] = reply
        port.postMessage(extra.put("id", id).put("cmd", cmd))
        return withTimeoutOrNull(COMMAND_TIMEOUT_MILLIS) { reply.await() }.also { replies.remove(id) }
    }

    private val pageMessages = object : WebExtension.MessageDelegate {
        override fun onConnect(port: WebExtension.Port) {
            // One port per top-level document; the newest is the page showing.
            contentPort = port
            port.setDelegate(object : WebExtension.PortDelegate {
                override fun onPortMessage(message: Any, port: WebExtension.Port) {
                    val m = message as? JSONObject ?: return
                    when (m.optString("type")) {
                        "tab" -> callbacks.onEngineTab(m.optInt("tabId", -1), tabId)
                        "chunk" -> longReplies.getOrPut(m.optInt("id")) { StringBuilder() }.append(m.optString("text"))
                        "reply" -> {
                            val id = m.optInt("id")
                            val long = longReplies.remove(id)
                            val result = if (long != null) JSONObject().put("text", long.toString()) else m.optJSONObject("result")
                            replies.remove(id)?.complete(result ?: if (m.isNull("result")) null else JSONObject().put("value", m.opt("result")))
                        }
                    }
                }

                override fun onDisconnect(port: WebExtension.Port) {
                    if (contentPort === port) contentPort = null
                }
            })
        }

        override fun onMessage(nativeApp: String, message: Any, sender: WebExtension.MessageSender): GeckoResult<Any>? {
            val m = message as? JSONObject ?: return null
            when (m.optString("type")) {
                "blocked" -> callbacks.onNavigationBlocked(tabId, BlockReason.BLOCKED_WORDS)
                "pageBlocked" -> callbacks.onPageBlocked(tabId)
                "metrics" -> engine.logMetrics(m)
            }
            return null
        }
    }

    // --- Navigation -------------------------------------------------------------------------------

    private val navigation = object : GeckoSession.NavigationDelegate {
        override fun onLocationChange(session: GeckoSession, url: String?, perms: MutableList<PermissionDelegate.ContentPermission>, hasUserGesture: Boolean) {
            this@TabSession.url = url
            url?.let { callbacks.onLocationChange(tabId, it) }
        }

        override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
            this@TabSession.canGoBack = canGoBack
            callbacks.onHistoryState(tabId, canGoBack, canGoForward)
        }

        override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
            this@TabSession.canGoForward = canGoForward
            callbacks.onHistoryState(tabId, canGoBack, canGoForward)
        }

        override fun onLoadRequest(session: GeckoSession, request: LoadRequest): GeckoResult<AllowOrDeny> =
            if (allowTopLevel(request)) GeckoResult.allow() else GeckoResult.deny()

        override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? =
            callbacks.onNewWindow(tabId, uri)?.let { GeckoResult.fromValue(it) }

        override fun onLoadError(session: GeckoSession, uri: String?, error: WebRequestError): GeckoResult<String>? {
            // A request Hisar cancelled ends here too: the load simply stops, with no page about it.
            if (error.category == WebRequestError.ERROR_CATEGORY_UNKNOWN) return null
            return GeckoResult.fromValue(ErrorPage.uri(activity, error))
        }
    }

    /**
     * Whether a top-level navigation may go ahead. Blocked ones never leave the phone, and no page
     * about them lands in the tab's history: the tab stays where it was and the app says why.
     */
    private fun allowTopLevel(request: LoadRequest): Boolean {
        val uri = request.uri.toUri()
        return when (uri.scheme?.lowercase()) {
            "http", "https" -> when (val decision = filters.guardOrNull?.checkNavigation(request.uri)) {
                is Decision.Block -> {
                    Log.i(TAG, "Cancelled navigation (${decision.reason}): ${uri.host}")
                    callbacks.onNavigationBlocked(tabId, decision.reason)
                    false
                }
                // A page's own search link or form without strict SafeSearch is rewritten before it
                // is sent; the extension does the same for loads that don't come through here.
                is Decision.Redirect -> {
                    session.loadUri(decision.url)
                    false
                }
                // Before the lists are loaded the extension holds every request back.
                else -> true
            }
            "about" -> request.uri == "about:blank" || request.uri == "about:srcdoc"
            // The simplified view, which the app itself loads.
            "data" -> request.isDirectNavigation
            // Saved pages, from Hisar's own provider only.
            "content" -> OfflinePageRepository.isOfflineUrl(activity, request.uri)
            // Handing off to the dialler or mail app is harmless, but only on a real tap.
            "tel", "mailto", "sms" -> {
                if (request.hasUserGesture) {
                    try {
                        activity.startActivity(Intent(Intent.ACTION_VIEW, uri))
                    } catch (_: ActivityNotFoundException) {
                    }
                }
                false
            }
            // intent:, market:, file:, blob:, custom app schemes: any could open an unfiltered browser.
            else -> {
                Log.i(TAG, "Refused scheme: ${uri.scheme}")
                false
            }
        }
    }

    private val progress = object : GeckoSession.ProgressDelegate {
        override fun onPageStart(session: GeckoSession, url: String) = callbacks.onPageStarted(tabId, url)
        override fun onPageStop(session: GeckoSession, success: Boolean) = callbacks.onPageFinished(tabId)
        override fun onProgressChange(session: GeckoSession, progress: Int) = callbacks.onProgress(tabId, progress)
    }

    private val content = object : GeckoSession.ContentDelegate {
        override fun onTitleChange(session: GeckoSession, title: String?) = callbacks.onTitle(tabId, title.orEmpty())

        override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) = callbacks.onFullscreen(tabId, fullScreen)

        override fun onContextMenu(session: GeckoSession, screenX: Int, screenY: Int, element: ContextElement) {
            val image = element.srcUri?.takeIf { element.type == ContextElement.TYPE_IMAGE }
            if (element.linkUri != null || image != null) callbacks.onLongPress(LinkTarget(element.linkUri, image))
        }

        override fun onExternalResponse(session: GeckoSession, response: WebResponse) = callbacks.onDownload(tabId, response)

        override fun onCloseRequest(session: GeckoSession) = callbacks.onCloseWindow(tabId)

        override fun onCrash(session: GeckoSession) = callbacks.onCrash(tabId)

        override fun onKill(session: GeckoSession) = callbacks.onCrash(tabId)
    }

    private val historyDelegate = object : GeckoSession.HistoryDelegate {
        override fun onHistoryStateChange(session: GeckoSession, historyList: GeckoSession.HistoryDelegate.HistoryList) {
            history = historyList.map { it.uri }
            historyIndex = historyList.currentIndex
        }
    }

    // --- Dialogs and permissions ------------------------------------------------------------------

    private val prompts = object : PromptDelegate {
        private fun ask(prompt: PromptDelegate.BasePrompt): GeckoResult<PromptResponse> {
            val result = GeckoResult<PromptResponse>()
            callbacks.onPrompt(tabId, PagePrompt(prompt, result))
            return result
        }

        override fun onAlertPrompt(session: GeckoSession, prompt: PromptDelegate.AlertPrompt) = ask(prompt)
        override fun onButtonPrompt(session: GeckoSession, prompt: PromptDelegate.ButtonPrompt) = ask(prompt)
        override fun onTextPrompt(session: GeckoSession, prompt: PromptDelegate.TextPrompt) = ask(prompt)
        override fun onAuthPrompt(session: GeckoSession, prompt: PromptDelegate.AuthPrompt) = ask(prompt)
        override fun onChoicePrompt(session: GeckoSession, prompt: PromptDelegate.ChoicePrompt) = ask(prompt)
        override fun onColorPrompt(session: GeckoSession, prompt: PromptDelegate.ColorPrompt) = ask(prompt)
        override fun onDateTimePrompt(session: GeckoSession, prompt: PromptDelegate.DateTimePrompt) = ask(prompt)
        override fun onFilePrompt(session: GeckoSession, prompt: PromptDelegate.FilePrompt) = ask(prompt)
        override fun onBeforeUnloadPrompt(session: GeckoSession, prompt: PromptDelegate.BeforeUnloadPrompt) = ask(prompt)
        override fun onRepostConfirmPrompt(session: GeckoSession, prompt: PromptDelegate.RepostConfirmPrompt) = ask(prompt)

        // Pop-ups the page opens without a tap stay closed.
        override fun onPopupPrompt(session: GeckoSession, prompt: PromptDelegate.PopupPrompt): GeckoResult<PromptResponse> =
            GeckoResult.fromValue(prompt.confirm(AllowOrDeny.DENY))
    }

    /**
     * Location, camera, microphone, notifications, DRM: pages get none of them. Muted autoplay
     * (silent previews) is allowed; sound waits for a tap.
     */
    private val permissions = object : PermissionDelegate {
        override fun onContentPermissionRequest(session: GeckoSession, perm: PermissionDelegate.ContentPermission): GeckoResult<Int> =
            GeckoResult.fromValue(
                if (perm.permission == PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE) PermissionDelegate.ContentPermission.VALUE_ALLOW
                else PermissionDelegate.ContentPermission.VALUE_DENY,
            )

        override fun onMediaPermissionRequest(
            session: GeckoSession,
            uri: String,
            video: Array<out PermissionDelegate.MediaSource>?,
            audio: Array<out PermissionDelegate.MediaSource>?,
            callback: PermissionDelegate.MediaCallback,
        ) = callback.reject()

        override fun onAndroidPermissionsRequest(session: GeckoSession, permissions: Array<out String>?, callback: PermissionDelegate.Callback) =
            callback.reject()
    }

    // After the delegates above: the session gets them before it opens.
    init {
        session.navigationDelegate = navigation
        session.progressDelegate = progress
        session.contentDelegate = content
        session.historyDelegate = historyDelegate
        session.scrollDelegate = object : GeckoSession.ScrollDelegate {
            override fun onScrollChanged(session: GeckoSession, scrollX: Int, scrollY: Int) {
                val old = this@TabSession.scrollY
                this@TabSession.scrollY = scrollY
                callbacks.onScroll(tabId, scrollY, old)
            }
        }
        session.promptDelegate = prompts
        session.permissionDelegate = permissions
        session.selectionActionDelegate = BasicSelectionActionDelegate(activity)
        if (open) session.open(engine.runtime)
        engine.attach(session, pageMessages)
    }

    private companion object {
        const val TAG = "Hisar.Tab"
        const val COMMAND_TIMEOUT_MILLIS = 30_000L
    }
}
