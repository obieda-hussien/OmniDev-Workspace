package com.omnidev.workspace.data.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** SystemKnowledgeEntry stores discovered tool requirements, system and device capabilities, learned preferences and important warnings. */
@Entity(tableName = "system_knowledge", indices = [Index(value = ["subject", "knowledgeType", "source"], name = "index_knowledge_lookup")])
data class SystemKnowledgeEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** Knowledge category. */
    val knowledgeType: String, // TOOL_CAPABILITY, SYSTEM_INFO, PATTERN, WARNING, PREFERENCE, DEPENDENCY

    /** Subject, for example a tool or component name. */
    val subject: String,

    /** Detailed content. */
    val content: String,

    /** Confidence level (0.0-1.0). */
    val confidence: Float = 1.0f,

    /** Number of times this knowledge has been verified. */
    val verificationCount: Int = 1,

    /** Whether this knowledge is still valid. */
    val isValid: Boolean = true,

    /** Knowledge source. */
    val source: String = "agent_discovery",

    /** Search keywords. */
    val searchTags: String = "",

    /** System-prompt injection priority. */
    val injectionPriority: Int = 5, // 1 = highest, 10 = lowest.

    /** Creation timestamp. */
    val createdAt: Long = System.currentTimeMillis(),

    /** Last update. */
    val updatedAt: Long = System.currentTimeMillis()
)
