package com.ziribrowser.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "bookmarks", indices = [Index(value = ["url"], unique = true)])
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val url: String,
    val folder: String = "",
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "history", indices = [Index(value = ["visited_at"])])
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val url: String,
    @ColumnInfo(name = "visited_at") val visitedAt: Long = System.currentTimeMillis(),
)

/** Open tabs that survive a restart. Private tabs are never stored. */
@Entity(tableName = "tabs")
data class TabEntity(
    @PrimaryKey val id: String,
    val url: String,
    val title: String,
    val position: Int,
    val desktop: Boolean = false,
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis(),
)

enum class DownloadStatus { QUEUED, DOWNLOADING, PAUSED, COMPLETED, FAILED }

@Entity(tableName = "downloads", indices = [Index(value = ["url"])])
data class DownloadEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val filename: String,
    val url: String,
    @ColumnInfo(name = "mime_type") val mimeType: String,
    val category: String,
    @ColumnInfo(name = "file_size") val fileSize: Long = -1,
    @ColumnInfo(name = "downloaded_size") val downloadedSize: Long = 0,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    /** content:// Uri of the file in MediaStore. It stays "pending" (hidden from other apps) until complete. */
    @ColumnInfo(name = "file_uri") val fileUri: String = "",
    val cookie: String = "",
    @ColumnInfo(name = "user_agent") val userAgent: String = "",
    val referer: String = "",
    val etag: String = "",
    @ColumnInfo(name = "can_resume") val canResume: Boolean = false,
    val error: String = "",
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "completed_at") val completedAt: Long = 0,
    /** Started from a private tab: the row is removed the next time the app starts. */
    @ColumnInfo(name = "is_private") val isPrivate: Boolean = false,
    /** Progress of each part of a multi-connection download, as "start:end:done;…" (empty for one-connection downloads). */
    val parts: String = "",
    /** Queue position: smaller runs first. 0 means "by order of adding". */
    @ColumnInfo(name = "queue_order") val queueOrder: Long = 0,
)

/**
 * A saved login. [secret] is the password sealed with a key that lives in the Android Keystore, so the database file
 * alone never reveals a password.
 */
@Entity(tableName = "credentials", indices = [Index(value = ["origin", "username"], unique = true)])
data class CredentialEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val origin: String,
    val username: String,
    val secret: String,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "last_used") val lastUsed: Long = 0,
)

/** Sites where the person chose "Never save" for passwords. */
@Entity(tableName = "never_save")
data class NeverSaveEntity(@PrimaryKey val origin: String)
