package com.omnidev.workspace.domain.engine

import com.omnidev.workspace.data.model.ToolCall
import com.omnidev.workspace.data.model.ToolCallResult

/**
 * Deterministic record of what tools actually did during one run.
 *
 * It is never summarized by a model, so it survives context compaction unchanged. It is used to
 * (1) pin ground truth into compacted context, (2) fact-check the draft final answer, and
 * (3) flag file names/paths in the final answer that no tool ever returned.
 */
class RunEvidenceLedger {

    enum class Outcome { OK, EMPTY, ERROR }

    data class Entry(
        val seq: Int,
        val tool: String,
        val target: String,
        val outcome: Outcome,
        val evidence: String
    )

    private val entries = ArrayList<Entry>()
    private val corpus = StringBuilder()
    private var seq = 0

    @Synchronized
    fun size(): Int = entries.size

    @Synchronized
    fun record(call: ToolCall, result: ToolCallResult) {
        val output = result.output
        val trimmed = output.trim()
        val outcome = when {
            result.isError -> Outcome.ERROR
            trimmed.isEmpty() || trimmed == "(no output)" || trimmed.startsWith("NO_MATCHES") -> Outcome.EMPTY
            else -> Outcome.OK
        }
        seq += 1
        entries += Entry(
            seq = seq,
            tool = call.name,
            target = describeArguments(call.arguments),
            outcome = outcome,
            evidence = trimmed.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().trim().take(140)
        )
        corpus.append('\n').append(call.name).append(' ')
        call.arguments.values.forEach { corpus.append(it.take(400)).append(' ') }
        corpus.append('\n').append(output.take(MAX_OUTPUT_IN_CORPUS))
        if (corpus.length > MAX_CORPUS_CHARS) corpus.delete(0, corpus.length - MAX_CORPUS_CHARS)
    }

    /** Model-facing block, pinned after compaction. Must start with "[RUN LEDGER". */
    @Synchronized
    fun render(maxOk: Int = 30, maxProblems: Int = 25): String {
        if (entries.isEmpty()) return ""
        val problems = entries.filter { it.outcome != Outcome.OK }.takeLast(maxProblems)
        val ok = entries.filter { it.outcome == Outcome.OK }
        val recentOk = ok.takeLast(maxOk)
        val olderOk = ok.dropLast(recentOk.size)
        val shown = (problems + recentOk).distinctBy { it.seq }.sortedBy { it.seq }
        return buildString {
            appendLine("[RUN LEDGER — deterministic record of tool calls that really ran in this run. Authoritative: never deny an action listed here, never claim an action that is not listed, never report EMPTY/ERROR as success.]")
            appendLine("Totals: ${entries.size} calls; OK=${entries.count { it.outcome == Outcome.OK }}, EMPTY=${entries.count { it.outcome == Outcome.EMPTY }}, ERROR=${entries.count { it.outcome == Outcome.ERROR }}")
            if (olderOk.isNotEmpty()) {
                val counts = olderOk.groupingBy { it.tool }.eachCount().entries
                    .sortedByDescending { it.value }.joinToString(", ") { "${it.key}×${it.value}" }
                appendLine("Earlier successful calls (details dropped): $counts")
            }
            shown.forEach { e ->
                append('#').append(e.seq).append(' ').append(e.tool)
                if (e.target.isNotBlank()) append(' ').append(e.target)
                append(" → ").append(e.outcome.name)
                if (e.evidence.isNotBlank()) append(": ").append(e.evidence)
                appendLine()
            }
        }.trimEnd()
    }

    /**
     * File names / absolute paths cited in [answer] that never appear in any tool call, tool output,
     * or in [extraEvidence] (user message and prior turns). Fenced code is ignored.
     */
    @Synchronized
    fun unverifiedReferences(answer: String, extraEvidence: String = ""): List<String> {
        val haystack = (corpus.toString() + "\n" + extraEvidence).lowercase()
        val text = answer.replace(FENCED_CODE, " ")
        val found = LinkedHashSet<String>()
        ABSOLUTE_PATH.findAll(text).forEach { found += it.value.trimEnd('.', ',', ')', ':') }
        FILE_NAME.findAll(text).forEach { m ->
            if (found.none { it.endsWith("/" + m.value) }) found += m.value
        }
        return found.filter { ref ->
            val base = ref.substringAfterLast('/').lowercase()
            base.length >= 3 && base !in haystack
        }.take(8)
    }

    /** User-visible note appended to the final answer only when something deserves a warning. */
    @Synchronized
    fun groundingNote(answer: String, extraEvidence: String = ""): String {
        val unverified = unverifiedReferences(answer, extraEvidence)
        val weak = entries.filter { it.outcome != Outcome.OK }
        if (unverified.isEmpty() && weak.isEmpty()) return ""
        return buildString {
            if (unverified.isNotEmpty()) {
                appendLine("Verification warning: these file names/paths were mentioned in the answer but did not appear in tool output; treat them as unverified: " +
                    unverified.joinToString(", ") { "`$it`" })
            }
            if (weak.isNotEmpty()) {
                val summary = weak.groupingBy { "${it.tool}:${it.outcome.name.lowercase()}" }.eachCount().entries
                    .joinToString(", ") { "${it.key}×${it.value}" }
                append("Tool calls with empty output or errors in this run: $summary")
            }
        }.trimEnd()
    }

    private fun describeArguments(arguments: Map<String, String>): String {
        val keys = listOf("action", "path", "filePath", "directory", "url", "repo", "command", "script", "shell_script", "query", "name_pattern", "name")
        return keys.mapNotNull { key -> arguments[key]?.takeIf(String::isNotBlank)?.let { "$key=${it.replace('\n', ' ').take(80)}" } }
            .take(3).joinToString(" ")
    }

    private companion object {
        const val MAX_CORPUS_CHARS = 400_000
        const val MAX_OUTPUT_IN_CORPUS = 60_000
        val FENCED_CODE = Regex("(?s)```.*?```")
        val ABSOLUTE_PATH = Regex("(?<![\\w:/.])/(?:[\\w.@+-]+/)+[\\w.@+-]+")
        val FILE_NAME = Regex("\\b[\\w-]+(?:\\.[\\w-]+)*\\.(?:kt|kts|java|xml|md|json|py|sh|yml|yaml|gradle|toml|properties|txt)\\b")
    }
}
