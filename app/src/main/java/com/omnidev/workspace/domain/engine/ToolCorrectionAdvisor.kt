package com.omnidev.workspace.domain.engine

import com.omnidev.workspace.data.model.ToolCall
import com.omnidev.workspace.data.tools.ToolDefinition
import kotlinx.serialization.json.*

/** Interpretation is advisory. Only an exact new proposal can pass preflight and dispatch authorization. */
internal enum class ToolContractIssueKind { UNKNOWN_TOOL, UNKNOWN_PARAMETER, MISSING_PARAMETER, INVALID_ACTION, INVALID_TYPE, MALFORMED_ARGUMENTS }
internal data class ToolContractIssue(val kind: ToolContractIssueKind, val field: String? = null,
    val expected: String? = null, val suggestion: String? = null)
internal data class ToolCorrectionReport(val tool: String?, val issues: List<ToolContractIssue>, val moreIssues: Boolean = false) {
    val valid: Boolean get() = issues.isEmpty() && !moreIssues
    fun render(): String = buildString {
        append("Contract corrections (advisory only; submit a new call): ")
        issues.forEachIndexed { i, issue ->
            if (i > 0) append("; ")
            append(issue.kind.name)
            issue.field?.let { append(" field=").append(it.take(80)) }
            issue.expected?.let { append(" expected=").append(it.take(180)) }
            issue.suggestion?.let { append(" possible exact value=").append(it.take(160)) }
        }
        if (moreIssues) append("; additional issues omitted; inspect the exact schema")
    }.take(2_400)
}

/** Consumes only the caller's currently eligible contracts; never searches a global/denied registry. */
internal class ToolCorrectionAdvisor(definitions: List<ToolDefinition>) {
    private val contracts = definitions.groupBy { it.name }
    fun hasUniqueContract(name: String): Boolean = contracts[name]?.size == 1
    fun review(call: ToolCall): ToolCorrectionReport {
        val matches = contracts[call.name]
        val definition = matches?.singleOrNull()
        if (definition == null) return ToolCorrectionReport(null, listOf(ToolContractIssue(ToolContractIssueKind.UNKNOWN_TOOL,
            suggestion = if (matches == null) ToolTypoAdvisor.suggest(call.name, contracts.filterValues { it.size == 1 }.keys) else null)))
        if (call.argumentError != null) return ToolCorrectionReport(definition.name, listOf(ToolContractIssue(ToolContractIssueKind.MALFORMED_ARGUMENTS)))
        val parameters = definition.parameters.associateBy { it.name }
        val issues = ArrayList<ToolContractIssue>()
        fun add(issue: ToolContractIssue) { if (issues.size < 13) issues += issue }
        // Do not echo unknown names or supplied values: either can contain credentials or instructions.
        val unknown = call.arguments.keys - parameters.keys
        unknown.sorted().take(13).forEach { key -> add(ToolContractIssue(ToolContractIssueKind.UNKNOWN_PARAMETER,
            suggestion = ToolTypoAdvisor.suggest(key, parameters.keys))) }
        val action = call.arguments["action"]
        val actionSchema = parameters["action"]
        val actionHint = action?.let { ToolTypoAdvisor.suggest(it, actionSchema?.allowedValues.orEmpty()) }
        val effectiveAction = action?.takeIf { actionSchema?.allowedValues.isNullOrEmpty() || it in actionSchema!!.allowedValues } ?: actionHint
        definition.parameters.forEach { p ->
            if (issues.size >= 13) return@forEach
            val value = call.arguments[p.name]
            if (value == null) {
                if (p.required || effectiveAction in p.requiredForActions) add(ToolContractIssue(ToolContractIssueKind.MISSING_PARAMETER, p.name, p.type))
            } else {
                if ((p.name in setOf("action", "operation") && value.isBlank()) || p.allowedValues.isNotEmpty() && value !in p.allowedValues) {
                    add(ToolContractIssue(ToolContractIssueKind.INVALID_ACTION, p.name,
                        if (p.allowedValues.isEmpty()) "nonempty string" else p.allowedValues.take(12).joinToString("|"),
                        ToolTypoAdvisor.suggest(value, p.allowedValues)))
                }
                val valid = when (p.type.lowercase()) {
                    "integer" -> value.toLongOrNull() != null
                    "number" -> value.toDoubleOrNull()?.isFinite() == true
                    "boolean" -> value == "true" || value == "false"
                    "object" -> parse(value) is JsonObject
                    "array" -> parse(value) is JsonArray
                    "string" -> true
                    else -> false
                }
                if (!valid) add(ToolContractIssue(ToolContractIssueKind.INVALID_TYPE, p.name, p.type))
            }
        }
        return ToolCorrectionReport(definition.name, issues.take(12), issues.size > 12)
    }
    private fun parse(value: String): JsonElement? = try { Json.parseToJsonElement(value) } catch (_: IllegalArgumentException) { null }
}
