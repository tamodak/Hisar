package org.tamodak.hisar.data.repository

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Environment
import android.util.LruCache
import android.webkit.CookieManager
import androidx.core.content.FileProvider
import androidx.core.graphics.scale
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.tamodak.hisar.data.local.DownloadDao
import org.tamodak.hisar.data.local.DownloadEntity
import org.tamodak.hisar.data.local.OfflinePageDao
import org.tamodak.hisar.data.local.OfflinePageEntity
import org.tamodak.hisar.data.model.DownloadItem
import org.tamodak.hisar.data.model.DownloadStatus
import org.tamodak.hisar.data.model.OfflinePage
import org.tamodak.hisar.di.ApplicationScope
import org.tamodak.hisar.di.IoDispatcher
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pages saved for reading without a connection, as MHTML archives in app storage.
 *
 * Chromium renders MHTML only from file: or content: URLs, so WebViewAssetLoader (https) can't
 * serve them. They are opened through a non-exported FileProvider covering just this folder;
 * WebView never gets file access, and content access only in the tab opening a saved page.
 */
@Singleton
class OfflinePageRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: OfflinePageDao,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    val directory: File = File(context.filesDir, "offline")

    val pages: Flow<List<OfflinePage>> = dao.observeAll().map { list ->
        list.map { OfflinePage(it.id, it.url, it.title, it.fileName, it.size, it.savedAt) }
    }

    /** Where WebView should write the next archive. */
    suspend fun newFile(): File = withContext(io) {
        directory.mkdirs()
        File(directory, "${System.currentTimeMillis()}.mht")
    }

    suspend fun add(url: String, title: String, archive: File) = withContext(io) {
        // Saving a page again replaces the earlier copy.
        dao.byUrl(url).forEach { old ->
            File(directory, old.fileName).delete()
            dao.delete(old.id)
        }
        dao.insert(OfflinePageEntity(url = url, title = title.ifBlank { url }, fileName = archive.name, size = archive.length(), savedAt = System.currentTimeMillis()))
    }

    suspend fun delete(page: OfflinePage) = withContext(io) {
        File(directory, page.fileName).delete()
        dao.delete(page.id)
    }

    fun contentUrl(page: OfflinePage): String =
        FileProvider.getUriForFile(context, authority(context), File(directory, page.fileName)).toString()

    companion object {
        fun authority(context: Context) = "${context.packageName}.offline"

        /** True for a URL of one of Hisar's saved pages. */
        fun isOfflineUrl(context: Context, url: String) = url.startsWith("content://${authority(context)}/")
    }
}

/**
 * Downloads through the system DownloadManager, which keeps them going in the background, shows
 * the notification and saves to the shared Downloads folder. Hisar records what it started so the
 * Downloads screen shows only those.
 */
