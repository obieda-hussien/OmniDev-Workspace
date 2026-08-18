package com.omnidev.workspace.data.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * RepoFileIndexEntry — Context note Context note Context note Context note Live Repository Context Engine.
 *
 * Context note Context note Context note Context note (incremental indexing): Context note Context note Context note
 * Context note Context note Context note mtime Context note Context note — Context note Context note Context note Context note 5000 Context note Context note
 * Context note Android Context note.
 *
 * @property scopePath Context note Context note Context note (Context note Context note Context note Context note)
 * @property contentHash SHA-256 Context note (16 hex) Context note Context note Context note Context note-Context note
 * @property skipReason Context note Context note Context note (binary/large/excluded) → Context note Context note
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
