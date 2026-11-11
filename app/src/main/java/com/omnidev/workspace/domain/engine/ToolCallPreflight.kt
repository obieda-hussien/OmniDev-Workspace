package com.omnidev.workspace.domain.engine

import com.omnidev.workspace.data.model.ToolCall
import com.omnidev.workspace.data.tools.ToolDefinition
import com.omnidev.workspace.data.tools.ToolExecutionResult
import kotlinx.serialization.json.*

/** Exact contract checks; no fuzzy dispatch, guessed arguments, or silent repairs. */
class ToolCallPreflight(definitions: List<ToolDefinition>) {
    private val catalog = definitions.associateBy { it.name }
    private val corrections = ToolCorrectionAdvisor(definitions)

    fun check(call: ToolCall): ToolExecutionResult? {
        val failure = checkExact(call)
        val report = corrections.review(call)
        if (failure == null && report.valid) return null
        // Preserve the router's separately owned candidate-loading control, never its raw payload.
        if (call.argumentError?.startsWith("OPERATION_UNRESOLVED:") == true) return failure
        val code = if (report.issues.any { it.kind == ToolContractIssueKind.UNKNOWN_TOOL }) "TOOL_NOT_EXPOSED"
            else failure?.classification ?: "INVALID_TOOL_ARGUMENTS"
        return denied(code, report.render())
    }

    private fun checkExact(call: ToolCall): ToolExecutionResult? {
        val definition = catalog[call.name]
            ?: return denied("TOOL_NOT_EXPOSED", buildString {
                append("Tool is not in this request's catalog. ")
                ToolTypoAdvisor.suggest(call.name, catalog.keys)?.let {
                    append("Possible exact exposed name: ").append(it).append(". ")
                }
                append("Use discover_tools to find and load an exact registered name.")
            })
        call.argumentError?.let { error ->
            if (error.startsWith("OPERATION_UNRESOLVED:")) {
                return ToolExecutionResult(
                    "$error No operation was executed; choose one of the returned candidates or clarify the intended action.",
                    true,
                    classification = "OPERATION_UNRESOLVED",
                    retryable = false
                )
            }
            return denied("INVALID_TOOL_ARGUMENTS", error)
        }
        val parameters = definition.parameters.associateBy { it.name }
        val unknown = call.arguments.keys - parameters.keys
        if (unknown.isNotEmpty()) return denied("INVALID_TOOL_ARGUMENTS", buildString {
            append("Unknown parameter(s): ").append(unknown.sorted().joinToString()).append(". ")
            if (unknown.size == 1) ToolTypoAdvisor.suggest(unknown.single(), parameters.keys)?.let {
                append("Possible exact parameter: ").append(it).append(". ")
            }
            append("Expected: ").append(parameters.keys.joinToString()).append(".")
        })
        val missing = definition.parameters.filter { (it.required || call.arguments["action"] in it.requiredForActions) && it.name !in call.arguments }.map { it.name }
        if (missing.isNotEmpty()) return denied("INVALID_TOOL_ARGUMENTS", "Missing required parameter(s): ${missing.joinToString()}.")
        for ((key, value) in call.arguments) {
            val parameter = parameters.getValue(key)
            if (key in setOf("action", "operation") && value.isBlank()) return denied("INVALID_TOOL_ARGUMENTS", "$key cannot be blank.")
            if (parameter.allowedValues.isNotEmpty() && value !in parameter.allowedValues) {
                return denied("INVALID_TOOL_ARGUMENTS", "$key must be one of: ${parameter.allowedValues.joinToString()}.")
            }
            val valid = when (parameter.type.lowercase()) {
                "integer" -> value.toLongOrNull() != null
                "number" -> value.toDoubleOrNull()?.isFinite() == true
                "boolean" -> value == "true" || value == "false"
                "object" -> parse(value) is JsonObject
                "array" -> parse(value) is JsonArray
                "string" -> true // Empty content is legitimate for file creation/clearing.
                else -> false
            }
            if (!valid) return denied("INVALID_TOOL_ARGUMENTS", "$key must have type ${parameter.type}.")
        }
        return null
    }

    /** Validate the entire response before any side effect; duplicate writes are not two intents. */
    fun checkBatch(calls: List<ToolCall>): List<ToolExecutionResult?> {
        val errors = calls.map(::check).toMutableList()
        val duplicateIds = calls.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys
        val mutations = mutableSetOf<Pair<String, Map<String, String>>>()
        calls.forEachIndexed { index, call ->
            if (call.id.isBlank() || call.id in duplicateIds) errors[index] = denied("INVALID_TOOL_BATCH", "Tool call IDs must be nonempty and unique.")
            if (!ToolBatchPolicy.isReadOnly(call) && !mutations.add(call.name to call.arguments)) {
                errors[index] = denied("INVALID_TOOL_BATCH", "Duplicate mutation in one response. Re-plan one verified action at a time.")
            }
        }
        if (calls.size > MAX_BATCH_SIZE) return calls.map { denied("INVALID_TOOL_BATCH", "At most $MAX_BATCH_SIZE calls may be submitted in one response.") }
        if (errors.any { it != null }) return errors.map { it ?: denied("BATCH_PREFLIGHT_BLOCKED", "Another call failed preflight. No calls in this batch were executed. Correct the batch before proceeding.") }
        return errors
    }

    private fun parse(raw: String): JsonElement? = try { Json.parseToJsonElement(raw) } catch (_: Exception) { null }
    private fun denied(code: String, detail: String) = ToolExecutionResult(
        "$code: $detail No action executed; correct the call using its advertised schema.", true,
        classification = code, retryable = false
    )

    companion object { const val MAX_BATCH_SIZE = 8 }
}
