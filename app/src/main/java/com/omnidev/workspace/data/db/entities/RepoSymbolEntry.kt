package com.omnidev.workspace.data.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * RepoSymbolEntry — رمز (class/function/variable...) مُستخرج من ملف مصدر.
 *
 * Mobile-first:
 * - استخراج خفيف بـ regex (لا Tree-sitter ولا Compiler) — يلائم 2 GB RAM
 * - snippet ≤ 240 حرف (سطر التعريف فقط)
 * - 5000 رمز كحد أقصى لكل scope مع LRU eviction
 *
 * @property symbolKind class | object | interface | function | variable | property | enum
 * @property qualifiedName اسم كامل للبحث الدقيق (e.g. "com.example.Foo.bar")
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
