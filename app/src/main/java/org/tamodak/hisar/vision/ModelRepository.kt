package org.tamodak.hisar.vision

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.tamodak.hisar.BuildConfig
import org.tamodak.hisar.R
import org.tamodak.hisar.di.ApplicationScope
import org.tamodak.hisar.di.IoDispatcher
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The picture filter's models (about 230 MB). They are not in the APK, which would be too big
 * for Play Store downloads: Hisar downloads them on first start, before the browser opens, from
 * the GitHub release named in [BuildConfig.MODELS_URL].
 *
 * Downloads go through Android's DownloadManager, which survives the app being closed, resumes
 * after lost connections and shows its progress in a notification. Each file is checked against
 * the size and SHA-256 in assets/vision-models.json before it is used, then kept in no-backup
 * app storage ([directory]), out of reach of other apps and of cloud backups.
 */
@Singleton
class ModelRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val downloads: DownloadManager,
    @ApplicationScope private val scope: CoroutineScope,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    sealed interface State {
        /** Looking at what is already on the phone. */
        data object Checking : State

        /** Downloading: [done] of [total] bytes. */
        data class Downloading(val done: Long, val total: Long) : State

        /** A download failed or a file was damaged; [retry] starts again. */
        data object Failed : State

        data object Ready : State
    }

    private class ModelFile(val name: String, val size: Long, val sha256: String)

    /** Where the checked models are kept. */
    val directory: File = File(context.noBackupFilesDir, "vision")

    private val _state = MutableStateFlow<State>(State.Checking)
    val state: StateFlow<State> = _state.asStateFlow()

    private var job: Job? = null

    private val manifest: Pair<Int, List<ModelFile>> by lazy {
        val json = JSONObject(context.assets.open(MANIFEST).bufferedReader().use { it.readText() })
        val files = json.getJSONArray("files")
        json.getInt("version") to List(files.length()) { i ->
            val f = files.getJSONObject(i)
            ModelFile(f.getString("name"), f.getLong("size"), f.getString("sha256"))
        }
    }

    /** The marker written once every file of this model version has been checked. */
    private val verified get() = File(directory, "verified-${manifest.first}")

    /** Checks what is on the phone and downloads what is missing. Safe to call again. */
    fun start() {
        if (job?.isActive == true || _state.value == State.Ready) return
        job = scope.launch(io) {
            _state.value = State.Checking
            _state.value = if (prepare()) State.Ready else State.Failed
        }
    }

    fun retry() {
        if (_state.value == State.Failed) start()
    }

    /** Suspends until the models are ready to load. */
    suspend fun awaitReady() {
        start()
        state.first { it == State.Ready }
    }

    private suspend fun prepare(): Boolean = withContext(io) {
        val (version, files) = manifest
        if (verified.exists() && files.all { File(directory, it.name).length() == it.size }) return@withContext true
        directory.mkdirs()
        // Files of other model versions are no longer used.
        directory.listFiles()?.forEach { file -> if (files.none { it.name == file.name }) file.delete() }
        val staging = context.getExternalFilesDir(STAGING) ?: return@withContext false
        val missing = files.filter { !File(directory, it.name).isGood(it) }
        val ids = missing.associateWith { enqueue(it, staging) }
        val total = files.sumOf { it.size }
        val already = files.filter { it !in missing }.sumOf { it.size }
        val pending = ids.toMutableMap()
        var finished = 0L
        while (isActive && pending.isNotEmpty()) {
            var inFlight = 0L
            for ((file, id) in pending.toMap()) {
                when (val status = query(id)) {
                    is Status.Running -> inFlight += status.bytes
                    is Status.Done -> {
                        pending.remove(file)
                        val downloaded = File(staging, file.name)
                        if (!downloaded.isGood(file) || !downloaded.moveTo(File(directory, file.name))) {
                            downloaded.delete()
                            cancelAll(pending.values)
                            return@withContext false
                        }
                        finished += file.size
                    }
                    Status.Failed -> {
                        cancelAll(pending.values + id)
                        return@withContext false
                    }
                }
            }
            _state.value = State.Downloading(already + finished + inFlight, total)
            if (pending.isNotEmpty()) delay(POLL_MILLIS)
        }
        verified.writeText(version.toString())
        true
    }

    private fun enqueue(file: ModelFile, staging: File): Long {
        val url = BuildConfig.MODELS_URL + file.name
        // A download of the same file left from an earlier start (the app was closed) is reused.
        existing(url)?.let { return it }
        File(staging, file.name).delete()
        val request = DownloadManager.Request(url.toUri())
            .setTitle(context.getString(R.string.models_notification))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationUri(Uri.fromFile(File(staging, file.name)))
        return downloads.enqueue(request)
    }

    private fun existing(url: String): Long? {
        val query = DownloadManager.Query().setFilterByStatus(
            DownloadManager.STATUS_PENDING or DownloadManager.STATUS_RUNNING or DownloadManager.STATUS_PAUSED,
        )
        downloads.query(query)?.use { c ->
            while (c.moveToNext()) {
                if (c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_URI)) == url) {
                    return c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID))
                }
            }
        }
        return null
    }

    private sealed interface Status {
        data class Running(val bytes: Long) : Status
        data object Done : Status
        data object Failed : Status
    }

    private fun query(id: Long): Status {
        downloads.query(DownloadManager.Query().setFilterById(id))?.use { c ->
            if (!c.moveToFirst()) return Status.Failed
            return when (c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                DownloadManager.STATUS_SUCCESSFUL -> Status.Done
                DownloadManager.STATUS_FAILED -> Status.Failed
                else -> Status.Running(c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)).coerceAtLeast(0))
            }
        }
        return Status.Failed
    }

    private fun cancelAll(ids: Collection<Long>) {
        if (ids.isNotEmpty()) downloads.remove(*ids.toLongArray())
    }

    private fun File.isGood(expected: ModelFile): Boolean =
        isFile && length() == expected.size && sha256() == expected.sha256

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Moves across file systems (shared storage to app storage): copy, then delete. */
    private fun File.moveTo(target: File): Boolean = runCatching {
        val partial = File(target.path + ".part")
        copyTo(partial, overwrite = true)
        delete()
        partial.renameTo(target)
    }.getOrDefault(false)

    private companion object {
        const val MANIFEST = "vision-models.json"
        const val STAGING = "vision-download"
        const val POLL_MILLIS = 500L
    }
}
