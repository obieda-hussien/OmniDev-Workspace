package com.omnidev.workspace.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "scheduled_tasks")
data class ScheduledTaskEntity(
    @PrimaryKey
    val id: String,
    val prompt: String,
    val initialDelayMs: Long,
    val repeatIntervalMs: Long,
    val isRecurring: Boolean,
    val createdAt: Long,
    val nextExecutionTime: Long,
    val status: String,
    val allowWakeLock: Boolean,
    val lastExecutionResult: String? = null
)
