package com.omnidev.workspace.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A persistent fact or rule stored in the agent's long-term knowledge base.
 *
 * The agent can call `remember_fact` to store new entries and `search_knowledge`
 * to retrieve relevant entries before composing a response.
 *
 * @property id Auto-generated primary key.
 * @property category Broad grouping tag (e.g. "user_preference", "architecture", "project_rule").
 * @property content The natural-language fact or rule.
 * @property tags Comma-separated search tags for fuzzy retrieval.
 * @property createdAt Unix timestamp (ms) of when this snippet was created.
 */
@Entity(tableName = "knowledge_snippets")
data class KnowledgeSnippet(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val category: String,
    val content: String,
    val tags: String = "",
    val createdAt: Long = System.currentTimeMillis()
)
