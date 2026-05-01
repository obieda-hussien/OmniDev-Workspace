package com.omnidev.workspace.data.tools

import com.omnidev.workspace.data.brain.EpisodeOutcome
import com.omnidev.workspace.data.brain.EpisodicMemoryStore
import com.omnidev.workspace.data.brain.ReflexionEngine

/**
 * ══════════════════════════════════════════════════════════════════════════════
 * AgentBrainTools — أدوات Agent للوصول لذاكرته (Brain 2.0)
 * ══════════════════════════════════════════════════════════════════════════════
 *
 *   - brain_recall_lessons: استرجاع دروس Reflexion ذات صلة
 *   - brain_recall_episodes: استرجاع episodes (مهام كاملة) مشابهة
 *   - brain_record_episode: تسجيل episode يدوياً (نادر — عادة آلي)
 *
 * كل الأدوات on-device بالكامل (Lite-friendly).
 */
class AgentBrainTools(
    private val reflexion: ReflexionEngine,
    private val episodic: EpisodicMemoryStore
) {

    fun getDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "brain_recall_lessons",
            description = "استرجاع دروس مستفادة (Reflexion) ذات صلة بسؤال أو أداة معينة. " +
                "يساعدك على تجنب أخطاء سابقة وإعادة استخدام أنماط ناجحة.",
            parameters = listOf(
                ToolParameter("query", "string", "سؤال/سياق للبحث الدلالي"),
                ToolParameter("tool_name", "string", "اسم أداة للتركيز عليها", required = false),
                ToolParameter("limit", "integer", "عدد الدروس (1-10، الافتراضي 5)", required = false)
            )
        ),
        ToolDefinition(
            name = "brain_recall_episodes",
            description = "استرجاع episodes (مهام كاملة) مشابهة من الذاكرة العَرَضية. " +
                "كل episode = ملخص مهمة + الأدوات المستخدمة + النتيجة.",
            parameters = listOf(
                ToolParameter("query", "string", "وصف المهمة الحالية"),
                ToolParameter("limit", "integer", "عدد الـ episodes (1-5، الافتراضي 3)", required = false),
                ToolParameter("prefer_success", "string", "true لتفضيل الناجحين (الافتراضي true)", required = false)
            )
        ),
        ToolDefinition(
            name = "brain_record_episode",
            description = "تسجيل episode يدوياً (نادر — عادة AgentPipeline يفعل هذا تلقائياً). " +
                "استخدم فقط لو تريد حفظ ملخص مهمة معينة بشكل صريح.",
            parameters = listOf(
                ToolParameter("summary", "string", "ملخص المهمة (≤ 500 حرف)"),
                ToolParameter("user_intent", "string", "ما طلبه المستخدم"),
                ToolParameter("outcome", "string", "SUCCESS / FAILURE / ABANDONED"),
                ToolParameter("tools_used", "string", "قائمة الأدوات مفصولة بفواصل", required = false)
            )
        )
    )

    /** يُرجع null إذا الأداة ليست مملوكة لهذا الـ wrapper (لتمرير fall-through). */
    suspend fun execute(name: String, args: Map<String, String>): ToolExecutionResult? {
        if (name !in HANDLED) return null
        return try {
            when (name) {
                "brain_recall_lessons" -> {
                    val q = args["query"]?.trim()
                        ?: return ToolExecutionResult("query مطلوب", isError = true)
                    val tool = args["tool_name"]?.takeIf { it.isNotBlank() }
                    val k = args["limit"]?.toIntOrNull()?.coerceIn(1, 10) ?: 5
                    val lessons = reflexion.retrieveRelevantLessons(q, tool, topK = k)
                    if (lessons.isEmpty()) ToolExecutionResult("لا دروس ذات صلة.")
                    else ToolExecutionResult(buildString {
                        appendLine("💡 ${lessons.size} درس ذو صلة:")
                        for (l in lessons) {
                            val icon = if (l.successContext) "✅" else "⚠️"
                            val toolHint = if (l.toolName.isNotBlank()) "[${l.toolName}] " else ""
                            appendLine("  $icon $toolHint${l.lesson}")
                            appendLine("       جودة=${"%.2f".format(l.quality)} | استخدم=${l.useCount}")
                        }
                    })
                }
                "brain_recall_episodes" -> {
                    val q = args["query"]?.trim()
                        ?: return ToolExecutionResult("query مطلوب", isError = true)
                    val k = args["limit"]?.toIntOrNull()?.coerceIn(1, 5) ?: 3
                    val preferSuccess = args["prefer_success"]?.trim()?.lowercase() != "false"
                    val episodes = episodic.retrieveSimilar(q, topK = k, preferSuccess = preferSuccess)
                    if (episodes.isEmpty()) ToolExecutionResult("لا episodes مشابهة.")
                    else ToolExecutionResult(buildString {
                        appendLine("📚 ${episodes.size} episode مشابه:")
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
                            appendLine("       تكرارات=${ep.iterationsCount} | وقت=${ep.totalTimeMs}ms")
                        }
                    })
                }
                "brain_record_episode" -> {
                    val summary = args["summary"]?.trim()
                        ?: return ToolExecutionResult("summary مطلوب", isError = true)
                    val intent = args["user_intent"]?.trim()
                        ?: return ToolExecutionResult("user_intent مطلوب", isError = true)
                    val outcomeStr = args["outcome"]?.trim()?.uppercase() ?: "SUCCESS"
                    val outcome = try {
                        EpisodeOutcome.valueOf(outcomeStr)
                    } catch (_: Throwable) {
                        return ToolExecutionResult("outcome يجب أن يكون SUCCESS/FAILURE/ABANDONED", isError = true)
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
                    if (id > 0) ToolExecutionResult("✅ سُجِّل episode #$id")
                    else ToolExecutionResult("❌ فشل التسجيل", isError = true)
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
