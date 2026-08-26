package com.omnidev.workspace.ui.chat

import org.json.JSONArray
import org.json.JSONObject

/**
 * Utility object for serializing and deserializing [AgentConsoleEntry] lists to/from a compact
 * JSON string that can be stored in the `chat_messages.consoleEntriesJson` database column.
 *
 * Uses Android's built-in `org.json` to avoid adding extra dependencies.
 */
object AgentConsoleSerializer {

    /** Serializes a list of [AgentConsoleEntry] to a JSON string (empty string if empty). */
    fun serialize(entries: List<AgentConsoleEntry>): String {
        if (entries.isEmpty()) return ""
        val array = JSONArray()
        entries.forEach { entry ->
            val obj = JSONObject()
            obj.put("ts", entry.timestamp)
            obj.put("id", entry.id)
            when (entry) {
                is AgentConsoleEntry.ThinkingEntry -> {
                    obj.put("type", "thinking")
                    obj.put("iteration", entry.iteration)
                }
                is AgentConsoleEntry.DeepThinkingEntry -> {
                    obj.put("type", "deep_thinking")
                    obj.put("snippet", entry.snippet)
                }
                is AgentConsoleEntry.ToolEntry -> {
                    obj.put("type", "tool")
                    obj.put("toolName", entry.toolName)
                    obj.put("params", entry.params)
                    obj.put("iteration", entry.iteration)
                    obj.put("fullParams", entry.fullParams)
                }
                is AgentConsoleEntry.ResultEntry -> {
                    obj.put("type", "result")
                    obj.put("toolName", entry.toolName)
                    obj.put("snippet", entry.snippet)
                    obj.put("isError", entry.isError)
                    obj.put("fullOutput", entry.fullOutput)
                    obj.put("durationMs", entry.durationMs)
                }
                is AgentConsoleEntry.TokenEntry -> {
                    obj.put("type", "token")
                    obj.put("totalTokens", entry.totalTokens)
                    if (entry.budget != null) obj.put("budget", entry.budget)
                }
                is AgentConsoleEntry.PhaseEntry -> {
                    obj.put("type", "phase")
                    obj.put("phase", entry.phase)
                    if (!entry.detail.isNullOrBlank()) obj.put("detail", entry.detail)
                }
                is AgentConsoleEntry.ReplyEntry -> {
                    obj.put("type", "reply")
                }
                                is AgentConsoleEntry.ContextSummaryEntry -> {
                    obj.put("type", "context_summary")
                    obj.put("summary", entry.summary)
                }
                is AgentConsoleEntry.ErrorEntry -> {
                    obj.put("type", "error")
                    obj.put("message", entry.message)
                }
            }
            array.put(obj)
        }
        return array.toString()
    }

    /** Deserializes a JSON string back to a [List] of [AgentConsoleEntry]. Returns empty on blank or error. */
    fun deserialize(json: String): List<AgentConsoleEntry> {
        if (json.isBlank()) return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { i ->
                val obj = array.getJSONObject(i)
                val ts = obj.getLong("ts")
                val id = obj.optLong("id", AgentConsoleEntry.nextId())
                when (obj.getString("type")) {
                    "thinking" -> AgentConsoleEntry.ThinkingEntry(
                        iteration = obj.getInt("iteration"),
                        timestamp = ts,
                        id = id
                    )
                    "deep_thinking" -> AgentConsoleEntry.DeepThinkingEntry(
                        snippet = obj.getString("snippet"),
                        timestamp = ts,
                        id = id
                    )
                    "tool" -> AgentConsoleEntry.ToolEntry(
                        toolName = obj.getString("toolName"),
                        params = obj.getString("params"),
                        iteration = obj.getInt("iteration"),
                        fullParams = obj.optString("fullParams", obj.getString("params")),
                        timestamp = ts,
                        id = id
                    )
                    "result" -> AgentConsoleEntry.ResultEntry(
                        toolName = obj.getString("toolName"),
                        snippet = obj.getString("snippet"),
                        isError = obj.getBoolean("isError"),
                        fullOutput = obj.optString("fullOutput", obj.getString("snippet")),
                        durationMs = obj.optLong("durationMs", 0L),
                        timestamp = ts,
                        id = id
                    )
                    "token" -> AgentConsoleEntry.TokenEntry(
                        totalTokens = obj.getInt("totalTokens"),
                        budget = if (obj.has("budget")) obj.getInt("budget") else null,
                        timestamp = ts,
                        id = id
                    )
                    "phase" -> AgentConsoleEntry.PhaseEntry(
                        phase = obj.getString("phase"),
                        detail = obj.optString("detail").takeIf { it.isNotBlank() },
                        timestamp = ts,
                        id = id
                    )
                    "reply" -> AgentConsoleEntry.ReplyEntry(timestamp = ts, id = id)
                                    "context_summary" -> AgentConsoleEntry.ContextSummaryEntry(
                    summary = obj.optString("summary"),
                    timestamp = ts,
                    id = id
                )
                "error" -> AgentConsoleEntry.ErrorEntry(
                        message = obj.getString("message"),
                        timestamp = ts,
                        id = id
                    )
                    else -> null
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
