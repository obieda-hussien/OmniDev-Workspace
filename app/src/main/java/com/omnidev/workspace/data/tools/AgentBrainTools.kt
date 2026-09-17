package com.omnidev.workspace.data.tools

import com.omnidev.workspace.data.brain.EpisodeOutcome
import com.omnidev.workspace.data.brain.EpisodicMemoryStore
import com.omnidev.workspace.data.brain.ReflexionEngine

/** On-device tools exposing bounded Agent Brain memory. */
class AgentBrainTools(
    private val reflexion: ReflexionEngine,
    private val episodic: EpisodicMemoryStore
) {

    fun getDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "brain_recall_lessons",
            description = "Recall relevant learned execution lessons to avoid repeated failures and reuse recovery paths.",
            parameters = listOf(
                ToolParameter("query", "string", "Semantic task/context query"),
                ToolParameter("tool_name", "string", "Optional tool name filter", required = false),
                ToolParameter("limit", "integer", "Number of lessons (1-10, default 5)", required = false)
            )
        ),
        ToolDefinition(
            name = "brain_recall_episodes",
            description = "Recall similar past task episodes with their tools and outcome.",
            parameters = listOf(
                ToolParameter("query", "string", "Current task description"),
                ToolParameter("limit", "integer", "Episodes to return (1-5, default 3)", required = false),
                ToolParameter("prefer_success", "string", "true to prefer successful episodes", required = false)
            )
        ),
        ToolDefinition(
            name = "brain_record_episode",
            description = "Manually record a task episode. Normally AgentPipeline records episodes automatically.",
            parameters = listOf(
                ToolParameter("summary", "string", "Task summary (<=500 chars)"),
                ToolParameter("user_intent", "string", "Original user objective"),
                ToolParameter("outcome", "string", "SUCCESS / FAILURE / ABANDONED / BLOCKED"),
                ToolParameter("tools_used", "string", "Comma-separated tool names", required = false)
            )
        )
    )

    suspend fun execute(name: String, args: Map<String, String>): ToolExecutionResult? {
        if (name !in HANDLED) return null
        return try {
            when (name) {
                "brain_recall_lessons" -> {
                    val query = args["query"]?.trim()
                        ?: return ToolExecutionResult("query is required", isError = true)
                    val tool = args["tool_name"]?.takeIf(String::isNotBlank)
                    val limit = args["limit"]?.toIntOrNull()?.coerceIn(1, 10) ?: 5
                    val lessons = reflexion.retrieveRelevantLessons(query, tool, topK = limit)
                    if (lessons.isEmpty()) ToolExecutionResult("No relevant lessons found.")
                    else ToolExecutionResult(buildString {
                        appendLine("Relevant learned lessons (${lessons.size}):")
                        lessons.forEach { lesson ->
                            val status = if (lesson.successContext) "WORKED" else "CAUTION"
                            val toolHint = lesson.toolName.takeIf(String::isNotBlank)?.let { "[$it] " }.orEmpty()
                            appendLine("- $status $toolHint${lesson.lesson}")
                            appendLine("  quality=${"%.2f".format(lesson.quality)} uses=${lesson.useCount}")
                        }
                    })
                }

                "brain_recall_episodes" -> {
                    val query = args["query"]?.trim()
                        ?: return ToolExecutionResult("query is required", isError = true)
                    val limit = args["limit"]?.toIntOrNull()?.coerceIn(1, 5) ?: 3
                    val preferSuccess = args["prefer_success"]?.trim()?.lowercase() != "false"
                    val episodes = episodic.retrieveSimilar(query, topK = limit, preferSuccess = preferSuccess)
                    if (episodes.isEmpty()) ToolExecutionResult("No similar episodes found.")
                    else ToolExecutionResult(buildString {
                        appendLine("Similar episodes (${episodes.size}):")
                        episodes.forEach { episode ->
                            val status = when (episode.finalOutcome) {
                                EpisodeOutcome.SUCCESS.name -> "WORKED"
                                EpisodeOutcome.FAILURE.name -> "FAILED"
                                EpisodeOutcome.ABANDONED.name -> "STALLED"
                                EpisodeOutcome.BLOCKED.name -> "BLOCKED_EXTERNALLY"
                                else -> episode.finalOutcome
                            }
                            appendLine("- [$status] ${episode.summary.take(220)}")
                            appendLine("  intent: ${episode.userIntent.take(120)}")
                            val tools = episode.toolsUsedCsv.split(',')
                                .filter(String::isNotBlank)
                                .take(8)
                                .joinToString(" -> ")
                            if (tools.isNotBlank()) appendLine("  tools: $tools")
                            appendLine("  iterations=${episode.iterationsCount} duration=${episode.totalTimeMs}ms")
                        }
                    })
                }

                "brain_record_episode" -> {
                    val summary = args["summary"]?.trim()
                        ?: return ToolExecutionResult("summary is required", isError = true)
                    val intent = args["user_intent"]?.trim()
                        ?: return ToolExecutionResult("user_intent is required", isError = true)
                    val outcomeText = args["outcome"]?.trim()?.uppercase() ?: EpisodeOutcome.SUCCESS.name
                    val outcome = runCatching { EpisodeOutcome.valueOf(outcomeText) }.getOrElse {
                        return ToolExecutionResult(
                            "outcome must be SUCCESS, FAILURE, ABANDONED, or BLOCKED",
                            isError = true
                        )
                    }
                    val tools = args["tools_used"]?.split(',')
                        ?.map(String::trim)
                        ?.filter(String::isNotBlank)
                        .orEmpty()
                    val id = episodic.recordEpisode(
                        summary = summary,
                        userIntent = intent,
                        finalOutcome = outcome,
                        toolsUsed = tools,
                        iterationsCount = 0,
                        totalTimeMs = 0,
                        sessionId = "manual"
                    )
                    if (id > 0) ToolExecutionResult("Recorded episode #$id")
                    else ToolExecutionResult("Episode was not recorded", isError = true)
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
