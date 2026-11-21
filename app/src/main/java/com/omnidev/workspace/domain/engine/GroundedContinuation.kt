package com.omnidev.workspace.domain.engine

import com.omnidev.workspace.data.model.CompletionResponse
import com.omnidev.workspace.data.model.ToolCall
import com.omnidev.workspace.domain.engine.completion.EvidenceRef
import java.util.Calendar

/** Recovery for recognized, explicitly requested operations. Calls still pass the normal catalog, policy and batch preflight. */
internal object GroundedContinuation {
    private val webTopic = Regex("""(?i)(?:\bsearch\s+(?:(?:on|in)\s+)?(?:the\s+)?(?:google|web|internet)\s+for|(?:ابحث|تبحث|دور)\s+(?:في|على|علي)\s+(?:جوجل|الويب|النت)\s+عن)\s+(.+)""")
    private val fresh = Regex("""(?i)\blatest\b|[أا]حدث""")
    private val year = Regex("""(?<!\d)\d{4}(?!\d)""")

    private val localAlarm = Regex("""(?:ظبط|اضبط|ضبط)\s+(?:ال)?منبه\s+الساع[هة]\s+(\p{Nd}{1,2})(?::(\p{Nd}{2}))?\s+(بليل|بالليل|مساء|الصبح|صباحا)[.!؟ ]*""")

    fun recover(
        response: CompletionResponse, goal: String, evidence: List<EvidenceRef>, catalog: RunToolCatalog, iteration: Int
    ): CompletionResponse {
        if (response.toolCalls.isNotEmpty()) return response
        val pending = nextCall(goal, evidence, iteration) ?: return response
        if (!catalog.ensureLoaded(pending.name)) return response
        if (pending.name == "planner_tool" && response.content.isNotBlank() &&
            !UnfulfilledPromiseDetector.isUnfulfilled(response.content)) return response
        return response.copy(content = "", toolCalls = listOf(pending))
    }

    private fun nextCall(goal: String, evidence: List<EvidenceRef>, iteration: Int): ToolCall? {
        fun tried(tool: String) = evidence.any { it.tool == tool }
        fun call(name: String, vararg args: Pair<String, String>) =
            ToolCall("continuation_$iteration", name, mapOf(*args), textProtocol = true)

        val repo = ExecutionEnvironmentContext.readmeRepository(goal)
        if (repo != null && !tried("github_manager"))
            return call("github_manager", "action" to "read_file", "repo" to repo, "path" to "README.md")

        if (SpotifyPlayback.requested(goal) != null) {
            if (!tried("semantic_ui")) return call("semantic_ui", "action" to "dump_tree")
            return null
        }

        val parts = DeviceWorkflow.orderedParts(goal)
        if (parts.isNotEmpty() && DeviceWorkflow.notificationsFirst(goal)) {
            if (!tried("read_notifications")) return call("read_notifications", "operation" to "read")
            if (evidence.none { it.tool == "read_notifications" && it.target in setOf("", "operation=read") &&
                    it.outcome != RunEvidenceLedger.Outcome.SKIPPED }) return null
        }
        if (tried("web_search") || tried("web_search_deep")) {
            if (parts.size != 3 || !DeviceWorkflow.notificationsFirst(goal) || !DeviceWorkflow.needsWeb(parts[1]) ||
                tried("planner_tool") || evidence.none { it.tool == "read_notifications" &&
                    it.target in setOf("", "operation=read") && it.outcome == RunEvidenceLedger.Outcome.OK } || evidence.none { it.tool in setOf("web_search", "web_search_deep") &&
                    it.outcome == RunEvidenceLedger.Outcome.OK }) return null
            val alarm = localAlarm.matchEntire(parts.last().trim()) ?: return null
            val hour = alarm.groupValues[1].toIntOrNull()?.takeIf { it in 1..12 } ?: return null
            val minute = alarm.groupValues[2].ifEmpty { "0" }.toIntOrNull()?.takeIf { it in 0..59 } ?: return null
            val evening = alarm.groupValues[3] in setOf("بليل", "بالليل", "مساء")
            val localTime = String.format(java.util.Locale.ROOT, "%02d:%02d", hour % 12 + if (evening) 12 else 0, minute)
            return call("planner_tool", "action" to "alarm", "localTime" to localTime)
        }
        val web = (parts.ifEmpty { listOf(goal) }).firstOrNull(DeviceWorkflow::needsWeb) ?: return null
        val topic = webTopic.find(web)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() && it.length <= 500 }
            ?: return null
        val query = if (fresh.containsMatchIn(topic) && !year.containsMatchIn(topic))
            "$topic ${Calendar.getInstance().get(Calendar.YEAR)}" else topic
        return call("web_search", "query" to query)
    }
}
