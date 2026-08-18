package com.omnidev.workspace.data.tools

import com.omnidev.workspace.data.brain.EpisodeOutcome
import com.omnidev.workspace.data.brain.EpisodicMemoryStore
import com.omnidev.workspace.data.brain.ReflexionEngine

/**
 * ══════════════════════════════════════════════════════════════════════════════
 * AgentBrainTools — Context note Agent Context note Context note (Brain 2.0)
 * ══════════════════════════════════════════════════════════════════════════════
 *
 *   - brain_recall_lessons: Context note Context note Reflexion Context note Context note
 *   - brain_recall_episodes: Context note episodes (Context note Context note) Context note
 *   - brain_record_episode: Context note episode Context note (Context note — Context note Context note)
 *
 * Context note Context note on-device Context note (Lite-friendly).
 */
class AgentBrainTools(
    private val reflexion: ReflexionEngine,
    private val episodic: EpisodicMemoryStore
) {

    fun getDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "brain_recall_lessons",
            description = "Info Info Info (Reflexion) Info Info Info Info Info Info. " +
                "Info Info Info Info Info Info Info Info Info.",
            parameters = listOf(
                ToolParameter("query", "string", "Info/Info Info Info"),
                ToolParameter("tool_name", "string", "Info Info Info Info", required = false),
                ToolParameter("limit", "integer", "Info Info (1-10Info Info 5)", required = false)
            )
        ),
        ToolDefinition(
            name = "brain_recall_episodes",
            description = "Info episodes (Info Info) Info Info Info Info. " +
                "Info episode = Info Info + Info Info + Info.",
            parameters = listOf(
                ToolParameter("query", "string", "Info Info Info"),
                ToolParameter("limit", "integer", "Info Info episodes (1-5Info Info 3)", required = false),
                ToolParameter("prefer_success", "string", "true Info Info (Info true)", required = false)
            )
        ),
        ToolDefinition(
            name = "brain_record_episode",
            description = "Info episode Info (Info — Info AgentPipeline Info Info Info). " +
                "Info Info Info Info Info Info Info Info Info Info.",
            parameters = listOf(
                ToolParameter("summary", "string", "Info Info (≤ 500 Info)"),
                ToolParameter("user_intent", "string", "Info Info Info"),
                ToolParameter("outcome", "string", "SUCCESS / FAILURE / ABANDONED"),
                ToolParameter("tools_used", "string", "Info Info Info Info", required = false)
            )
        )
    )

    /** Context note null Context note Context note Context note Context note Context note Context note wrapper (Context note fall-through). */
    suspend fun execute(name: String, args: Map<String, String>): ToolExecutionResult? {
        if (name !in HANDLED) return null
        return try {
            when (name) {
                "brain_recall_lessons" -> {
                    val q = args["query"]?.trim()
                        ?: return ToolExecutionResult("query Info", isError = true)
                    val tool = args["tool_name"]?.takeIf { it.isNotBlank() }
                    val k = args["limit"]?.toIntOrNull()?.coerceIn(1, 10) ?: 5
                    val lessons = reflexion.retrieveRelevantLessons(q, tool, topK = k)
                    if (lessons.isEmpty()) ToolExecutionResult("Info Info Info Info.")
                    else ToolExecutionResult(buildString {
                        appendLine("💡 ${lessons.size} Info Info Info:")
                        for (l in lessons) {
                            val icon = if (l.successContext) "✅" else "⚠️"
                            val toolHint = if (l.toolName.isNotBlank()) "[${l.toolName}] " else ""
                            appendLine("  $icon $toolHint${l.lesson}")
                            appendLine("       Info=${"%.2f".format(l.quality)} | Info=${l.useCount}")
                        }
                    })
                }
                "brain_recall_episodes" -> {
                    val q = args["query"]?.trim()
                        ?: return ToolExecutionResult("query Info", isError = true)
                    val k = args["limit"]?.toIntOrNull()?.coerceIn(1, 5) ?: 3
                    val preferSuccess = args["prefer_success"]?.trim()?.lowercase() != "false"
                    val episodes = episodic.retrieveSimilar(q, topK = k, preferSuccess = preferSuccess)
                    if (episodes.isEmpty()) ToolExecutionResult("Info episodes Info.")
                    else ToolExecutionResult(buildString {
                        appendLine("📚 ${episodes.size} episode Info:")
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
                            appendLine("       Info=${ep.iterationsCount} | Info=${ep.totalTimeMs}ms")
                        }
                    })
                }
                "brain_record_episode" -> {
                    val summary = args["summary"]?.trim()
                        ?: return ToolExecutionResult("summary Info", isError = true)
                    val intent = args["user_intent"]?.trim()
                        ?: return ToolExecutionResult("user_intent Info", isError = true)
                    val outcomeStr = args["outcome"]?.trim()?.uppercase() ?: "SUCCESS"
                    val outcome = try {
                        EpisodeOutcome.valueOf(outcomeStr)
                    } catch (_: Throwable) {
                        return ToolExecutionResult("outcome Info Info Info SUCCESS/FAILURE/ABANDONED", isError = true)
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
                    if (id > 0) ToolExecutionResult("✅ Info episode #$id")
                    else ToolExecutionResult("❌ Info Info", isError = true)
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
