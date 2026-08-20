package com.omnidev.workspace.data.tools

import com.omnidev.workspace.data.brain.EpisodeOutcome
import com.omnidev.workspace.data.brain.EpisodicMemoryStore
import com.omnidev.workspace.data.brain.ReflexionEngine

/**
 * ══════════════════════════════════════════════════════════════════════════════
 * AgentBrainTools — System awareness note Agent System awareness note System awareness note (Brain 2.0)
 * ══════════════════════════════════════════════════════════════════════════════
 *
 *   - brain_recall_lessons: System awareness note System awareness note Reflexion System awareness note System awareness note
 *   - brain_recall_episodes: System awareness note episodes (System awareness note System awareness note) System awareness note
 *   - brain_record_episode: System awareness note episode System awareness note (System awareness note — System awareness note System awareness note)
 *
 * System awareness note System awareness note on-device System awareness note (Lite-friendly).
 */
class AgentBrainTools(
    private val reflexion: ReflexionEngine,
    private val episodic: EpisodicMemoryStore
) {

    fun getDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "brain_recall_lessons",
            description = "System awareness note System awareness note System awareness note (Reflexion) System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note. " +
                "System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note.",
            parameters = listOf(
                ToolParameter("query", "string", "System awareness note/System awareness note System awareness note System awareness note"),
                ToolParameter("tool_name", "string", "System awareness note System awareness note System awareness note System awareness note", required = false),
                ToolParameter("limit", "integer", "System awareness note System awareness note (1-10System awareness note System awareness note 5)", required = false)
            )
        ),
        ToolDefinition(
            name = "brain_recall_episodes",
            description = "System awareness note episodes (System awareness note System awareness note) System awareness note System awareness note System awareness note System awareness note. " +
                "System awareness note episode = System awareness note System awareness note + System awareness note System awareness note + System awareness note.",
            parameters = listOf(
                ToolParameter("query", "string", "System awareness note System awareness note System awareness note"),
                ToolParameter("limit", "integer", "System awareness note System awareness note episodes (1-5System awareness note System awareness note 3)", required = false),
                ToolParameter("prefer_success", "string", "true System awareness note System awareness note (System awareness note true)", required = false)
            )
        ),
        ToolDefinition(
            name = "brain_record_episode",
            description = "System awareness note episode System awareness note (System awareness note — System awareness note AgentPipeline System awareness note System awareness note System awareness note). " +
                "System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note.",
            parameters = listOf(
                ToolParameter("summary", "string", "System awareness note System awareness note (≤ 500 System awareness note)"),
                ToolParameter("user_intent", "string", "System awareness note System awareness note System awareness note"),
                ToolParameter("outcome", "string", "SUCCESS / FAILURE / ABANDONED"),
                ToolParameter("tools_used", "string", "System awareness note System awareness note System awareness note System awareness note", required = false)
            )
        )
    )

    /** System awareness note null System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note wrapper (System awareness note fall-through). */
    suspend fun execute(name: String, args: Map<String, String>): ToolExecutionResult? {
        if (name !in HANDLED) return null
        return try {
            when (name) {
                "brain_recall_lessons" -> {
                    val q = args["query"]?.trim()
                        ?: return ToolExecutionResult("query System awareness note", isError = true)
                    val tool = args["tool_name"]?.takeIf { it.isNotBlank() }
                    val k = args["limit"]?.toIntOrNull()?.coerceIn(1, 10) ?: 5
                    val lessons = reflexion.retrieveRelevantLessons(q, tool, topK = k)
                    if (lessons.isEmpty()) ToolExecutionResult("System awareness note System awareness note System awareness note System awareness note.")
                    else ToolExecutionResult(buildString {
                        appendLine("💡 ${lessons.size} System awareness note System awareness note System awareness note:")
                        for (l in lessons) {
                            val icon = if (l.successContext) "✅" else "⚠️"
                            val toolHint = if (l.toolName.isNotBlank()) "[${l.toolName}] " else ""
                            appendLine("  $icon $toolHint${l.lesson}")
                            appendLine("       System awareness note=${"%.2f".format(l.quality)} | System awareness note=${l.useCount}")
                        }
                    })
                }
                "brain_recall_episodes" -> {
                    val q = args["query"]?.trim()
                        ?: return ToolExecutionResult("query System awareness note", isError = true)
                    val k = args["limit"]?.toIntOrNull()?.coerceIn(1, 5) ?: 3
                    val preferSuccess = args["prefer_success"]?.trim()?.lowercase() != "false"
                    val episodes = episodic.retrieveSimilar(q, topK = k, preferSuccess = preferSuccess)
                    if (episodes.isEmpty()) ToolExecutionResult("System awareness note episodes System awareness note.")
                    else ToolExecutionResult(buildString {
                        appendLine("📚 ${episodes.size} episode System awareness note:")
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
                            appendLine("       System awareness note=${ep.iterationsCount} | System awareness note=${ep.totalTimeMs}ms")
                        }
                    })
                }
                "brain_record_episode" -> {
                    val summary = args["summary"]?.trim()
                        ?: return ToolExecutionResult("summary System awareness note", isError = true)
                    val intent = args["user_intent"]?.trim()
                        ?: return ToolExecutionResult("user_intent System awareness note", isError = true)
                    val outcomeStr = args["outcome"]?.trim()?.uppercase() ?: "SUCCESS"
                    val outcome = try {
                        EpisodeOutcome.valueOf(outcomeStr)
                    } catch (_: Throwable) {
                        return ToolExecutionResult("outcome System awareness note System awareness note System awareness note SUCCESS/FAILURE/ABANDONED", isError = true)
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
                    if (id > 0) ToolExecutionResult("✅ System awareness note episode #$id")
                    else ToolExecutionResult("❌ System awareness note System awareness note", isError = true)
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
