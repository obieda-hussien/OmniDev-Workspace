package com.omnidev.workspace.data.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * RepoFileIndexEntry — System awareness note System awareness note System awareness note System awareness note Live Repository Context Engine.
 *
 * System awareness note System awareness note System awareness note System awareness note (incremental indexing): System awareness note System awareness note System awareness note
 * System awareness note System awareness note System awareness note mtime System awareness note System awareness note — System awareness note System awareness note System awareness note System awareness note 5000 System awareness note System awareness note
 * System awareness note Android System awareness note.
 *
 * @property scopePath System awareness note System awareness note System awareness note (System awareness note System awareness note System awareness note System awareness note)
 * @property contentHash SHA-256 System awareness note (16 hex) System awareness note System awareness note System awareness note System awareness note-System awareness note
 * @property skipReason System awareness note System awareness note System awareness note (binary/large/excluded) → System awareness note System awareness note
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
