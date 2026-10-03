package org.tamodak.hisar.web

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.annotation.MainThread
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.WebExtension
import org.tamodak.hisar.BuildConfig
import org.tamodak.hisar.data.repository.CookieStore
import org.tamodak.hisar.data.repository.FilterRepository
import org.tamodak.hisar.di.ApplicationScope
import org.tamodak.hisar.filter.Decision
import org.tamodak.hisar.vision.ImageFilter
import javax.inject.Inject
import javax.inject.Singleton

/** What the extension's background script reports, by the engine's own tab ids. */
interface EngineEvents {
    /** A request a page sent in the background (a search, a form) was refused for its words. */
    fun onRequestBlocked(engineTabId: Int)

    /** An ad, tracker or harmful-site request was refused. */
    fun onAdBlocked(engineTabId: Int)

    /** A page's own data showed it is about blocked content (a video tagged "trans"): leave it. */
    fun onPageBlocked(engineTabId: Int)

    /** Data a page fetched was about blocked content as a whole (a video's player data) and was refused. */
    fun onDataBlocked(engineTabId: Int)
}

/**
 * The browser engine: one Gecko runtime for the whole app, with Hisar's filter built in as a
 * WebExtension (assets/extensions/hisar). The extension sees every request and every page; this
 * is its link to the app.
 *
 * The background script asks here about each top-level navigation, so it follows exactly the
 * rules the address bar does ([org.tamodak.hisar.filter.Guard.checkNavigation]), and receives the
 * word lists from here once they are loaded. Until then it holds every request back.
 */
