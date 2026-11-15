package com.ziribrowser.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface BookmarkDao {
    @Query("SELECT COUNT(*) FROM bookmarks")
    suspend fun count(): Int

    @Query("SELECT * FROM bookmarks WHERE title LIKE '%' || :q || '%' ESCAPE '\\' OR url LIKE '%' || :q || '%' ESCAPE '\\' ORDER BY created_at DESC")
    fun search(q: String): Flow<List<BookmarkEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM bookmarks WHERE url = :url)")
    fun isBookmarked(url: String): Flow<Boolean>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(b: BookmarkEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(items: List<BookmarkEntity>)

    @Query("SELECT * FROM bookmarks ORDER BY created_at DESC")
    suspend fun all(): List<BookmarkEntity>

    @Query("SELECT * FROM bookmarks WHERE title LIKE '%' || :q || '%' ESCAPE '\\' OR url LIKE '%' || :q || '%' ESCAPE '\\' ORDER BY created_at DESC LIMIT 4")
    suspend fun suggest(q: String): List<BookmarkEntity>

    @Query("UPDATE bookmarks SET title = :title, folder = :folder WHERE id = :id")
    suspend fun update(id: Long, title: String, folder: String)

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM bookmarks WHERE url = :url")
    suspend fun deleteByUrl(url: String)
}

@Dao
interface HistoryDao {
    @Query("SELECT COUNT(*) FROM history")
    suspend fun count(): Int

    @Query("SELECT * FROM history WHERE title LIKE '%' || :q || '%' ESCAPE '\\' OR url LIKE '%' || :q || '%' ESCAPE '\\' ORDER BY visited_at DESC LIMIT 500")
    fun search(q: String): Flow<List<HistoryEntity>>

    /** Newest visits first; used to work out the most visited sites. */
    @Query("SELECT * FROM history ORDER BY visited_at DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<HistoryEntity>>

    @Insert
    suspend fun insert(h: HistoryEntity)

    @Query("SELECT * FROM history WHERE title LIKE '%' || :q || '%' ESCAPE '\\' OR url LIKE '%' || :q || '%' ESCAPE '\\' GROUP BY url ORDER BY MAX(visited_at) DESC LIMIT 5")
    suspend fun suggest(q: String): List<HistoryEntity>

    @Query("DELETE FROM history WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM history WHERE visited_at >= :since")
    suspend fun clearSince(since: Long)

    @Query("DELETE FROM history")
    suspend fun clearAll()
}

@Dao
interface TabDao {
    @Query("SELECT COUNT(*) FROM tabs")
    suspend fun count(): Int

    @Query("SELECT * FROM tabs ORDER BY position")
    suspend fun all(): List<TabEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(tabs: List<TabEntity>)

    @Query("DELETE FROM tabs")
    suspend fun clear()
}

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY created_at DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun get(id: Long): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE id = :id")
    fun observe(id: Long): Flow<DownloadEntity?>

    /** Finished pictures, newest first: the image viewer lets you swipe through them. */
    @Query("SELECT * FROM downloads WHERE category = 'IMAGES' AND status = 'COMPLETED' ORDER BY created_at DESC")
    fun observeImages(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE status IN ('QUEUED','DOWNLOADING')")
    suspend fun active(): List<DownloadEntity>

    @Query("SELECT * FROM downloads WHERE status IN ('PAUSED','FAILED')")
    suspend fun pausedOrFailed(): List<DownloadEntity>

    @Query("UPDATE downloads SET cookie = '' WHERE cookie != ''")
    suspend fun clearCookies()

    @Query("DELETE FROM downloads WHERE is_private = 1")
    suspend fun deletePrivate()

    @Query("SELECT * FROM downloads WHERE file_uri = :uri LIMIT 1")
    suspend fun findByUri(uri: String): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE url = :url AND status != 'FAILED' LIMIT 1")
    suspend fun findByUrl(url: String): DownloadEntity?

    @Insert
    suspend fun insert(d: DownloadEntity): Long

    @Update
    suspend fun update(d: DownloadEntity)

    @Delete
    suspend fun delete(d: DownloadEntity)
}

@Dao
interface CredentialDao {
    @Query("SELECT * FROM credentials ORDER BY origin, username")
    fun all(): Flow<List<CredentialEntity>>

    @Query("SELECT * FROM credentials ORDER BY origin, username")
    suspend fun allNow(): List<CredentialEntity>

    @Query("SELECT * FROM credentials WHERE origin = :origin COLLATE NOCASE ORDER BY last_used DESC, username")
    suspend fun forOrigin(origin: String): List<CredentialEntity>

    @Query("SELECT * FROM credentials WHERE id = :id")
    suspend fun get(id: Long): CredentialEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(c: CredentialEntity): Long

    @Query("UPDATE credentials SET username = :username, secret = :secret, updated_at = :now WHERE id = :id")
    suspend fun update(id: Long, username: String, secret: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE credentials SET last_used = :now WHERE id = :id")
    suspend fun touch(id: Long, now: Long = System.currentTimeMillis())

    @Query("DELETE FROM credentials WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM credentials")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM credentials")
    fun count(): Flow<Int>

    @Query("SELECT EXISTS(SELECT 1 FROM never_save WHERE origin = :origin COLLATE NOCASE)")
    suspend fun isNeverSaved(origin: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun neverSave(n: NeverSaveEntity)

    @Query("SELECT origin FROM never_save ORDER BY origin")
    fun neverSaved(): Flow<List<String>>

    @Query("DELETE FROM never_save WHERE origin = :origin")
    suspend fun allowSaving(origin: String)
}
