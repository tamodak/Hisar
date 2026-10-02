package org.tamodak.hisar.web

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.util.Log
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONTokener
import org.tamodak.hisar.data.repository.FilterRepository
import org.tamodak.hisar.data.repository.OfflinePageRepository
import org.tamodak.hisar.filter.BlockReason
import org.tamodak.hisar.filter.Decision
import java.util.Collections

/**
 * Where Hisar's filters meet a tab's WebView.
 *
 * - [shouldInterceptRequest] sees every network request, top-level or not, and is the enforcement
 *   point: blocked domains, blocked words in URLs and SafeSearch are all applied there, so no
 *   route into the page (typed URL, link, redirect, back button, script) gets around them.
 * - [shouldOverrideUrlLoading] keeps navigation inside Hisar: schemes that would hand the page to
 *   another app, and with it another browser, are refused.
 * - [onPageFinished] and [doUpdateVisitedHistory] scan the rendered text for blocked words.
 */
// MissingOnRenderProcessGone: a false positive. onRenderProcessGone is implemented below, returns
// true and replaces the WebView; verified by killing the renderer process on a device.
@SuppressLint("MissingOnRenderProcessGone")
internal class HisarWebViewClient(
    private val tabId: Long,
    private val filters: FilterRepository,
    private val callbacks: WebViewCallbacks,
    /** The UI's scope: page scans stop with the screen. */
    private val scope: CoroutineScope,
    private val scanDispatcher: CoroutineDispatcher,
) : WebViewClient() {

    /**
     * Recent top-level URLs shouldInterceptRequest has ruled on; they need no second check. More
     * than one is kept because a SafeSearch hop commits the original URL after the strict one has
     * already been requested.
     */
    private val intercepted = Collections.synchronizedSet(LinkedHashSet<String>())

    /**
     * Pages this tab blocked for their content. Back and Reload must not show them again, so they
     * are answered with the block page — in this tab only, and until the user opens the address
     * again on purpose ([forget]), since a site can show something else next time.
     */
    private val contentBlocked = Collections.synchronizedSet(HashSet<String>())

    fun isContentBlocked(url: String) = url.substringBefore('#') in contentBlocked

    fun forget(url: String) {
        contentBlocked -= url.substringBefore('#')
    }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        // Saved pages come from Hisar's own FileProvider; any other content: URL is refused.
        if (request.url.scheme == "content") {
            return if (OfflinePageRepository.isOfflineUrl(view.context, request.url.toString())) null else BlockedPage.emptyResponse()
        }

        val url = request.url.toString()
        val mainFrame = request.isForMainFrame
        if (mainFrame && isContentBlocked(url)) return BlockedPage.response(view.context, BlockReason.PAGE_CONTENT, "")
        if (mainFrame) remember(url)
        val guard = filters.guardBlocking()
        val decision = if (mainFrame) guard.checkNavigation(url) else guard.checkSubresource(url)
        return when (decision) {
            Decision.Allow -> {
                if (mainFrame) YouTubeRestrictedMode.apply(request.url)
                null
            }
            is Decision.Redirect -> if (mainFrame) BlockedPage.redirect(decision.url) else BlockedPage.emptyResponse()
            is Decision.Block -> {
                if (mainFrame) {
                    Log.i(TAG, "Blocked page (${decision.reason}): ${request.url.host}")
                    BlockedPage.response(view.context, decision.reason, decision.detail)
                } else {
                    if (decision.reason == BlockReason.AD_OR_TRACKER) callbacks.onAdBlocked(tabId)
                    BlockedPage.emptyResponse()
                }
            }
        }
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val uri = request.url
        return when (uri.scheme?.lowercase()) {
            // A page's own search link or form without strict SafeSearch is rewritten before it is
            // sent. Letting it go out and redirecting afterwards costs a duplicate request, which
            // Google answers with an "unusual traffic" CAPTCHA.
            //
            // A link, form or redirect to anything Hisar blocks is cancelled here, so the request
            // never leaves the phone and no block page lands in the tab's history.
            "http", "https" -> {
                if (!request.isForMainFrame) return false
                when (val decision = filters.guardOrNull?.checkNavigation(uri.toString())) {
                    is Decision.Block -> {
                        Log.i(TAG, "Cancelled navigation (${decision.reason}): ${uri.host}")
                        callbacks.onNavigationBlocked(tabId, decision.reason)
                        true
                    }
                    is Decision.Redirect -> {
                        if (request.method == "GET") view.loadUrl(decision.url)
                        request.method == "GET"
                    }
                    else -> false
                }
            }
            // The block page's own "Go back" button.
            "hisar" -> {
                if (uri.host == "back") callbacks.onBlockedPageBack(tabId)
                true
            }
            "about" -> false
            // Handing off to the dialler or mail app is harmless, but only on a real tap.
            "tel", "mailto", "sms" -> {
                if (request.hasGesture()) {
                    try {
                        view.context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                    } catch (_: ActivityNotFoundException) {
                    }
                }
                true
            }
            // intent:, market:, file:, custom app schemes: any could open an unfiltered browser.
            else -> {
                Log.i(TAG, "Refused scheme: ${uri.scheme}")
                true
            }
        }
    }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        // Content access was opened for a saved page only; anything after it runs without.
        if (!url.startsWith("content://")) view.settings.allowContentAccess = false
        callbacks.onPageStarted(tabId, url, favicon)
    }

    override fun onPageCommitVisible(view: WebView, url: String) {
        callbacks.onPageVisible(tabId, view)
    }

    override fun onPageFinished(view: WebView, url: String) {
        callbacks.onPageFinished(tabId, view, url)
        scanPage(view, url)
    }

    override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
        callbacks.onHistoryUpdated(tabId, view, url, isReload)
        // Single-page apps change the URL with pushState, which never reaches
        // shouldInterceptRequest. Re-check the new URL, and rescan once the new content is drawn.
        // A real load was already checked there; re-checking it would reload a blocked page
        // forever.
        val alreadyChecked = url in intercepted
        scope.launch {
            val decision = if (alreadyChecked) Decision.Allow else withContext(scanDispatcher) { filters.guard().checkNavigation(url) }
            when (decision) {
                Decision.Allow -> {
                    delay(SPA_RESCAN_DELAY_MILLIS)
                    scanPage(view, url)
                }
                is Decision.Redirect -> if (view.url == url) view.loadUrl(decision.url)
                // Reloading lets shouldInterceptRequest serve the blocked page in its place.
                is Decision.Block -> if (view.url == url) view.loadUrl(url)
            }
        }
    }

    /**
     * The renderer crashed or was killed to free memory. The WebView can't be used again; per the
     * WebView guide it is destroyed and replaced, rather than letting the app crash with it.
     */
    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        Log.w(TAG, "Renderer gone (crashed=${detail.didCrash()}) for tab $tabId")
        callbacks.onRendererGone(tabId, view)
        return true
    }

    private fun scanPage(view: WebView, url: String) {
        if (!url.startsWith("http")) return
        view.evaluateJavascript(PAGE_TEXT_JS) { json ->
            val text = runCatching { JSONTokener(json).nextValue() as? String }.getOrNull() ?: return@evaluateJavascript
            scope.launch {
                val blocked = withContext(scanDispatcher) { filters.guard().checkPageText(url, text) is Decision.Block }
                if (blocked && view.url == url) {
                    contentBlocked += url.substringBefore('#')
                    Log.i(TAG, "Blocked by page content: ${url.toUri().host}")
                    // Recorded above, so this load is answered with the block page, and so is any
                    // return to it in this tab.
                    view.loadUrl(url)
                }
            }
        }
    }

    private fun remember(url: String) = synchronized(intercepted) {
        intercepted += url
        if (intercepted.size > 16) intercepted.remove(intercepted.first())
    }

    private companion object {
        const val TAG = "Hisar.Web"
        const val SPA_RESCAN_DELAY_MILLIS = 1_500L

        /** Title and visible text, capped so a giant page cannot stall the scan. */
        const val PAGE_TEXT_JS =
            "(function(){var b=document.body;" +
                "return ((document.title||'')+'\\n'+(b?b.innerText:'')).slice(0,300000);})()"
    }
}
