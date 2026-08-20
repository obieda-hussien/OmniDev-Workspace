package com.omnidev.workspace.data.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * RepoSymbolEntry — System awareness note (class/function/variable...) System awareness note System awareness note System awareness note System awareness note.
 *
 * Mobile-first:
 * - System awareness note System awareness note System awareness note regex (System awareness note Tree-sitter System awareness note Compiler) — System awareness note 2 GB RAM
 * - snippet ≤ 240 System awareness note (System awareness note System awareness note System awareness note)
 * - 5000 System awareness note System awareness note System awareness note System awareness note scope System awareness note LRU eviction
 *
 * @property symbolKind class | object | interface | function | variable | property | enum
 * @property qualifiedName System awareness note System awareness note System awareness note System awareness note (e.g. "com.example.Foo.bar")
 */
@Entity(
    tableName = "repo_symbols",
    indices = [
        Index(value = ["scopePath"]),
        Index(value = ["symbolName"]),
        Index(value = ["symbolKind"]),
        Index(value = ["filePath"]),
        Index(value = ["qualifiedName"])
    ]
)
data class RepoSymbolEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val scopePath: String,
    val symbolKind: String,
    val symbolName: String,
    val qualifiedName: String = "",
    val filePath: String,
    val lineNumber: Int = 0,
    val snippet: String = "",
    val language: String = "",
    val visibility: String = "",
    val fileMtime: Long = 0,
    val indexedAt: Long = System.currentTimeMillis()
)
