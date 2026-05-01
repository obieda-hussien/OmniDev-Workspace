package com.omnidev.workspace.data.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * RepoFileIndexEntry — سجل كل ملف في Live Repository Context Engine.
 *
 * يستخدم لكشف التغييرات تدريجياً (incremental indexing): نُعيد تحليل الملف
 * فقط لو تغيّرت mtime أو الحجم — يوفر اجتياح كامل لمشروع 5000 ملف على
 * أجهزة Android الضعيفة.
 *
 * @property scopePath جذر المشروع المُفهرس (يدعم تعدد المشاريع المتزامنة)
 * @property contentHash SHA-256 مقطوع (16 hex) للكشف عن تغييرات السطر-السطر
 * @property skipReason لو الملف مُتجاهَل (binary/large/excluded) → نسجل السبب
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
