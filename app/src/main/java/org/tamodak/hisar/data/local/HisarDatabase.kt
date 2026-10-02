package org.tamodak.hisar.data.local

import androidx.room.AutoMigration
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Database(
    entities = [
        HistoryEntity::class,
        BookmarkEntity::class,
        ClosedTabEntity::class,
        OfflinePageEntity::class,
        DownloadEntity::class,
        TabEntity::class,
        TabGroupEntity::class,
        ShortcutEntity::class,
    ],
    version = 2,
    // Version 2 added the new tab page's pinned shortcuts; Room writes the migration from the
    // exported schemas.
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
abstract class HisarDatabase : RoomDatabase() {
    abstract fun historyDao(): HistoryDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun closedTabDao(): ClosedTabDao
    abstract fun offlinePageDao(): OfflinePageDao
    abstract fun downloadDao(): DownloadDao
    abstract fun tabDao(): TabDao
    abstract fun shortcutDao(): ShortcutDao
}

// --- History ------------------------------------------------------------------------------------

@Entity(tableName = "history", indices = [Index("url"), Index("visited_at")])
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val title: String,
    @ColumnInfo(name = "visited_at") val visitedAt: Long,
)

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history ORDER BY visited_at DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<HistoryEntity>>

    @Query(
        "SELECT * FROM history WHERE title LIKE '%' || :query || '%' OR url LIKE '%' || :query || '%' " +
            "ORDER BY visited_at DESC LIMIT :limit",
    )
    fun observeSearch(query: String, limit: Int): Flow<List<HistoryEntity>>

    @Query(
        "SELECT * FROM history WHERE title LIKE '%' || :query || '%' OR url LIKE '%' || :query || '%' " +
            "ORDER BY visited_at DESC LIMIT :limit",
    )
    suspend fun search(query: String, limit: Int): List<HistoryEntity>

    @Query("SELECT * FROM history ORDER BY visited_at DESC LIMIT 1")
    suspend fun newest(): HistoryEntity?

    @Insert
    suspend fun insert(entry: HistoryEntity)

    @Query("UPDATE history SET visited_at = :time, title = CASE WHEN :title = '' THEN title ELSE :title END WHERE id = :id")
    suspend fun touch(id: Long, title: String, time: Long)

    /** Titles arrive after the visit is recorded; patch the newest entry for [url]. */
    @Query(
        "UPDATE history SET title = :title WHERE id = " +
            "(SELECT id FROM history WHERE url = :url ORDER BY visited_at DESC LIMIT 1)",
    )
    suspend fun updateTitle(url: String, title: String)

    @Query("DELETE FROM history WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM history")
    suspend fun clear()

    @Query("DELETE FROM history WHERE id NOT IN (SELECT id FROM history ORDER BY visited_at DESC LIMIT :keep)")
    suspend fun trim(keep: Int)
}

// --- Bookmarks ----------------------------------------------------------------------------------

@Entity(tableName = "bookmarks")
data class BookmarkEntity(
    @PrimaryKey val url: String,
    val title: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks ORDER BY created_at DESC")
    fun observeAll(): Flow<List<BookmarkEntity>>

    @Query(
        "SELECT * FROM bookmarks WHERE title LIKE '%' || :query || '%' OR url LIKE '%' || :query || '%' " +
            "ORDER BY created_at DESC LIMIT :limit",
    )
    suspend fun search(query: String, limit: Int): List<BookmarkEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM bookmarks WHERE url = :url)")
    suspend fun contains(url: String): Boolean

    @Upsert
    suspend fun upsert(bookmark: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE url = :url")
    suspend fun delete(url: String)
}

// --- Recently closed tabs -----------------------------------------------------------------------

@Entity(tableName = "closed_tabs")
data class ClosedTabEntity(
    @PrimaryKey val url: String,
    val title: String,
    @ColumnInfo(name = "closed_at") val closedAt: Long,
)

@Dao
interface ClosedTabDao {
    @Query("SELECT * FROM closed_tabs ORDER BY closed_at DESC")
    fun observeAll(): Flow<List<ClosedTabEntity>>

    @Upsert
    suspend fun upsert(tab: ClosedTabEntity)

