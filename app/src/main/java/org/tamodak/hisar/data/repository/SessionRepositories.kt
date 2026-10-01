package org.tamodak.hisar.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.webkit.CookieManager
import androidx.core.graphics.scale
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.tamodak.hisar.data.local.TabDao
import org.tamodak.hisar.data.local.TabEntity
import org.tamodak.hisar.data.local.TabGroupEntity
import org.tamodak.hisar.data.model.SavedSession
import org.tamodak.hisar.data.model.SavedTab
import org.tamodak.hisar.data.model.TabGroup
import org.tamodak.hisar.di.ApplicationScope
import org.tamodak.hisar.di.IoDispatcher
import org.tamodak.hisar.filter.Guard
import org.tamodak.hisar.search.Omnibox
import org.tamodak.hisar.search.SearchEngine
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The open tabs and groups, restored on the next start. Saves are coalesced: tabs change on every
 * page load, and only the latest state needs to reach disk. They run in the application scope, so
 * a save started as the app goes away still finishes.
 */
@OptIn(FlowPreview::class)
@Singleton
class TabRepository @Inject constructor(
    private val dao: TabDao,
    @ApplicationScope scope: CoroutineScope,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    private val pending = MutableStateFlow<SavedSession?>(null)

    init {
        scope.launch(io) {
            pending.filterNotNull().debounce(SAVE_DELAY_MILLIS).collect(::write)
        }
    }

    suspend fun load(): SavedSession = withContext(io) {
        val groups = dao.groups().map { TabGroup(it.id, it.name, it.colorIndex) }
        val tabs = dao.tabs()
        SavedSession(
            tabs = tabs.map { SavedTab(it.id, it.url, it.title, it.groupId?.takeIf { id -> groups.any { g -> g.id == id } }) },
            groups = groups,
            selectedId = tabs.firstOrNull { it.selected }?.id,
        )
    }

    fun save(session: SavedSession) {
        pending.value = session
    }

    private suspend fun write(session: SavedSession) {
        dao.replaceSession(
            tabs = session.tabs.mapIndexed { i, t -> TabEntity(t.id, i, t.url, t.title, t.groupId, t.id == session.selectedId) },
            groups = session.groups.map { TabGroupEntity(it.id, it.name, it.colorIndex) },
        )
    }

    private companion object {
        const val SAVE_DELAY_MILLIS = 500L
    }
}

/** Search suggestions from the chosen engine's OpenSearch endpoint. */
@Singleton
class SuggestionRepository @Inject constructor(@IoDispatcher private val io: CoroutineDispatcher) {

    /**
     * Suggestions for [input], with any containing blocked words dropped, so typing never surfaces
     * what Hisar would refuse to load. Empty on any network error.
     */
    suspend fun suggestions(engine: SearchEngine, guard: Guard, input: String): List<String> = withContext(io) {
        val text = input.trim()
        if (text.isEmpty() || Omnibox.looksLikeUrl(text)) return@withContext emptyList()
        try {
            val connection = URL(engine.suggestUrl(text)).openConnection() as HttpURLConnection
            connection.connectTimeout = TIMEOUT_MILLIS
            connection.readTimeout = TIMEOUT_MILLIS
            val body = connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            val array = JSONArray(body).getJSONArray(1)
            (0 until array.length())
                .map { array.getString(it) }
                .filter { !it.equals(text, ignoreCase = true) && guard.isClean(it) }
                .take(MAX)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private companion object {
        const val TIMEOUT_MILLIS = 2_000
        const val MAX = 5
    }
}

/**
 * Search by image through Google Lens's web upload, the same endpoint Chrome's "Search with
 * Google Lens" falls back to. Lens answers with a redirect to an ordinary Google results page,
 * which then loads through the filters with SafeSearch enforced like any search.
 */
@Singleton
class ImageSearchRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    /** The results URL for a picture, or null if it could not be read or uploaded. */
    suspend fun search(image: Bitmap, userAgent: String): String? = withContext(io) { upload(encode(image), userAgent) }

    suspend fun search(image: Uri, userAgent: String): String? = withContext(io) {
        context.contentResolver.openInputStream(image)?.use(BitmapFactory::decodeStream)?.let { upload(encode(it), userAgent) }
    }

    /** Fetches an image from the page (long-press "Search image") and searches with it. */
    suspend fun searchImageUrl(imageUrl: String, userAgent: String): String? = withContext(io) {
        val connection = URL(imageUrl).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        connection.setRequestProperty("User-Agent", userAgent)
        CookieManager.getInstance().getCookie(imageUrl)?.let { connection.setRequestProperty("Cookie", it) }
        val bytes = connection.inputStream.use { it.readBytes() }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { upload(encode(it), userAgent) }
    }

    private fun encode(image: Bitmap): ByteArray {
        val scale = MAX_SIDE.toFloat() / maxOf(image.width, image.height)
        val sized = if (scale < 1f) image.scale((image.width * scale).toInt(), (image.height * scale).toInt()) else image
        return ByteArrayOutputStream().use {
            sized.compress(Bitmap.CompressFormat.JPEG, 85, it)
            it.toByteArray()
        }
    }

    private fun upload(jpeg: ByteArray, userAgent: String): String? {
        val boundary = "HisarBoundary${System.nanoTime()}"
        val language = Locale.getDefault().language
        val connection = URL("https://lens.google.com/v3/upload?hl=$language&re=df&st=${System.currentTimeMillis()}&ep=gsbubb")
            .openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = 10_000
        connection.readTimeout = 20_000
        connection.setRequestProperty("User-Agent", userAgent)
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        // Lens keys the results to the Google session: upload with the WebView's Google cookies,
        // and hand any cookie the upload sets back to the WebView that will open the results.
        val cookies = CookieManager.getInstance()
        cookies.getCookie("https://www.google.com")?.let { connection.setRequestProperty("Cookie", it) }
        connection.outputStream.use { out ->
            out.write(
                ("--$boundary\r\n" +
                    "Content-Disposition: form-data; name=\"encoded_image\"; filename=\"image.jpg\"\r\n" +
                    "Content-Type: image/jpeg\r\n\r\n").toByteArray(),
            )
            out.write(jpeg)
            out.write("\r\n--$boundary--\r\n".toByteArray())
        }
        val code = connection.responseCode
        val location = connection.getHeaderField("Location")
        connection.headerFields["Set-Cookie"].orEmpty().forEach { cookie ->
            cookies.setCookie("https://www.google.com", cookie)
            cookies.setCookie("https://lens.google.com", cookie)
        }
        cookies.flush()
        connection.disconnect()
        return location?.takeIf { code in 300..399 && it.startsWith("https://") }
    }

    private companion object {
        const val MAX_SIDE = 1000
    }
}
