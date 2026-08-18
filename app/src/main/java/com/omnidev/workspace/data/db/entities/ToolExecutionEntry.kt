package com.omnidev.workspace.data.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * ToolExecutionEntry — Context note Context note Context note Context note
 *
 * Context note Context note Context note Context note Context note Context note Context note Context note:
 * - Context note Context note Context note Context note
 * - Context note Context note Context note Context note
 * - Context note Context note Context note
 * - Context note Context note Context note Context note Context note
 */
@Entity(
    tableName = "tool_execution_log",
    indices = [
        Index(name = "index_tool_log_tool", value = ["toolName"]),
        Index(name = "index_tool_log_session", value = ["sessionId"]),
        Index(name = "index_tool_log_time", value = ["timestamp"])
    ]
)
data class ToolExecutionEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** Context note Context note Context note */
    val toolName: String,

    /** Context note Context note JSON string */
    @ColumnInfo(defaultValue = "'{}'")
    val parametersJson: String = "{}",

    /** Context note Context note (Context note Context note Context note) */
    @ColumnInfo(defaultValue = "''")
    val resultSummary: String = "",

    /** Context note Context note Context note */
    val success: Boolean,

    /** Context note Context note Context note Context note */
    val executionTimeMs: Long,

    /** Context note Context note Context note */
    @ColumnInfo(defaultValue = "0")
    val resultSize: Int = 0,

    /** Context note: Context note Context note Context note Agent Context note Context note */
    @ColumnInfo(defaultValue = "''")
    val agentContext: String = "",

    /** Context note Context note Context note Context note Context note */
    @ColumnInfo(defaultValue = "''")
    val previousToolName: String = "",

    /** Context note Context note */
    @ColumnInfo(defaultValue = "''")
    val sessionId: String = "",

    /** Context note Context note (DEVELOPER, RESEARCHER, etc.) */
    @ColumnInfo(defaultValue = "''")
    val agentMode: String = "",

    /** Context note Context note Context note Context note Context note */
    @ColumnInfo(defaultValue = "''")
    val errorMessage: String = "",

    /** Context note Context note Context note (0.0 - 1.0) */
    @ColumnInfo(defaultValue = "0.5")
    val resultQuality: Float = 0.5f,

    /** Context note Context note Context note (0-23) */
    @ColumnInfo(defaultValue = "0")
    val hourOfDay: Int = 0,

    /** Context note Context note (1-7) */
    @ColumnInfo(defaultValue = "1")
    val dayOfWeek: Int = 1,

    /** Context note Context note Context note */
    @ColumnInfo(defaultValue = "''")
    val learningNote: String = "",

    /** Context note Context note Context note Context note Context note */
    @ColumnInfo(defaultValue = "0")
    val flaggedForReview: Boolean = false,

    /** Context note Context note */
    val timestamp: Long = System.currentTimeMillis()
)
