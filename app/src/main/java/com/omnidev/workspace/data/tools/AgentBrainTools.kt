package com.omnidev.workspace.data.tools

import com.omnidev.workspace.data.brain.EpisodeOutcome
import com.omnidev.workspace.data.brain.EpisodicMemoryStore
import com.omnidev.workspace.data.brain.ReflexionEngine

/**
 * ══════════════════════════════════════════════════════════════════════════════
 * AgentBrainTools — [Localized] Agent [Localized] [Localized] (Brain 2.0)
 * ══════════════════════════════════════════════════════════════════════════════
 *
 *   - brain_recall_lessons: [Localized] [Localized] Reflexion [Localized] [Localized]
 *   - brain_recall_episodes: [Localized] episodes ([Localized] [Localized]) [Localized]
 *   - brain_record_episode: [Localized] episode [Localized] ([Localized] — [Localized] [Localized])
 *
 * [Localized] [Localized] on-device [Localized] (Lite-friendly).
 */
class AgentBrainTools(
    private val reflexion: ReflexionEngine,
    private val episodic: EpisodicMemoryStore
) {

    fun getDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "brain_recall_lessons",
            description = "[Localized] [Localized] [Localized] (Reflexion) [Localized] [Localized] [Localized] [Localized] [Localized] [Localized]. " +
                "[Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized].",
            parameters = listOf(
                ToolParameter("query", "string", "[Localized]/[Localized] [Localized] [Localized]"),
                ToolParameter("tool_name", "string", "[Localized] [Localized] [Localized] [Localized]", required = false),
                ToolParameter("limit", "integer", "[Localized] [Localized] (1-10[Localized] [Localized] 5)", required = false)
            )
        ),
        ToolDefinition(
            name = "brain_recall_episodes",
            description = "[Localized] episodes ([Localized] [Localized]) [Localized] [Localized] [Localized] [Localized]. " +
                "[Localized] episode = [Localized] [Localized] + [Localized] [Localized] + [Localized].",
            parameters = listOf(
                ToolParameter("query", "string", "[Localized] [Localized] [Localized]"),
                ToolParameter("limit", "integer", "[Localized] [Localized] episodes (1-5[Localized] [Localized] 3)", required = false),
                ToolParameter("prefer_success", "string", "true [Localized] [Localized] ([Localized] true)", required = false)
            )
        ),
        ToolDefinition(
            name = "brain_record_episode",
            description = "[Localized] episode [Localized] ([Localized] — [Localized] AgentPipeline [Localized] [Localized] [Localized]). " +
                "[Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized].",
            parameters = listOf(
                ToolParameter("summary", "string", "[Localized] [Localized] (≤ 500 [Localized])"),
                ToolParameter("user_intent", "string", "[Localized] [Localized] [Localized]"),
                ToolParameter("outcome", "string", "SUCCESS / FAILURE / ABANDONED"),
                ToolParameter("tools_used", "string", "[Localized] [Localized] [Localized] [Localized]", required = false)
            )
        )
    )

    /** [Localized] null [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] wrapper ([Localized] fall-through). */
    suspend fun execute(name: String, args: Map<String, String>): ToolExecutionResult? {
        if (name !in HANDLED) return null
        return try {
            when (name) {
                "brain_recall_lessons" -> {
                    val q = args["query"]?.trim()
                        ?: return ToolExecutionResult("query [Localized]", isError = true)
                    val tool = args["tool_name"]?.takeIf { it.isNotBlank() }
                    val k = args["limit"]?.toIntOrNull()?.coerceIn(1, 10) ?: 5
                    val lessons = reflexion.retrieveRelevantLessons(q, tool, topK = k)
                    if (lessons.isEmpty()) ToolExecutionResult("[Localized] [Localized] [Localized] [Localized].")
                    else ToolExecutionResult(buildString {
                        appendLine("💡 ${lessons.size} [Localized] [Localized] [Localized]:")
                        for (l in lessons) {
                            val icon = if (l.successContext) "✅" else "⚠️"
                            val toolHint = if (l.toolName.isNotBlank()) "[${l.toolName}] " else ""
                            appendLine("  $icon $toolHint${l.lesson}")
                            appendLine("       [Localized]=${"%.2f".format(l.quality)} | [Localized]=${l.useCount}")
                        }
                    })
                }
                "brain_recall_episodes" -> {
                    val q = args["query"]?.trim()
                        ?: return ToolExecutionResult("query [Localized]", isError = true)
                    val k = args["limit"]?.toIntOrNull()?.coerceIn(1, 5) ?: 3
                    val preferSuccess = args["prefer_success"]?.trim()?.lowercase() != "false"
                    val episodes = episodic.retrieveSimilar(q, topK = k, preferSuccess = preferSuccess)
                    if (episodes.isEmpty()) ToolExecutionResult("[Localized] episodes [Localized].")
                    else ToolExecutionResult(buildString {
                        appendLine("📚 ${episodes.size} episode [Localized]:")
                        for (ep in episodes) {
                            val icon = when (ep.finalOutcome) {
                                "SUCCESS" -> "✅"
                                "FAILURE" -> "❌"
                                else -> "⚠️"
                            }
                            appendLine("  $icon ${ep.summary.take(220)}")
                            appendLine("       intent: ${ep.userIntent.take(120)}")
                            val tools = ep.toolsUsedCsv.split(',').take(8).joinToString(" → ")
                            if (tools.isNotBlank()) appendLine("       🔧 $tools")
                            appendLine("       [Localized]=${ep.iterationsCount} | [Localized]=${ep.totalTimeMs}ms")
                        }
                    })
                }
                "brain_record_episode" -> {
                    val summary = args["summary"]?.trim()
                        ?: return ToolExecutionResult("summary [Localized]", isError = true)
                    val intent = args["user_intent"]?.trim()
                        ?: return ToolExecutionResult("user_intent [Localized]", isError = true)
                    val outcomeStr = args["outcome"]?.trim()?.uppercase() ?: "SUCCESS"
                    val outcome = try {
                        EpisodeOutcome.valueOf(outcomeStr)
                    } catch (_: Throwable) {
                        return ToolExecutionResult("outcome [Localized] [Localized] [Localized] SUCCESS/FAILURE/ABANDONED", isError = true)
                    }
                    val tools = args["tools_used"]?.split(',')
                        ?.map { it.trim() }
                        ?.filter { it.isNotBlank() } ?: emptyList()
                    val id = episodic.recordEpisode(
                        summary = summary,
                        userIntent = intent,
                        finalOutcome = outcome,
                        toolsUsed = tools,
                        iterationsCount = 0,
                        totalTimeMs = 0,
                        sessionId = "manual"
                    )
                    if (id > 0) ToolExecutionResult("✅ [Localized] episode #$id")
                    else ToolExecutionResult("❌ [Localized] [Localized]", isError = true)
                }
                else -> ToolExecutionResult("Unknown tool: $name", isError = true)
            }
        } catch (t: Throwable) {
            ToolExecutionResult("Brain tool error: ${t.message}", isError = true)
        }
    }

    fun handles(name: String): Boolean = name in HANDLED

    companion object {
        val HANDLED = setOf(
            "brain_recall_lessons",
            "brain_recall_episodes",
            "brain_record_episode"
        )
    }
}
