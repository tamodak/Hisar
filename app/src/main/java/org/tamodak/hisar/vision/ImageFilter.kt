package org.tamodak.hisar.vision

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.tamodak.hisar.BuildConfig
import org.tamodak.hisar.di.ApplicationScope
import org.tamodak.hisar.di.DefaultDispatcher
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.measureTimedValue

/**
 * Judges the pictures pages load, for the browser extension (image-filter.js), which holds each
 * one back until it has an answer.
 *
 * - [judge]: the fast classifier ([BodyClassifier]), tens of milliseconds a picture. It
 *   looks for a woman's or girl's bare body beyond her face and hands, and nudity, not merely for
 *   a woman: a bare leg or foot alone counts.
 * - [outline]: for a covered picture that is not a video's thumbnail, the slow outline mask
 *   ([OutlineMasker], one to a few seconds), which covers the people and shows the rest.
 *
 * Judging comes first: an outline mask only starts while no picture waits to be judged. The fast
 * classifier loads when the app starts ([preload]); the outline models (about 600 MB) on first use,
 * and they are let go when the system runs short of memory.
 */
@Singleton
class ImageFilter @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope private val scope: CoroutineScope,
    @DefaultDispatcher private val dispatcher: CoroutineDispatcher,
) : ComponentCallbacks2 {

    private val classifier = scope.async(dispatcher, CoroutineStart.LAZY) { BodyClassifier(context) }
    private val classifying = Mutex()
    private val waiting = AtomicInteger()

    private val masking = Mutex()
    private var masker: OutlineMasker? = null

    /** Loads the fast classifier in the background, so it is ready by the first picture. */
    fun preload() {
        context.registerComponentCallbacks(this)
        classifier.start()
    }

    /** A picture's verdict: [cover] it; and whether the classifier is [sure], not only cautious. */
    data class Verdict(val cover: Boolean, val sure: Boolean)

    /**
     * Judges a picture (JPEG or another format Android decodes). A picture that can't be read is
     * covered: nothing unjudged is shown.
     */
    suspend fun judge(image: ByteArray): Verdict = withContext(dispatcher) {
        waiting.incrementAndGet()
        try {
            val bitmap = decode(image, mutable = false) ?: return@withContext Verdict(cover = true, sure = false)
            val (score, time) = measureTimedValue { classifying.withLock { classifier.await().score(bitmap) } }
            if (BuildConfig.DEBUG) Log.d(TAG, "classified ${bitmap.width}x${bitmap.height} in $time: $score")
            Verdict(cover = score >= BodyClassifier.COVER, sure = score >= BodyClassifier.SURE)
        } finally {
            waiting.decrementAndGet()
        }
    }

    /** [image] with the people in it covered, as JPEG, or null when it can't be done. */
    suspend fun outline(image: ByteArray): ByteArray? = withContext(dispatcher) {
        masking.withLock {
            // Pictures waiting to be judged hold up a page; outline masks only improve one.
            while (waiting.get() > 0) delay(YIELD_MILLIS)
            val bitmap = decode(image, mutable = true) ?: return@withLock null
            val filter = masker ?: OutlineMasker.create(context).also { masker = it }
            val result = filter.filter(bitmap)
            if (BuildConfig.DEBUG) Log.d(TAG, "outlined ${bitmap.width}x${bitmap.height} in ${result.totalTime}: ${result.reasons}")
            ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                out.toByteArray()
            }
        }
    }

    private fun decode(bytes: ByteArray, mutable: Boolean): Bitmap? =
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = mutable
        })

    // --- Memory ------------------------------------------------------------------------------------

    override fun onTrimMemory(level: Int) {
        // In the background, or when the system asks, the outline models go first: they are
        // loaded again on the next covered picture.
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) releaseMasker()
    }

    override fun onConfigurationChanged(newConfig: Configuration) = Unit

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onLowMemory() = releaseMasker()

    private fun releaseMasker() {
        scope.launch(dispatcher) {
            masking.withLock {
                masker?.close()
                masker = null
            }
        }
    }

    private companion object {
        const val TAG = "Hisar.Images"
        const val YIELD_MILLIS = 50L
        const val JPEG_QUALITY = 88
    }
}
