package com.omnidev.workspace.data.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Persistent tool-execution record supporting cross-session history, learning from outcomes and execution diagnostics. */
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

    /** Tool name. */
    val toolName: String,

    /** Arguments as a JSON string. */
    @ColumnInfo(defaultValue = "'{}'")
    val parametersJson: String = "{}",

    /** Execution result, truncated to bound storage. */
    @ColumnInfo(defaultValue = "''")
    val resultSummary: String = "",

    /** Whether execution succeeded. */
    val success: Boolean,

    /** Execution time in milliseconds. */
    val executionTimeMs: Long,

    /** Result length in characters. */
    @ColumnInfo(defaultValue = "0")
    val resultSize: Int = 0,

    /** Context describing the agent's intended task. */
    @ColumnInfo(defaultValue = "''")
    val agentContext: String = "",

    /** Previous tool in the same session. */
    @ColumnInfo(defaultValue = "''")
    val previousToolName: String = "",

    /** Session identifier. */
    @ColumnInfo(defaultValue = "''")
    val sessionId: String = "",

    /** Operating mode (DEVELOPER, RESEARCHER, etc.). */
    @ColumnInfo(defaultValue = "''")
    val agentMode: String = "",

    /** Error message when execution fails. */
    @ColumnInfo(defaultValue = "''")
    val errorMessage: String = "",

    /** Result quality score (0.0-1.0). */
    @ColumnInfo(defaultValue = "0.5")
    val resultQuality: Float = 0.5f,

    /** Hour of day (0-23). */
    @ColumnInfo(defaultValue = "0")
    val hourOfDay: Int = 0,

    /** Day of week (1-7). */
    @ColumnInfo(defaultValue = "1")
    val dayOfWeek: Int = 1,

    /** Learning notes. */
    @ColumnInfo(defaultValue = "''")
    val learningNote: String = "",

    /** Whether the record is flagged for review. */
    @ColumnInfo(defaultValue = "0")
    val flaggedForReview: Boolean = false,

    /** Timestamp. */
    val timestamp: Long = System.currentTimeMillis()
)
