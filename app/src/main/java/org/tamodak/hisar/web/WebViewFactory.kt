package org.tamodak.hisar.web

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.View
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.ServiceWorkerClientCompat
import androidx.webkit.ServiceWorkerControllerCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import org.tamodak.hisar.data.repository.FilterRepository
import org.tamodak.hisar.filter.Decision

/** Builds the locked-down WebView behind each tab. */
class WebViewFactory(
    private val context: Context,
    private val filters: FilterRepository,
    private val callbacks: WebViewCallbacks,
    private val scope: CoroutineScope,
    private val scanDispatcher: CoroutineDispatcher,
) {
    init {
        installServiceWorkerFilter()
    }

    @SuppressLint("SetJavaScriptEnabled") // The modern web does not work without it.
    fun create(tabId: Long, desktopMode: Boolean, textZoom: Int): WebView = WebView(context).apply {
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // No local files. Content access is switched on only to open a saved page
            // (BrowserController.loadInto) and off again on the next navigation.
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            // New windows become tabs (HisarChromeClient.onCreateWindow), and only on a tap.
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
            mediaPlaybackRequiresUserGesture = true
            setGeolocationEnabled(false)
            // Lay pages out like a real browser rather than WebView's 1990s default.
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
            this.textZoom = textZoom
        }
        // Google Safe Browsing (enabled for every WebView in the manifest) covers malware and
        // phishing pages the lists do not know yet.
        applyUserAgent(this, desktopMode)
        webViewClient = HisarWebViewClient(tabId, filters, callbacks, scope, scanDispatcher)
        webChromeClient = HisarChromeClient(tabId, callbacks)
        setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            callbacks.onDownload(tabId, url, userAgent, contentDisposition, mimeType)
        }
        setOnScrollChangeListener { _, _, y, _, oldY -> callbacks.onScroll(tabId, y, oldY) }
        setOnLongClickListener { onLongPress(this) }
    }

    private fun onLongPress(view: WebView): Boolean {
        val result = view.hitTestResult
        when (result.type) {
            WebView.HitTestResult.SRC_ANCHOR_TYPE -> callbacks.onLongPress(LinkTarget(result.extra, null))
            WebView.HitTestResult.IMAGE_TYPE -> callbacks.onLongPress(LinkTarget(null, result.extra))
            WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                // For a linked image, extra is the image; the link has to be asked for separately.
                val image = result.extra
                val reply = Handler(Looper.getMainLooper()) { msg ->
                    callbacks.onLongPress(LinkTarget(msg.data.getString("url"), image))
                    true
                }.obtainMessage()
                view.requestFocusNodeHref(reply)
            }
            else -> return false
        }
        return true
    }

    /**
     * Service workers fetch outside any page, so their requests never reach the WebViewClient. A
     * cached worker could otherwise serve a blocked site's content from its own fetches.
     */
    private fun installServiceWorkerFilter() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BASIC_USAGE)) return
        ServiceWorkerControllerCompat.getInstance().setServiceWorkerClient(object : ServiceWorkerClientCompat() {
            override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? =
                when (filters.guardBlocking().checkSubresource(request.url.toString())) {
                    Decision.Allow -> null
                    else -> BlockedPage.emptyResponse()
                }
        })
    }

    companion object {
        fun applyUserAgent(view: WebView, desktopMode: Boolean) {
            val default = WebSettings.getDefaultUserAgent(view.context)
            view.settings.userAgentString = if (desktopMode) desktopUserAgent(default) else mobileUserAgent(default)
        }

        /**
         * WebView's own user agent with its WebView markers ("; wv" and "Version/4.0") removed, so
         * it reads as the Chrome it is built from. Google treats the WebView agent as an embedded
         * browser and answers its searches with "unusual traffic" CAPTCHAs; WebView-based browsers
         * send the Chrome form for this reason.
         */
        fun mobileUserAgent(webView: String): String =
            webView.replace("; wv)", ")").replace(Regex("Version/[\\d.]+ "), "")

        /** Chrome's desktop user agent, keeping the real engine version. */
        fun desktopUserAgent(mobile: String): String {
            val chrome = Regex("Chrome/[\\d.]+").find(mobile)?.value ?: "Chrome/130.0.0.0"
            return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) $chrome Safari/537.36"
        }
    }
}

/** Progress, titles, icons, new windows, full-screen video and file uploads for one tab. */
internal class HisarChromeClient(private val tabId: Long, private val callbacks: WebViewCallbacks) : WebChromeClient() {

    override fun onProgressChanged(view: WebView, newProgress: Int) = callbacks.onProgress(tabId, newProgress)

    override fun onReceivedTitle(view: WebView, title: String?) = callbacks.onTitle(tabId, title.orEmpty())

    override fun onReceivedIcon(view: WebView, icon: Bitmap) = callbacks.onIcon(tabId, view.url, icon)

    override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
        if (!isUserGesture) return false // Popup blocker.
        val window = callbacks.onCreateWindow(tabId) ?: return false
        (resultMsg.obj as WebView.WebViewTransport).webView = window
        resultMsg.sendToTarget()
        return true
    }

    override fun onCloseWindow(window: WebView) = callbacks.onCloseWindow(window)

    override fun onShowCustomView(view: View, callback: CustomViewCallback) = callbacks.onShowFullscreen(view, callback)

    override fun onHideCustomView() = callbacks.onHideFullscreen()

    override fun onShowFileChooser(
        webView: WebView,
        filePathCallback: ValueCallback<Array<Uri>>,
        fileChooserParams: FileChooserParams,
    ): Boolean = callbacks.onShowFileChooser(filePathCallback, fileChooserParams)
}
