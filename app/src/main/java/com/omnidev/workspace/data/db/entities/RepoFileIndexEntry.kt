package com.omnidev.workspace.data.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** RepoFileIndexEntry tracks files for incremental indexing, reprocessing when mtime or size changes. scopePath identifies a project; contentHash is a truncated 16-hex SHA-256; skipReason records binary, large or excluded files. */
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
