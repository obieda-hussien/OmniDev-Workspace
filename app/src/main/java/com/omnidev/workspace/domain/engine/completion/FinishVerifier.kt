package com.omnidev.workspace.domain.engine.completion

import com.omnidev.workspace.data.tools.ToolParameter
import com.omnidev.workspace.data.tools.ToolDefinition
import com.omnidev.workspace.domain.engine.RunEvidenceLedger.Outcome

/** The control tool. It is intercepted by the runtime and never reaches ToolManager. */
object FinishTool {
    const val NAME = "finish_task"

    val DEFINITION = ToolDefinition(
        NAME,
        "End the task. The runtime checks your claim against real tool results; 'completed' without matching evidence is rejected. Call it alone, never together with other tools.",
        listOf(
            ToolParameter("status", "string", "completed | blocked | failed | awaiting_approval | incomplete", required = true,
                allowedValues = FinishStatus.entries.map { it.wire }),
            ToolParameter("summary", "string", "Result for the user in the user's language: what was done, what was not, exact errors.", required = true),
            ToolParameter("evidence", "string", "Ids from RUN STATE LEDGER that prove the work, comma separated.", required = false),
            ToolParameter("criteria", "string", "One line per acceptance item: 'A1 => id1,id2'. Required when RUN STATE lists acceptance items.", required = false)
        )
    )

    fun parse(arguments: Map<String, String>): FinishRequest? {
        val status = FinishStatus.parse(arguments["status"]) ?: return null
        return FinishRequest(
            status = status,
            summary = arguments["summary"].orEmpty().trim(),
            evidenceIds = splitIds(arguments["evidence"].orEmpty()),
            claims = parseClaims(arguments["criteria"].orEmpty())
        )
    }

    internal fun splitIds(raw: String): List<String> =
        raw.split(Regex("[,;\\s]+")).map { it.trim().trim('[', ']', '"', '\'', '`') }.filter { it.isNotBlank() }.distinct()

    internal fun parseClaims(raw: String): List<CriterionClaim> = raw.lineSequence().mapNotNull { line ->
        val text = line.trim().trimStart('-', '*', '•', ' ')
        if (text.isBlank()) return@mapNotNull null
        val cut = Regex("=>|->|::|\\|").find(text)
        val left = (cut?.let { text.substring(0, it.range.first) } ?: text).trim()
        val right = cut?.let { text.substring(it.range.last + 1) }.orEmpty()
        val key = Regex("^[Aa](\\d+)\\b").find(left)?.let { "A" + it.groupValues[1] } ?: ""
        CriterionClaim(key, left.take(200), splitIds(right))
    }.toList()
}

data class FinishRequest(
    val status: FinishStatus,
    val summary: String,
    val evidenceIds: List<String> = emptyList(),
    val claims: List<CriterionClaim> = emptyList()
)

data class VerificationContext(
    val runId: String,
    val workerId: String?,
    val evidence: List<EvidenceRef>,
    val requireExecution: Boolean,
    /** Task acceptance items; they are numbered A1..An in order. */
    val taskCriteria: List<String> = emptyList(),
    val requireAction: Boolean = false
)

data class Evaluation(
    val accepted: Boolean,
    val results: List<CriterionResult>,
    val problems: List<String>
)

/** Pure checks of a finish claim against runtime-produced evidence. No model output is trusted here. */
object FinishVerifier {

