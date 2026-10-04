package com.omnidev.workspace.data.routines

import kotlinx.serialization.Serializable

/** Durable executable recipes, separate from prompt-only SKILL.md files. */
@Serializable
data class UiSelector(
    val packageName: String,
    val viewId: String = "",
    val text: String = "",
    val description: String = "",
    val className: String = ""
) {
    val hasIdentity: Boolean get() = viewId.isNotBlank() || text.isNotBlank() || description.isNotBlank()
}

@Serializable
enum class RoutineStepKind { CLICK, LONG_CLICK, TYPE, SCROLL, WAIT, TOOL, DECISION, USER }

@Serializable
data class RoutineStep(
    val kind: RoutineStepKind,
    val label: String,
    val selector: UiSelector? = null,
    val value: String = "",
    val tool: String = "",
    val arguments: Map<String, String> = emptyMap(),
    val expected: UiSelector? = null,
    val timeoutMs: Long = 5_000
)

@Serializable
data class LearnedRoutine(
    val id: String,
    val name: String,
    val triggers: List<String>,
    val steps: List<RoutineStep>,
    val enabled: Boolean = false,
    val revision: Int = 1,
    val source: String = "agent",
    val createdAt: Long = System.currentTimeMillis(),
    val successfulRuns: Int = 0
)

@Serializable
enum class RoutineRunStatus { RUNNING, PAUSED, COMPLETED, CANCELLED }

@Serializable
data class RoutineRun(
    val id: String,
    val routineId: String,
    val revision: Int,
    val nextStep: Int = 0,
    val status: RoutineRunStatus = RoutineRunStatus.RUNNING,
    val reason: String = "",
    val parameters: Map<String, String> = emptyMap(),
    val scopePath: String? = null,
    val inFlight: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis()
)

/** Exact approved aliases only. Ambiguous matches never execute. No model or embedding call. */
object RoutineMatcher {
    private val slot = Regex("\\{\\{([a-zA-Z][a-zA-Z0-9_]{0,39})}}")
    fun variables(value: String): Set<String> = slot.findAll(value).map { it.groupValues[1] }.toSet()
    fun bind(value: String, parameters: Map<String, String>): String = slot.replace(value) {
        parameters[it.groupValues[1]] ?: error("Missing parameter: ${it.groupValues[1]}")
    }
    fun required(routine: LearnedRoutine): Set<String> = routine.steps.flatMap {
        variables(it.value) + it.arguments.values.flatMap(::variables) +
            listOfNotNull(it.selector?.text, it.selector?.description, it.expected?.text, it.expected?.description).flatMap(::variables)
    }.toSet()

    fun bindSelector(selector: UiSelector, parameters: Map<String, String>) = selector.copy(
        text = bind(selector.text, parameters), description = bind(selector.description, parameters))

    fun match(message: String, routines: List<LearnedRoutine>): Pair<LearnedRoutine, Map<String, String>>? {
        val hits = routines.filter { it.enabled }.flatMap { routine ->
            routine.triggers.mapNotNull { alias -> matchAlias(message.trim(), alias.trim()) }
                .distinct().map { bindings -> routine to bindings }
        }
        return hits.singleOrNull()
    }

    private fun matchAlias(message: String, alias: String): Map<String, String>? {
        if (alias.isBlank()) return null
        val slots = slot.findAll(alias).toList()
        if (slots.isEmpty()) return if (normalize(message) == normalize(alias)) emptyMap() else null
        if (slots.map { it.groupValues[1] }.distinct().size != slots.size) return null
        if (slots.zipWithNext().any { (a, b) -> a.range.last + 1 == b.range.first }) return null
        val pattern = buildString {
            append("^")
            var end = 0
            slots.forEach { s ->
                append(Regex.escape(alias.substring(end, s.range.first)))
                append("(.+?)")
                end = s.range.last + 1
            }
            append(Regex.escape(alias.substring(end))).append("$")
        }
        val match = Regex(pattern, RegexOption.IGNORE_CASE).matchEntire(message) ?: return null
        return slots.mapIndexed { index, s -> s.groupValues[1] to match.groupValues[index + 1].trim() }.toMap()
    }
    private fun normalize(s: String) = s.trim().lowercase(java.util.Locale.ROOT)
        .replace(Regex("[\\u064B-\\u065F\\u0670]"), "")
        .replace(Regex("[أإآ]"), "ا").replace(Regex("\\s+"), " ")
}

object RoutineValidation {
    // Observations plus explicitly reviewed native entry points and pinned SDK capabilities.
    val readTools = setOf("get_device_info", "device_info_tool", "read_file_lines", "read_file", "search_knowledge", "get_trust_profile")
    fun replayableTool(tool: String, args: Map<String, String>): Boolean = when (tool) {
        in readTools -> true
        "app_manager_tool" -> args["action"] == "launch_app" &&
            args["packageName"]?.matches(Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+")) == true
        "omni_link" -> args["action"] in setOf("execute_action", "execute_capability") &&
            !args["extension_id"].isNullOrBlank() && !args["action_name"].isNullOrBlank()
        else -> false
    }
    fun validate(routine: LearnedRoutine) {
        require(routine.id.matches(Regex("[a-zA-Z0-9_-]{1,80}"))) { "Invalid routine ID" }
        require(routine.name.isNotBlank() && routine.name.length <= 120)
        require(routine.triggers.size in 1..20 && routine.triggers.all { it.length in 1..500 })
        require(routine.steps.size in 1..100)
        require(routine.revision > 0)
        routine.steps.forEach {
            require(it.timeoutMs in 100..15_000)
            require(it.label.length <= 500 && it.value.length <= 10_000)
            if (it.kind in setOf(RoutineStepKind.CLICK, RoutineStepKind.LONG_CLICK,
                    RoutineStepKind.TYPE, RoutineStepKind.SCROLL, RoutineStepKind.WAIT)) {
                require(it.selector?.hasIdentity == true && it.selector.packageName.isNotBlank()) {
                    "UI steps need package-scoped semantic selectors, never recorded coordinates or node IDs"
                }
            }
            if (it.kind == RoutineStepKind.TYPE) require(RoutineMatcher.variables(it.value).isNotEmpty()) {
                "Typed values must be runtime parameters, never captured user input"
            }
            if (it.kind == RoutineStepKind.TOOL) require(replayableTool(it.tool, it.arguments)) { "Unsupported replay tool: ${it.tool}" }
            it.expected?.let { expected -> require(expected.hasIdentity && expected.packageName.isNotBlank()) }
        }
    }
}

/** A reviewed launch step lets library runs reach the app before resolving their first UI target. */
object RoutineCompilation {
    fun withEntryPoint(steps: List<RoutineStep>): List<RoutineStep> {
        val first = steps.firstOrNull() ?: return steps
        val target = first.selector ?: return steps
        if (steps.size >= 100) return steps
        return listOf(RoutineStep(RoutineStepKind.TOOL, "Open ${target.packageName}", tool = "app_manager_tool",
            arguments = mapOf("action" to "launch_app", "packageName" to target.packageName), expected = target)) + steps
    }
}
