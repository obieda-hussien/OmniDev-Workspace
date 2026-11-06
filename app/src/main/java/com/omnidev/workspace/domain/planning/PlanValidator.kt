package com.omnidev.workspace.domain.planning

import com.omnidev.workspace.core.policy.ConfirmationKind
import com.omnidev.workspace.domain.engine.IntentClassifier
import com.omnidev.workspace.domain.taskstate.NewStep

data class PlanIssue(val code: String, val stepIndex: Int? = null)
data class PlanValidation(val normalized: StructuredPlan?, val issues: List<PlanIssue>) {
    val valid get() = normalized != null && issues.isEmpty()
    fun diagnostic() = issues.take(6).joinToString(", ") { it.code + (it.stepIndex?.let { n -> " at step ${n + 1}" } ?: "") }.take(240)
}

/** Pure, bounded graph validation. Model output is never allowed to mutate the store partially. */
object PlanValidator {
    const val MAX_STEPS = 24
    private val key = Regex("[A-Za-z][A-Za-z0-9_-]{0,31}")
    fun graph(steps: List<NewStep>): List<PlanIssue> {
        val errors = arrayListOf<PlanIssue>()
        if (steps.size !in 1..MAX_STEPS) errors += PlanIssue("STEP_COUNT")
        val keys = steps.map { it.key }.toSet()
        if (keys.size != steps.size) errors += PlanIssue("DUPLICATE_KEY")
        steps.forEachIndexed { i, s ->
            if (!key.matches(s.key)) errors += PlanIssue("INVALID_KEY", i)
            if (s.description.isBlank() || s.description.length > 600) errors += PlanIssue("STEP_DESCRIPTION", i)
            if (s.dependsOnKeys.size > MAX_STEPS || s.dependsOnKeys.distinct().size != s.dependsOnKeys.size) errors += PlanIssue("DUPLICATE_DEPENDENCY", i)
            if (s.key in s.dependsOnKeys) errors += PlanIssue("SELF_DEPENDENCY", i)
            if (s.dependsOnKeys.any { it !in keys }) errors += PlanIssue("MISSING_DEPENDENCY", i)
        }
        if (errors.isEmpty() && order(steps) == null) errors += PlanIssue("DEPENDENCY_CYCLE")
        return errors.take(32)
    }

    /** Lexicographic tie-breaking makes equivalent DAGs normalize identically. */
    fun order(steps: List<NewStep>): List<String>? {
        val remaining = steps.associateBy { it.key }.toMutableMap()
        val result = arrayListOf<String>()
        while (remaining.isNotEmpty()) {
            val next = remaining.values.filter { it.dependsOnKeys.all(result::contains) }.minByOrNull { it.key } ?: return null
            result += next.key
            remaining.remove(next.key)
        }
        return result
    }

    fun validate(plan: StructuredPlan, mandatory: List<String> = emptyList()): PlanValidation {
        val errors = graph(plan.steps.map { it.newStep() }).toMutableList()
        fun text(value: String, max: Int, code: String, index: Int? = null) {
            if (value.isBlank() || value.length > max) errors += PlanIssue(code, index)
            if (!PlanningPrivacy.allowed(value)) errors += PlanIssue("UNSAFE_PLAN_TEXT", index)
        }
        text(plan.objective, 2_000, "OBJECTIVE")
        text(plan.summary, 600, "SUMMARY")
        if (plan.constraints.size > 16 || plan.constraints.distinct().size != plan.constraints.size) errors += PlanIssue("CONSTRAINT_COUNT")
        plan.constraints.forEach { text(it, 1_000, "CONSTRAINT_TEXT") }
        if (!plan.constraints.containsAll(mandatory)) errors += PlanIssue("MISSING_MANDATORY_CONSTRAINT")
        val descriptions = hashSetOf<String>()
        plan.steps.forEachIndexed { i, s ->
            text(s.description, 600, "STEP_DESCRIPTION", i)
            text(s.expectedOutput, 600, "EXPECTED_OUTPUT", i)
            text(s.completionCondition, 600, "COMPLETION_CONDITION", i)
            val semanticKey = s.description.lowercase().filter(Char::isLetterOrDigit)
            if (!descriptions.add(semanticKey)) errors += PlanIssue("DUPLICATE_STEP", i)
            if (s.policy.domains.size > 4 || s.policy.domains.distinct().size != s.policy.domains.size ||
                s.policy.domains.any { d -> IntentClassifier.ToolDomain.entries.none { it.name == d } }) errors += PlanIssue("INVALID_DOMAIN", i)
            if (s.policy.approvalKind != null && ConfirmationKind.entries.none { it.name == s.policy.approvalKind }) errors += PlanIssue("INVALID_APPROVAL", i)
            if (s.policy.contains.length > 200 || !PlanningPrivacy.allowed(s.policy.contains)) errors += PlanIssue("INVALID_PREDICATE", i)
            if (s.policy.proof != ProofKind.READ_RESULT && s.policy.contains.isBlank()) errors += PlanIssue("CHECK_PREDICATE_REQUIRED", i)
            if (s.policy.checkTool.length > 100 || (s.policy.checkTool.isNotBlank() && !Regex("[A-Za-z0-9_.-]+").matches(s.policy.checkTool))) errors += PlanIssue("INVALID_CHECK_TOOL", i)
            if (s.policy.proof == ProofKind.INDEPENDENT_CHECK && s.policy.checkTool.isBlank()) errors += PlanIssue("CHECK_TOOL_REQUIRED", i)
        }
        if (errors.isNotEmpty()) return PlanValidation(null, errors.distinct().take(32))
        val byKey = plan.steps.associateBy { it.key }
        return PlanValidation(plan.copy(steps = order(plan.steps.map { it.newStep() })!!.map { byKey.getValue(it) }), emptyList())
    }
}