    fun evaluate(ctx: VerificationContext, request: FinishRequest): Evaluation {
        val problems = ArrayList<String>()
        if (request.summary.isBlank()) problems += "summary is empty; state what was done or why you stopped"
        if (request.status != FinishStatus.COMPLETED) {
            return Evaluation(problems.isEmpty(), emptyList(), problems)
        }

        val results = ArrayList<CriterionResult>()
        val byId = HashMap<String, EvidenceRef>()
        ctx.evidence.forEach { ref -> byId[ref.citeId] = ref; byId["#" + ref.seq] = ref }

        // 1. Every cited id must be a real, successful entry of THIS run (and worker).
        val cited = (request.evidenceIds + request.claims.flatMap { it.evidenceIds }).distinct()
        val invalid = cited.mapNotNull { raw ->
            val ref = byId[normalize(raw)] ?: return@mapNotNull "$raw (unknown id)"
            when {
                ref.runId != ctx.runId || ref.workerId != ctx.workerId -> "$raw (belongs to another run)"
                ref.outcome != Outcome.OK -> "$raw (${ref.tool} ended ${ref.outcome.name}, not OK)"
                else -> null
            }
        }
        if (invalid.isNotEmpty()) problems += "invalid evidence: " + invalid.joinToString("; ")

        val ok = ctx.evidence.filter { it.outcome == Outcome.OK && it.runId == ctx.runId && it.workerId == ctx.workerId }

        // 2. Runtime-owned baseline criteria.
        if (ctx.requireExecution) {
            val met = ok.isNotEmpty()
            results += CriterionResult(AcceptanceCriterion("R1", "At least one tool call really succeeded", AcceptanceCriterion.Source.RUNTIME), met,
                ok.takeLast(3).map { it.citeId }, if (met) null else "no successful tool call exists in this run")
            if (!met) problems += "no successful tool call exists in this run, so nothing was actually executed"
        }
        if (ctx.requireAction) {
            val acted = ok.filter { it.kind != EvidenceKinds.Kind.READ }
            results += CriterionResult(AcceptanceCriterion("R4", "At least one successful non-read action (implementation work)", AcceptanceCriterion.Source.RUNTIME),
                acted.isNotEmpty(), acted.takeLast(3).map { it.citeId }, if (acted.isNotEmpty()) null else "only reads succeeded")
            if (acted.isEmpty()) problems += "this is implementation work but only read calls succeeded, so nothing was changed or run"
        }
        val open = openErrors(ctx.evidence)
        results += CriterionResult(AcceptanceCriterion("R2", "No unresolved tool failure", AcceptanceCriterion.Source.RUNTIME), open.isEmpty(),
            open.map { it.citeId }, if (open.isEmpty()) null else "unresolved: " + open.joinToString { "${it.citeId} ${it.tool}" })
        if (open.isNotEmpty()) problems += "unresolved failures: " + open.joinToString { "${it.citeId} (${it.tool})" } + "; fix or report them as blocked/failed"
        val unread = unverifiedMutations(ctx.evidence)
        results += CriterionResult(AcceptanceCriterion("R3", "Edits were read back", AcceptanceCriterion.Source.RUNTIME), unread.isEmpty(),
            unread.map { it.citeId }, if (unread.isEmpty()) null else "not read back: " + unread.joinToString { it.target.ifBlank { it.tool } })
        if (unread.isNotEmpty()) problems += "changed but never read back: " + unread.joinToString { "${it.citeId} ${it.tool} ${it.target}".trim() }

        // 3. Task acceptance items must each be claimed with valid evidence.
        ctx.taskCriteria.forEachIndexed { index, text ->
            val key = "A" + (index + 1)
            val claim = request.claims.firstOrNull { it.key == key }
            val valid = claim?.evidenceIds.orEmpty().filter { id -> byId[normalize(id)]?.let { it.outcome == Outcome.OK && it.runId == ctx.runId && it.workerId == ctx.workerId } == true }
            val met = claim != null && valid.isNotEmpty()
            results += CriterionResult(AcceptanceCriterion(key, text, AcceptanceCriterion.Source.TASK), met, valid,
                if (met) null else if (claim == null) "not claimed" else "no valid evidence")
            if (!met) problems += "acceptance item $key has no valid evidence"
        }

        // 4. Extra criteria the model declared must be backed too.
        request.claims.filter { it.key.isBlank() }.forEachIndexed { i, claim ->
            val valid = claim.evidenceIds.filter { id -> byId[normalize(id)]?.outcome == Outcome.OK }
            val met = valid.isNotEmpty()
            results += CriterionResult(AcceptanceCriterion("M${i + 1}", claim.text, AcceptanceCriterion.Source.MODEL), met, valid,
                if (met) null else "no valid evidence")
            if (!met) problems += "declared criterion '${claim.text.take(60)}' cites no valid evidence"
        }
        return Evaluation(problems.isEmpty(), results, problems.distinct())
    }

    /** An ERROR is open unless a later call to the same tool succeeded. EMPTY/SKIPPED never block. */
    fun openErrors(evidence: List<EvidenceRef>): List<EvidenceRef> = evidence.filterIndexed { index, e ->
        e.outcome == Outcome.ERROR && evidence.drop(index + 1).none { it.tool == e.tool && it.outcome == Outcome.OK }
    }

    /** Last successful mutation per path that has no later successful read of the same path (or any pathless read). */
    fun unverifiedMutations(evidence: List<EvidenceRef>): List<EvidenceRef> {
        val lastByPath = LinkedHashMap<String, EvidenceRef>()
        evidence.filter { it.kind == EvidenceKinds.Kind.MUTATION && it.outcome == Outcome.OK }
            .forEach { lastByPath[EvidenceKinds.pathOf(it.target) ?: "*"] = it }
        return lastByPath.values.filter { m ->
            val path = EvidenceKinds.pathOf(m.target)
            // Unknown targets are unverifiable: an unrelated or pathless read is not proof.
            path == null || evidence.none { r ->
                r.seq > m.seq && r.kind == EvidenceKinds.Kind.READ && r.outcome == Outcome.OK &&
                    EvidenceKinds.pathOf(r.target) == path
            }
        }
    }

    private fun normalize(raw: String) = raw.trim().trim('[', ']', '"', '\'', '`').let {
        if (it.all(Char::isDigit) && it.isNotEmpty()) "#$it" else it
    }
}
