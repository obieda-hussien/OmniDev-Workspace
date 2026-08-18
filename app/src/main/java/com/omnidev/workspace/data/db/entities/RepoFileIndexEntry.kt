package com.omnidev.workspace.data.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * RepoFileIndexEntry — [Localized] [Localized] [Localized] [Localized] Live Repository Context Engine.
 *
 * [Localized] [Localized] [Localized] [Localized] (incremental indexing): [Localized] [Localized] [Localized]
 * [Localized] [Localized] [Localized] mtime [Localized] [Localized] — [Localized] [Localized] [Localized] [Localized] 5000 [Localized] [Localized]
 * [Localized] Android [Localized].
 *
 * @property scopePath [Localized] [Localized] [Localized] ([Localized] [Localized] [Localized] [Localized])
 * @property contentHash SHA-256 [Localized] (16 hex) [Localized] [Localized] [Localized] [Localized]-[Localized]
 * @property skipReason [Localized] [Localized] [Localized] (binary/large/excluded) → [Localized] [Localized]
 */
@Entity(
    tableName = "repo_file_index",
    indices = [
        Index(value = ["scopePath", "filePath"], unique = true),
        Index(value = ["scopePath"]),
        Index(value = ["language"]),
        Index(value = ["indexedAt"])
    ]
)
data class RepoFileIndexEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val scopePath: String,
    val filePath: String,
    val fileSize: Long,
    val fileMtime: Long,
    val contentHash: String = "",
    val symbolCount: Int = 0,
    val language: String = "",
    val indexedAt: Long = System.currentTimeMillis(),
    val skipReason: String = ""
)