@Singleton
class DownloadRepository @Inject constructor(
    private val manager: DownloadManager,
    private val dao: DownloadDao,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    val downloads: Flow<List<DownloadItem>> = dao.observeAll().map { list ->
        list.map { DownloadItem(it.downloadId, it.fileName, it.url, it.mimeType, it.startedAt) }
    }

    suspend fun enqueue(url: String, fileName: String, mimeType: String?, userAgent: String?, referer: String?): DownloadItem =
        withContext(io) {
            val request = DownloadManager.Request(url.toUri()).apply {
                setTitle(fileName)
                mimeType?.let(::setMimeType)
                // DownloadManager has its own HTTP stack; pass the page's session along so
                // downloads that need a login work.
                CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
                userAgent?.let { addRequestHeader("User-Agent", it) }
                referer?.takeIf { it.startsWith("http") }?.let { addRequestHeader("Referer", it) }
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            }
            val item = DownloadItem(manager.enqueue(request), fileName, url, mimeType, System.currentTimeMillis())
            dao.insert(DownloadEntity(item.id, item.fileName, item.url, item.mimeType, item.startedAt))
            item
        }

    suspend fun find(id: Long): DownloadItem? = withContext(io) {
        dao.byId(id)?.let { DownloadItem(it.downloadId, it.fileName, it.url, it.mimeType, it.startedAt) }
    }

    suspend fun status(id: Long): DownloadStatus = withContext(io) { query(id) }

    /** DownloadManager offers no change callback for progress, so callers poll while they show it. */
    fun observeStatuses(ids: List<Long>): Flow<Map<Long, DownloadStatus>> = flow {
        while (true) {
            emit(ids.associateWith(::query))
            delay(POLL_MILLIS)
        }
    }.flowOn(io)

    /** An intent that opens a finished download in whatever app handles its type. */
    suspend fun openIntent(id: Long): Intent? = withContext(io) {
        val uri = manager.getUriForDownloadedFile(id) ?: return@withContext null
        val mime = manager.getMimeTypeForDownloadedFile(id)
        Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /** Cancels it if running and deletes the file. */
    suspend fun delete(id: Long) = withContext(io) {
        manager.remove(id)
        dao.delete(id)
    }

    private fun query(id: Long): DownloadStatus {
        manager.query(DownloadManager.Query().setFilterById(id)).use { c ->
            if (c == null || !c.moveToFirst()) return DownloadStatus(DownloadStatus.State.MISSING, 0, 0)
            val state = when (c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                DownloadManager.STATUS_SUCCESSFUL -> DownloadStatus.State.DONE
                DownloadManager.STATUS_FAILED -> DownloadStatus.State.FAILED
                DownloadManager.STATUS_PAUSED -> DownloadStatus.State.PAUSED
                DownloadManager.STATUS_RUNNING -> DownloadStatus.State.RUNNING
                else -> DownloadStatus.State.PENDING
            }
            return DownloadStatus(
                state,
                c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
            )
        }
    }

    private companion object {
        const val POLL_MILLIS = 500L
    }
}

/** Site icons by host: a memory cache in front of small PNGs in app storage. */
@Singleton
class FaviconRepository @Inject constructor(
    @ApplicationContext context: Context,
    @ApplicationScope private val scope: CoroutineScope,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    private val directory = File(context.filesDir, "favicons")
    private val memory = LruCache<String, Bitmap>(200)

    /** From memory only; for code that must not wait. */
    fun cached(host: String?): Bitmap? = host?.let(memory::get)

    suspend fun load(host: String?): Bitmap? {
        if (host.isNullOrEmpty()) return null
        memory.get(host)?.let { return it }
        return withContext(io) {
            val file = File(directory, fileName(host))
            if (file.exists()) BitmapFactory.decodeFile(file.path)?.also { memory.put(host, it) } else null
        }
    }

    /**
     * Downloads a site's icon: its touch icon (sharp at tile size) or else its favicon. Blocking
     * network I/O on the IO dispatcher; null if the site has neither.
     */
    suspend fun fetch(host: String): Bitmap? = withContext(io) {
        for (path in listOf("/apple-touch-icon.png", "/favicon.ico")) {
            val icon = runCatching {
                val connection = URL("https://$host$path").openConnection() as HttpURLConnection
                connection.connectTimeout = 5_000
                connection.readTimeout = 5_000
                if (connection.responseCode != HttpURLConnection.HTTP_OK) return@runCatching null
                connection.inputStream.use(BitmapFactory::decodeStream)
            }.getOrNull()
            if (icon != null) {
                save(host, icon)
                return@withContext icon
            }
        }
        null
    }

    fun save(host: String?, icon: Bitmap) {
        if (host.isNullOrEmpty()) return
        val scaled = if (icon.width > SIZE) icon.scale(SIZE, SIZE) else icon
        memory.put(host, scaled)
        scope.launch(io) {
            directory.mkdirs()
            runCatching { File(directory, fileName(host)).outputStream().use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        }
    }

    suspend fun clear() {
        memory.evictAll()
        withContext(io) { directory.listFiles()?.forEach { it.delete() } }
    }

    private fun fileName(host: String) = host.replace(Regex("[^a-z0-9.-]"), "_") + ".png"

    private companion object {
        const val SIZE = 64
    }
}
