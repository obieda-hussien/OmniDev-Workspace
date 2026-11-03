package com.omnidev.workspace.domain.engine

import com.omnidev.workspace.data.model.ToolCall
import com.omnidev.workspace.data.model.ToolCallResult
import com.omnidev.workspace.domain.engine.completion.EvidenceKinds
import com.omnidev.workspace.domain.engine.completion.EvidenceRef

/**
 * Deterministic record of what tools actually did during one run.
 *
 * It is never summarized by a model, so it survives context compaction unchanged. It is used to
 * (1) pin ground truth into compacted context, (2) fact-check the draft final answer, and
 * (3) flag file names/paths in the final answer that no tool ever returned.
 */
class RunEvidenceLedger(
    /** Identity stamped on every entry; evidence from another run can never match this ledger. */
    val runId: String = "",
    val workerId: String? = null
) {
    /**
     * The finish runtime of this run. Reached through the ledger so AgentPipeline's flow lambda keeps no
     * extra long-lived local (every one is saved and restored at each suspension point).
     */
    lateinit var fin: com.omnidev.workspace.domain.engine.completion.FinishRuntime

    enum class Outcome { OK, EMPTY, ERROR, SKIPPED }

    data class Entry(
        val seq: Int,
        val tool: String,
        val target: String,
        val outcome: Outcome,
        val evidence: String,
        // Added for the finish protocol. Defaults keep every existing constructor call valid.
        val callId: String = "",
        val kind: EvidenceKinds.Kind = EvidenceKinds.Kind.OTHER
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
        val wasNotExecuted = result.isError && (
            trimmed.startsWith("Skipped:", ignoreCase = true) ||
                trimmed.startsWith("Skipped retry", ignoreCase = true) ||
                listOf(
                    "TOOL_NOT_EXPOSED",
                    "INVALID_TOOL_ARGUMENTS",
                    "INVALID_TOOL_BATCH",
                    "BATCH_PREFLIGHT_BLOCKED",
                    "OPERATION_UNRESOLVED"
                ).any { marker -> trimmed.contains(marker, ignoreCase = true) }
            )
        val outcome = when {
            wasNotExecuted -> Outcome.SKIPPED
            result.isError -> Outcome.ERROR
            trimmed.isEmpty() || trimmed == "(no output)" || trimmed.startsWith("NO_MATCHES") -> Outcome.EMPTY
            else -> Outcome.OK
        }
        seq += 1
        entries += Entry(
            seq = seq,
            tool = call.name,
            target = describeArguments(call.name, call.arguments),
            outcome = outcome,
            evidence = trimmed.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().trim().take(140),
            callId = call.id,
            kind = EvidenceKinds.classify(call.name, call.arguments)
        )
        // Only successful, non-empty tool output can confirm a file/path. Error and empty
        // outputs remain in the ledger but cannot validate a claimed read or listing.
        if (outcome == Outcome.OK) {
            corpus.append('\n').append(output.take(MAX_OUTPUT_IN_CORPUS))
            val entry = entries.last()
            if (entry.kind == EvidenceKinds.Kind.READ) {
                EvidenceKinds.pathOf(entry.target)?.let { path -> corpus.append('\n').append(path) }
            }
        }
        if (corpus.length > MAX_CORPUS_CHARS) corpus.delete(0, corpus.length - MAX_CORPUS_CHARS)
    }

    /** Immutable, runtime-produced view of every entry; the only evidence a finish request can cite. */
    @Synchronized
    fun snapshot(): List<EvidenceRef> = entries.map { e ->
        EvidenceRef(e.callId, e.seq, e.tool, e.target, e.outcome, e.kind, runId, workerId)
    }

    /**
     * Compact id-bearing view for the per-turn RUN STATE block: the ids a model may cite, newest last,
     * plus unresolved problems. Unlike [render] it is sized for every request, not just compaction.
     */
    @Synchronized
    fun renderCitable(maxLines: Int = 10): String {
        if (entries.isEmpty()) return "(no tool has run yet)"
        val problems = entries.filter { it.outcome == Outcome.ERROR || it.outcome == Outcome.EMPTY }
            .takeLast(maxLines / 2)
        val recent = entries.takeLast(maxLines)
        val shown = (problems + recent).distinctBy { it.seq }.sortedBy { it.seq }
        return buildString {
            appendLine("${entries.size} calls: OK=${entries.count { it.outcome == Outcome.OK }}, EMPTY=${entries.count { it.outcome == Outcome.EMPTY }}, ERROR=${entries.count { it.outcome == Outcome.ERROR }}, SKIPPED=${entries.count { it.outcome == Outcome.SKIPPED }}")
            shown.forEach { e ->
                append(e.callId.ifBlank { "#" + e.seq }).append(' ').append(e.tool)
                if (e.target.isNotBlank()) append(' ').append(e.target)
                append(" -> ").append(e.outcome.name)
                if (e.evidence.isNotBlank()) append(": ").append(e.evidence.take(90))
                appendLine()
            }
        }.trimEnd()
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
            appendLine("Totals: ${entries.size} calls; OK=${entries.count { it.outcome == Outcome.OK }}, EMPTY=${entries.count { it.outcome == Outcome.EMPTY }}, ERROR=${entries.count { it.outcome == Outcome.ERROR }}, SKIPPED=${entries.count { it.outcome == Outcome.SKIPPED }}")
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
     * File names / absolute paths cited in [answer] that do not appear in successful, non-empty
     * tool output or in [extraEvidence] supplied from other successful tool observations.
     * User requests, assistant text and failed/empty tool results are never proof. Fenced code is ignored.
     */
    @Synchronized
    fun unverifiedReferences(
        answer: String,
        extraEvidence: List<ToolCallResult> = emptyList()
    ): List<String> {
        val supplementalEvidence = extraEvidence.asSequence()
            .filter { result ->
                !result.isError && result.output.isNotBlank() &&
                    result.output.trim() != "(no output)" &&
                    !result.output.trim().startsWith("NO_MATCHES")
            }
            .joinToString("\n") { it.output }
            .take(300_000)
        val haystack = (corpus.toString() + "\n" + supplementalEvidence).lowercase()
        val text = answer.replace(FENCED_CODE, " ")
        val found = LinkedHashSet<String>()
        ABSOLUTE_PATH.findAll(text).forEach { found += it.value.trimEnd('.', ',', ')', ':') }
        FILE_NAME.findAll(text).forEach { m ->
            if (found.none { it.endsWith("/" + m.value) }) found += m.value
        }
        return found.filterNot { ref ->
            val normalized = ref.lowercase()
            if (ref.startsWith("/")) {
                // Require token boundaries too: Foo.kt.backup must not verify Foo.kt.
                Regex("(?<![\\w.-])${Regex.escape(normalized)}(?![\\w.-])")
                    .containsMatchIn(haystack)
            } else {
                val base = normalized.substringAfterLast('/')
                base.length >= 3 && Regex(
                    "(?<![\\w.-])${Regex.escape(base)}(?![\\w.-])"
                ).containsMatchIn(haystack)
            }
        }.take(8)
    }

    /** User-visible note appended to the final answer only when something deserves a warning. */
    @Synchronized
    fun groundingNote(
        answer: String,
        extraEvidence: List<ToolCallResult> = emptyList()
    ): String {
        val unverified = unverifiedReferences(answer, extraEvidence)
        val weak = unresolvedProblems()
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

    /**
     * A later successful call to the same tool means its earlier transient failure was recovered.
     * Calls rejected before execution and actions skipped after steering are not execution failures.
     */
    private fun unresolvedProblems(): List<Entry> = entries.filterIndexed { index, entry ->
        if (entry.outcome != Outcome.EMPTY && entry.outcome != Outcome.ERROR) {
            false
        } else {
            entries.drop(index + 1).none { later ->
                later.tool == entry.tool && later.outcome == Outcome.OK
            }
        }
    }

    private fun describeArguments(toolName: String, arguments: Map<String, String>): String {
        // Keep only low-risk identifiers; raw commands, URLs and free-form queries can carry credentials.
        val explicitPath = listOf("path", "filePath", "file_path", "filepath", "directory", "file", "filename", "output_path")
            .firstNotNullOfOrNull { key -> arguments[key]?.takeIf(String::isNotBlank) }
        val path = (explicitPath ?: EvidenceKinds.explicitMutationTarget(toolName, arguments)
            ?: EvidenceKinds.commandTarget(toolName, arguments))?.let {
            "path=${SensitiveObservationRedactor.redact(it.replace('\n', ' ')).take(120)}"
        }
        val metadata = listOf("action", "repo", "name_pattern", "name").mapNotNull { key ->
            arguments[key]?.takeIf(String::isNotBlank)?.let {
                "$key=${SensitiveObservationRedactor.redact(it.replace('\n', ' ')).take(80)}"
            }
        }
        return (listOfNotNull(path) + metadata).take(4).joinToString(" ")
    }

    private companion object {
        const val MAX_CORPUS_CHARS = 400_000
        const val MAX_OUTPUT_IN_CORPUS = 60_000
        val FENCED_CODE = Regex("(?s)```.*?```")
        val ABSOLUTE_PATH = Regex("(?<![\\w:/.])/(?:[\\w.@+-]+/)+[\\w.@+-]+")
        val FILE_NAME = Regex("\\b[\\w-]+(?:\\.[\\w-]+)*\\.(?:kt|kts|java|xml|md|json|py|sh|yml|yaml|gradle|toml|properties|txt)\\b")
    }
}
