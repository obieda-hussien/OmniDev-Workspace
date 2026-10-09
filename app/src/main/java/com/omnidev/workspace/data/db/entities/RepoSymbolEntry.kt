package com.omnidev.workspace.data.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** RepoSymbolEntry stores a source symbol extracted by regex without a compiler. Snippets are bounded to 240 characters and scopes to 5000 symbols with LRU eviction. symbolKind covers class/object/interface/function/variable/property/enum; qualifiedName enables precise lookup such as com.example.Foo.bar. */
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
