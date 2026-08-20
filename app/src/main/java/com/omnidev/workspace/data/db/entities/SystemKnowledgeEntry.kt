package com.omnidev.workspace.data.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * SystemKnowledgeEntry — Persisted Agent System Knowledge
 *
 * Stores discoveries made by the Agent regarding:
 * - Tool capabilities and requirements
 * - System environment and device specs
 * - Learned patterns and user preferences
 * - Important system warnings and notes
 */
@Entity(
    tableName = "system_knowledge",
    indices = [Index(value = ["category", "key"], unique = true)]
)
data class SystemKnowledgeEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val category: String, // e.g. "runtime_env", "system_info", "tool_capability", "warning"
    val key: String,      // e.g. "bluetooth", "storage", "shizuku", "termux"
    val content: String,
    val confidence: Float = 1.0f,
    val timestamp: Long = System.currentTimeMillis(),

    // Backwards compatibility properties
    val knowledgeType: String = category,
    val subject: String = key,
    val verificationCount: Int = 1,
    val isValid: Boolean = true,
    val source: String = "agent_discovery",
    val searchTags: String = "",
    val injectionPriority: Int = 5,
    val createdAt: Long = timestamp,
    val updatedAt: Long = timestamp
)
