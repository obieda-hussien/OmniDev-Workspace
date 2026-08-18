package com.omnidev.workspace.data.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * RepoSymbolEntry — [Localized] (class/function/variable...) [Localized] [Localized] [Localized] [Localized].
 *
 * Mobile-first:
 * - [Localized] [Localized] [Localized] regex ([Localized] Tree-sitter [Localized] Compiler) — [Localized] 2 GB RAM
 * - snippet ≤ 240 [Localized] ([Localized] [Localized] [Localized])
 * - 5000 [Localized] [Localized] [Localized] [Localized] scope [Localized] LRU eviction
 *
 * @property symbolKind class | object | interface | function | variable | property | enum
 * @property qualifiedName [Localized] [Localized] [Localized] [Localized] (e.g. "com.example.Foo.bar")
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
