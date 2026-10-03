package org.tamodak.hisar.web

import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.PromptDelegate.BasePrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.PromptResponse
import org.mozilla.geckoview.WebResponse
import org.tamodak.hisar.filter.BlockReason

/** A long-pressed link or image. */
data class LinkTarget(val link: String?, val image: String?)

/**
 * A dialog a page opened — alert, confirm, a <select>'s options, a date, a file — waiting for
 * the user. Answered once, with [respond] or [dismiss].
 */
class PagePrompt(val prompt: BasePrompt, private val result: GeckoResult<PromptResponse>) {
    fun respond(response: PromptResponse) = result.complete(response)
    fun dismiss() = result.complete(prompt.dismiss())
}

/**
 * What a tab's page reports to the UI layer. Implemented by the browser's UI state holder, which
 * turns page events into state changes and handles the ones that need the window (full screen,
 * dialogs, new windows). Called on the main thread.
 */
interface TabCallbacks {
    fun onPageStarted(tabId: Long, url: String)
    fun onPageFinished(tabId: Long)
    /** The address changed: a load committed, or a single-page app moved (pushState). */
    fun onLocationChange(tabId: Long, url: String)
    fun onHistoryState(tabId: Long, canGoBack: Boolean, canGoForward: Boolean)
    fun onProgress(tabId: Long, progress: Int)
    fun onTitle(tabId: Long, title: String)
    fun onScroll(tabId: Long, y: Int, oldY: Int)
    fun onLongPress(target: LinkTarget)
    /** A link turned out to be a file. */
    fun onDownload(tabId: Long, response: WebResponse)
    /** A page asked for a new window; returns the session to put it in, or null to refuse. */
    fun onNewWindow(openerTabId: Long, uri: String): GeckoSession?
    fun onCloseWindow(tabId: Long)
    fun onFullscreen(tabId: Long, fullscreen: Boolean)
    fun onPrompt(tabId: Long, prompt: PagePrompt)

    /**
     * Hisar stopped something before it left the phone: a page's link, form or search with a
     * blocked word or to a blocked site, or something the page tried to send.
     */
    fun onNavigationBlocked(tabId: Long, reason: BlockReason)

    /** The page is about blocked content (its own title or tags say so): leave it. */
    fun onPageBlocked(tabId: Long)

    /** The tab's page told us its id in the engine, so background reports can find the tab. */
    fun onEngineTab(engineTabId: Int, tabId: Long)

    /** The tab's content process crashed or was killed; the session must be replaced. */
    fun onCrash(tabId: Long)
}
