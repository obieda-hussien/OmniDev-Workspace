package com.omnidev.workspace.data.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * ToolExecutionEntry — [Localized] [Localized] [Localized] [Localized]
 *
 * [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized]:
 * - [Localized] [Localized] [Localized] [Localized]
 * - [Localized] [Localized] [Localized] [Localized]
 * - [Localized] [Localized] [Localized]
 * - [Localized] [Localized] [Localized] [Localized] [Localized]
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

    /** [Localized] [Localized] [Localized] */
    val toolName: String,

    /** [Localized] [Localized] JSON string */
    @ColumnInfo(defaultValue = "'{}'")
    val parametersJson: String = "{}",

    /** [Localized] [Localized] ([Localized] [Localized] [Localized]) */
    @ColumnInfo(defaultValue = "''")
    val resultSummary: String = "",

    /** [Localized] [Localized] [Localized] */
    val success: Boolean,

    /** [Localized] [Localized] [Localized] [Localized] */
    val executionTimeMs: Long,

    /** [Localized] [Localized] [Localized] */
    @ColumnInfo(defaultValue = "0")
    val resultSize: Int = 0,

    /** [Localized]: [Localized] [Localized] [Localized] Agent [Localized] [Localized] */
    @ColumnInfo(defaultValue = "''")
    val agentContext: String = "",

    /** [Localized] [Localized] [Localized] [Localized] [Localized] */
    @ColumnInfo(defaultValue = "''")
    val previousToolName: String = "",

    /** [Localized] [Localized] */
    @ColumnInfo(defaultValue = "''")
    val sessionId: String = "",

    /** [Localized] [Localized] (DEVELOPER, RESEARCHER, etc.) */
    @ColumnInfo(defaultValue = "''")
    val agentMode: String = "",

    /** [Localized] [Localized] [Localized] [Localized] [Localized] */
    @ColumnInfo(defaultValue = "''")
    val errorMessage: String = "",

    /** [Localized] [Localized] [Localized] (0.0 - 1.0) */
    @ColumnInfo(defaultValue = "0.5")
    val resultQuality: Float = 0.5f,

    /** [Localized] [Localized] [Localized] (0-23) */
    @ColumnInfo(defaultValue = "0")
    val hourOfDay: Int = 0,

    /** [Localized] [Localized] (1-7) */
    @ColumnInfo(defaultValue = "1")
    val dayOfWeek: Int = 1,

    /** [Localized] [Localized] [Localized] */
    @ColumnInfo(defaultValue = "''")
    val learningNote: String = "",

    /** [Localized] [Localized] [Localized] [Localized] [Localized] */
    @ColumnInfo(defaultValue = "0")
    val flaggedForReview: Boolean = false,

    /** [Localized] [Localized] */
    val timestamp: Long = System.currentTimeMillis()
)
