package com.omnidev.workspace.data.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * RepoSymbolEntry — Context note (class/function/variable...) Context note Context note Context note Context note.
 *
 * Mobile-first:
 * - Context note Context note Context note regex (Context note Tree-sitter Context note Compiler) — Context note 2 GB RAM
 * - snippet ≤ 240 Context note (Context note Context note Context note)
 * - 5000 Context note Context note Context note Context note scope Context note LRU eviction
 *
 * @property symbolKind class | object | interface | function | variable | property | enum
 * @property qualifiedName Context note Context note Context note Context note (e.g. "com.example.Foo.bar")
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
