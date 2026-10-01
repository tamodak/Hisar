package org.tamodak.hisar.web

import android.graphics.Bitmap
import android.net.Uri
import android.view.View
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView

/** A long-pressed link or image. */
data class LinkTarget(val link: String?, val image: String?)

/**
 * What a tab's WebView reports to the UI layer. Implemented by the browser's UI state holder,
 * which turns page events into state changes and handles the ones that need the window
 * (full screen, file picker, new windows).
 *
 * Called on the main thread, except [onAdBlocked], which comes from WebView's network threads.
 */
interface WebViewCallbacks {
    fun onPageStarted(tabId: Long, url: String, favicon: Bitmap?)
    fun onPageVisible(tabId: Long, view: WebView)
    fun onPageFinished(tabId: Long, view: WebView, url: String)
    fun onHistoryUpdated(tabId: Long, view: WebView, url: String, isReload: Boolean)
    fun onProgress(tabId: Long, progress: Int)
    fun onTitle(tabId: Long, title: String)
    fun onIcon(tabId: Long, pageUrl: String?, icon: Bitmap)
    fun onAdBlocked(tabId: Long)
    fun onScroll(tabId: Long, y: Int, oldY: Int)
    fun onLongPress(target: LinkTarget)
    fun onDownload(tabId: Long, url: String, userAgent: String?, contentDisposition: String?, mimeType: String?)

    /** A page asked for a new window; returns the WebView to put it in, or null to refuse. */
    fun onCreateWindow(openerTabId: Long): WebView?
    fun onCloseWindow(view: WebView)
    fun onShowFullscreen(view: View, callback: WebChromeClient.CustomViewCallback)
    fun onHideFullscreen()
    fun onShowFileChooser(callback: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams): Boolean

    /** The tab's renderer died. The WebView is unusable and must be replaced. */
    fun onRendererGone(tabId: Long, view: WebView)
}
