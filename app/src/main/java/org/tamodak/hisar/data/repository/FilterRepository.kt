package org.tamodak.hisar.data.repository

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.tamodak.hisar.di.ApplicationScope
import org.tamodak.hisar.di.IoDispatcher
import org.tamodak.hisar.filter.DomainList
import org.tamodak.hisar.filter.Guard
import org.tamodak.hisar.filter.WordFilter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The filters, built from the lists embedded in the APK. Loading takes a moment, so it starts with
 * the app ([preload]) and every caller waits for it rather than proceeding unfiltered.
 */
@Singleton
class FilterRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope scope: CoroutineScope,
    @IoDispatcher io: CoroutineDispatcher,
) {
    @Volatile private var loaded: Guard? = null

    private val loading: Deferred<Guard> = scope.async(io, start = CoroutineStart.LAZY) {
        val start = System.currentTimeMillis()
        val assets = context.assets
        val ads = assets.open("extensions/hisar/lists/ads.txt").use(DomainList::load)
        val harmful = assets.open("extensions/hisar/lists/harmful.txt").use(DomainList::load)
        fun lines(name: String) = assets.open("extensions/hisar/lists/$name").use(WordFilter::readLines)
        val substring = lines("substring_words.txt")
        val stems = lines("suffix_stems.txt")
        val words = WordFilter(lines("words.txt"), substring, stems)
        val searchOnlyWords = WordFilter(lines("search_words.txt"), substring, stems)
        Guard(ads, harmful, words, searchOnlyWords).also {
            loaded = it
            Log.i(TAG, "Lists loaded in ${System.currentTimeMillis() - start} ms: ${ads.size} ad/tracker, ${harmful.size} harmful domains")
        }
    }

    fun preload() {
        loading.start()
    }

    suspend fun guard(): Guard = loaded ?: loading.await()

    /**
     * For WebView's network threads, which are already off the main thread and must wait rather
     * than let a request through unfiltered. Never call this on the main thread.
     */
    fun guardBlocking(): Guard = loaded ?: runBlocking { loading.await() }

    /** The filters if already loaded, for code on the main thread that must not wait. */
    val guardOrNull: Guard? get() = loaded

    private companion object {
        const val TAG = "Hisar.Filters"
    }
}
