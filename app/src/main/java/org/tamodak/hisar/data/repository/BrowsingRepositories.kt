package org.tamodak.hisar.data.repository

import androidx.core.net.toUri
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.tamodak.hisar.data.local.BookmarkDao
import org.tamodak.hisar.data.local.BookmarkEntity
import org.tamodak.hisar.data.local.ClosedTabDao
import org.tamodak.hisar.data.local.ClosedTabEntity
import org.tamodak.hisar.data.local.HistoryDao
import org.tamodak.hisar.data.local.HistoryEntity
import org.tamodak.hisar.data.model.Bookmark
import org.tamodak.hisar.data.model.ClosedTab
import org.tamodak.hisar.data.model.HistoryEntry
import org.tamodak.hisar.search.SearchEngine
import javax.inject.Inject
import javax.inject.Singleton

/** Only real web pages are remembered: not the new tab page or saved pages. */
internal fun isRememberable(url: String) = url.startsWith("http://") || url.startsWith("https://")

@Singleton
class HistoryRepository @Inject constructor(private val dao: HistoryDao) {

    fun observe(query: String): Flow<List<HistoryEntry>> =
        (if (query.isBlank()) dao.observeRecent(MAX_ENTRIES) else dao.observeSearch(query.trim(), SEARCH_LIMIT))
            .map { list -> list.map { it.toModel() } }

    suspend fun search(query: String, limit: Int): List<HistoryEntry> = dao.search(query.trim(), limit).map { it.toModel() }

    /**
     * Sites visited most often, as Chrome's tiles show them: one per site (its most recent page),
     * leaving out search result pages, which are visits to a query rather than to a site.
     */
    fun mostVisited(limit: Int): Flow<List<HistoryEntry>> = dao.observeRecent(TILE_SAMPLE).map { recent ->
        recent.asSequence()
            .filter { SearchEngine.queryOf(it.url) == null && !it.url.contains("/search?") }
            .groupBy { it.url.toUri().host?.removePrefix("www.") ?: it.url }
            .values
            .sortedByDescending { it.size }
            .take(limit)
            .map { it.first().toModel() }
            .toList()
    }

    suspend fun record(url: String, title: String) {
        if (!isRememberable(url)) return
        val now = System.currentTimeMillis()
        val newest = dao.newest()
        if (newest?.url == url) {
            dao.touch(newest.id, title, now)
        } else {
            dao.insert(HistoryEntity(url = url, title = title, visitedAt = now))
            dao.trim(MAX_ENTRIES)
        }
    }

    suspend fun updateTitle(url: String, title: String) {
        if (title.isNotBlank() && isRememberable(url)) dao.updateTitle(url, title)
    }

    suspend fun delete(entry: HistoryEntry) = dao.delete(entry.id)
    suspend fun clear() = dao.clear()

    private fun HistoryEntity.toModel() = HistoryEntry(id, url, title, visitedAt)

    private companion object {
        const val MAX_ENTRIES = 5_000
        const val SEARCH_LIMIT = 500
        const val TILE_SAMPLE = 2_000
    }
}

@Singleton
class BookmarkRepository @Inject constructor(private val dao: BookmarkDao) {

    val bookmarks: Flow<List<Bookmark>> = dao.observeAll().map { list -> list.map { Bookmark(it.url, it.title, it.createdAt) } }

    suspend fun search(query: String, limit: Int): List<Bookmark> =
        dao.search(query.trim(), limit).map { Bookmark(it.url, it.title, it.createdAt) }

    /** Adds [url], or removes it if already saved. Returns whether it is now bookmarked. */
    suspend fun toggle(url: String, title: String): Boolean {
        if (dao.contains(url)) {
            dao.delete(url)
            return false
        }
        dao.upsert(BookmarkEntity(url, title.ifBlank { url }, System.currentTimeMillis()))
        return true
    }

    suspend fun delete(bookmark: Bookmark) = dao.delete(bookmark.url)
}

/** Chrome's "Recent tabs": tabs closed earlier, newest first. */
@Singleton
class RecentTabsRepository @Inject constructor(private val dao: ClosedTabDao) {

    val closedTabs: Flow<List<ClosedTab>> = dao.observeAll().map { list -> list.map { ClosedTab(it.url, it.title, it.closedAt) } }

    suspend fun add(url: String, title: String) {
        if (!isRememberable(url)) return
        dao.upsert(ClosedTabEntity(url, title, System.currentTimeMillis()))
        dao.trim(MAX)
    }

    suspend fun remove(url: String) = dao.delete(url)
    suspend fun clear() = dao.clear()

    private companion object {
        const val MAX = 25
    }
}