    @Query("DELETE FROM closed_tabs WHERE url = :url")
    suspend fun delete(url: String)

    @Query("DELETE FROM closed_tabs")
    suspend fun clear()

    @Query("DELETE FROM closed_tabs WHERE url NOT IN (SELECT url FROM closed_tabs ORDER BY closed_at DESC LIMIT :keep)")
    suspend fun trim(keep: Int)
}

// --- Offline pages ------------------------------------------------------------------------------

@Entity(tableName = "offline_pages")
data class OfflinePageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val title: String,
    @ColumnInfo(name = "file_name") val fileName: String,
    val size: Long,
    @ColumnInfo(name = "saved_at") val savedAt: Long,
)

@Dao
interface OfflinePageDao {
    @Query("SELECT * FROM offline_pages ORDER BY saved_at DESC")
    fun observeAll(): Flow<List<OfflinePageEntity>>

    @Query("SELECT * FROM offline_pages WHERE url = :url")
    suspend fun byUrl(url: String): List<OfflinePageEntity>

    @Insert
    suspend fun insert(page: OfflinePageEntity)

    @Query("DELETE FROM offline_pages WHERE id = :id")
    suspend fun delete(id: Long)
}

// --- Downloads ----------------------------------------------------------------------------------

/** Downloads Hisar started; their progress lives in DownloadManager, keyed by [downloadId]. */
@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey @ColumnInfo(name = "download_id") val downloadId: Long,
    @ColumnInfo(name = "file_name") val fileName: String,
    val url: String,
    @ColumnInfo(name = "mime_type") val mimeType: String?,
    @ColumnInfo(name = "started_at") val startedAt: Long,
)

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY started_at DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE download_id = :id")
    suspend fun byId(id: Long): DownloadEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(download: DownloadEntity)

    @Query("DELETE FROM downloads WHERE download_id = :id")
    suspend fun delete(id: Long)
}

// --- Open tabs ----------------------------------------------------------------------------------

@Entity(tableName = "tabs")
data class TabEntity(
    @PrimaryKey val id: Long,
    val position: Int,
    val url: String,
    val title: String,
    @ColumnInfo(name = "group_id") val groupId: Long?,
    val selected: Boolean,
)

@Entity(tableName = "tab_groups")
data class TabGroupEntity(
    @PrimaryKey val id: Long,
    val name: String,
    @ColumnInfo(name = "color_index") val colorIndex: Int,
)

@Dao
interface TabDao {
    @Query("SELECT * FROM tabs ORDER BY position")
    suspend fun tabs(): List<TabEntity>

    @Query("SELECT * FROM tab_groups")
    suspend fun groups(): List<TabGroupEntity>

    @Query("DELETE FROM tabs")
    suspend fun clearTabs()

    @Query("DELETE FROM tab_groups")
    suspend fun clearGroups()

    @Insert
    suspend fun insertTabs(tabs: List<TabEntity>)

    @Insert
    suspend fun insertGroups(groups: List<TabGroupEntity>)

    /** The open tabs are saved as a whole, so a partial write never leaves a mix of two sessions. */
    @Transaction
    suspend fun replaceSession(tabs: List<TabEntity>, groups: List<TabGroupEntity>) {
        clearTabs()
        clearGroups()
        insertGroups(groups)
        insertTabs(tabs)
    }
}

// --- New tab page shortcuts ---------------------------------------------------------------------

/** A shortcut the user pinned to the new tab page by editing it. */
@Entity(tableName = "shortcuts")
data class ShortcutEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val url: String,
    val position: Int,
)

@Dao
interface ShortcutDao {
    @Query("SELECT * FROM shortcuts ORDER BY position")
    fun observeAll(): Flow<List<ShortcutEntity>>

    @Query("SELECT * FROM shortcuts WHERE url = :url LIMIT 1")
    suspend fun byUrl(url: String): ShortcutEntity?

    @Query("SELECT COALESCE(MAX(position), -1) FROM shortcuts")
    suspend fun lastPosition(): Int

    @Upsert
    suspend fun upsert(shortcut: ShortcutEntity)

    @Query("DELETE FROM shortcuts WHERE url = :url")
    suspend fun delete(url: String)
}