@Singleton
class GeckoEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val filters: FilterRepository,
    private val images: ImageFilter,
    @ApplicationScope private val scope: CoroutineScope,
) : CookieStore {

    /** Created on first use, on the main thread, as GeckoView requires. */
    val runtime: GeckoRuntime by lazy {
        val settings = GeckoRuntimeSettings.Builder()
            .consoleOutput(BuildConfig.DEBUG)
            .aboutConfigEnabled(false)
            .extensionsWebAPIEnabled(false)
            .automaticFontSizeAdjustment(false)
            // Google Safe Browsing: malware and phishing pages the lists don't know yet.
            .contentBlocking(ContentBlocking.Settings.Builder().safeBrowsing(ContentBlocking.SafeBrowsing.DEFAULT).build())
            .build()
        GeckoRuntime.create(context, settings).also { installExtension(it) }
    }

    private val extension = CompletableDeferred<WebExtension>()
    /** Completed when the extension has its lists and its page script in place. */
    private val filterReady = CompletableDeferred<Unit>()
    private var background: WebExtension.Port? = null
    private var simpleCss: String? = null
    /** Strategy switches being compared (debug builds only, tools/experiments); null normally. */
    private var experiment: JSONObject? = null
    private var nextId = 1
    private val pending = HashMap<Int, CompletableDeferred<JSONObject?>>()

    /** The activity's listener for background events; set while it exists. */
    var events: EngineEvents? = null

    private fun installExtension(runtime: GeckoRuntime) {
        runtime.webExtensionController.ensureBuiltIn(EXTENSION_LOCATION, EXTENSION_ID).accept({ ext ->
            ext ?: return@accept
            ext.setMessageDelegate(backgroundDelegate, NATIVE_APP)
            extension.complete(ext)
        }, { error ->
            // Without its filter the browser must not browse: every request waits on the lists.
            Log.e(TAG, "The filter extension failed to install", error)
        })
    }

    /**
     * Runs [load] once the filter is in place, at once after the first time. A page loaded before
     * that, right after a cold start, would be shown without the page and picture filters.
     */
    @MainThread
    fun whenFilterReady(load: () -> Unit) {
        if (filterReady.isCompleted) load()
        else scope.launch(Dispatchers.Main) {
            filterReady.await()
            load()
        }
    }

    /** Routes a tab's content-script messages (commands, reports) to [delegate]. */
    @MainThread
    fun attach(session: GeckoSession, delegate: WebExtension.MessageDelegate) {
        scope.launch(Dispatchers.Main) {
            session.webExtensionController.setMessageDelegate(extension.await(), delegate, NATIVE_APP)
        }
    }

    // --- The background script -------------------------------------------------------------------

    private val backgroundDelegate = object : WebExtension.MessageDelegate {
        override fun onConnect(port: WebExtension.Port) {
            port.setDelegate(object : WebExtension.PortDelegate {
                override fun onPortMessage(message: Any, port: WebExtension.Port) {
                    (message as? JSONObject)?.let { onBackgroundMessage(it, port) }
                }

                override fun onDisconnect(port: WebExtension.Port) {
                    if (background === port) background = null
                }
            })
        }

        override fun onMessage(nativeApp: String, message: Any, sender: WebExtension.MessageSender): GeckoResult<Any>? = null
    }

    private fun onBackgroundMessage(message: JSONObject, port: WebExtension.Port) {
        when (message.optString("type")) {
            "hello" -> {
                background = port
                port.postMessage(JSONObject().put("type", "welcome"))
                scope.launch(Dispatchers.Main) {
                    // As a string: the engine's message bridge can't carry arrays of arrays.
                    val lists = GuardLists.json(filters.guard()).toString()
                    experiment?.let { port.postMessage(JSONObject().put("type", "config").put("config", it.toString())) }
                    port.postMessage(JSONObject().put("type", "lists").put("lists", lists).put("simpleCss", simpleCss ?: JSONObject.NULL))
                }
            }
            "checkNavigation" -> scope.launch(Dispatchers.Main) {
                val verdict = when (val decision = filters.guard().checkNavigation(message.optString("url"))) {
                    Decision.Allow -> JSONObject().put("action", "allow")
                    is Decision.Redirect -> JSONObject().put("action", "redirect").put("url", decision.url)
                    is Decision.Block -> JSONObject().put("action", "block").put("reason", decision.reason.name)
                }
                port.postMessage(verdict.put("type", "reply").put("id", message.optInt("id")))
            }
            // Pictures, held by the extension until judged (image-filter.js).
            "classifyImage" -> scope.launch {
                val verdict = images.judge(Base64.decode(message.optString("image"), Base64.DEFAULT))
                reply(port, message, JSONObject().put("cover", verdict.cover).put("sure", verdict.sure))
            }
            "outlineImage" -> scope.launch {
                val masked = images.outline(Base64.decode(message.optString("image"), Base64.DEFAULT))
                reply(port, message, JSONObject().put("image", masked?.let { Base64.encodeToString(it, Base64.NO_WRAP) } ?: JSONObject.NULL))
            }
            "ready" -> filterReady.complete(Unit)
            "blocked" -> events?.onRequestBlocked(message.optInt("tabId", -1))
            "adBlocked" -> events?.onAdBlocked(message.optInt("tabId", -1))
            "pageBlocked" -> events?.onPageBlocked(message.optInt("tabId", -1))
            "dataBlocked" -> events?.onDataBlocked(message.optInt("tabId", -1))
            "reply" -> pending.remove(message.optInt("id"))?.complete(message)
            "metrics" -> logMetrics(message)
        }
    }

    /** Answers the background script's [question]; the port takes messages on the main thread. */
    private suspend fun reply(port: WebExtension.Port, question: JSONObject, answer: JSONObject) = withContext(Dispatchers.Main) {
        port.postMessage(answer.put("type", "reply").put("id", question.optInt("id")))
    }

    // --- Comparing strategies (debug builds only) -------------------------------------------------

    /**
     * Switches the filtering strategies for an experiment (tools/experiments): the extension's
     * options, and "dns" ("cleanbrowsing" for a family DNS resolver, else the system's).
     */
    @MainThread
    fun setExperiment(config: JSONObject) {
        if (!BuildConfig.DEBUG) return
        experiment = config
        background?.postMessage(JSONObject().put("type", "config").put("config", config.toString()))
        val settings = runtime.settings
        if (config.optString("dns") == "cleanbrowsing") {
            settings.setTrustedRecursiveResolverUri(CLEANBROWSING_ADULT_DOH)
            settings.setTrustedRecursiveResolverMode(GeckoRuntimeSettings.TRR_MODE_ONLY)
        } else {
            settings.setTrustedRecursiveResolverMode(GeckoRuntimeSettings.TRR_MODE_OFF)
        }
    }

    /** A measurement from the extension, for tools/experiments to collect from the log. */
    fun logMetrics(message: JSONObject) {
        if (BuildConfig.DEBUG) Log.i(METRICS_TAG, message.put("experiment", experiment?.optString("name")).toString())
    }

    /** Sends [message] to the background script and waits for its reply; null if there is none. */
    private suspend fun ask(message: JSONObject): JSONObject? = withContext(Dispatchers.Main) {
        val port = background ?: return@withContext null
        val id = nextId++
        val reply = CompletableDeferred<JSONObject?>()
        pending[id] = reply
        port.postMessage(message.put("id", id))
        withTimeoutOrNull(ASK_TIMEOUT_MILLIS) { reply.await() }.also { pending.remove(id) }
    }

    // --- Settings ---------------------------------------------------------------------------------

    /** Simple mode's stylesheet for every page, or null to show pages as they are. */
    @MainThread
    fun setSimpleModeCss(css: String?) {
        if (css == simpleCss) return
        simpleCss = css
        background?.postMessage(JSONObject().put("type", "simpleMode").put("css", css ?: JSONObject.NULL))
    }

    /** Page text size in percent. */
    @MainThread
    fun setTextZoom(percent: Int) {
        runtime.settings.setFontSizeFactor(percent / 100f)
    }

    // --- CookieStore ------------------------------------------------------------------------------

    override suspend fun cookieHeader(url: String): String? =
        ask(JSONObject().put("type", "getCookies").put("url", url))?.optString("cookie")?.takeIf { it.isNotEmpty() && it != "null" }

    override suspend fun store(url: String, setCookieHeaders: List<String>) {
        if (setCookieHeaders.isEmpty()) return
        ask(JSONObject().put("type", "setCookies").put("url", url).put("cookies", JSONArray(setCookieHeaders)))
    }

    companion object {
        private const val TAG = "Hisar.Engine"
        const val NATIVE_APP = "hisar"
        private const val EXTENSION_ID = "filter@hisar.tamodak.org"
        private const val EXTENSION_LOCATION = "resource://android/assets/extensions/hisar/"
        private const val ASK_TIMEOUT_MILLIS = 5_000L
        private const val METRICS_TAG = "Hisar.Metrics"
        private const val CLEANBROWSING_ADULT_DOH = "https://doh.cleanbrowsing.org/doh/adult-filter/"
    }
}
