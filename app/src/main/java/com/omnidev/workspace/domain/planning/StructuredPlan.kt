package com.omnidev.workspace.domain.planning

import com.omnidev.workspace.domain.engine.SensitiveObservationRedactor
import com.omnidev.workspace.domain.taskstate.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class ProofKind { READ_RESULT, READ_BACK, INDEPENDENT_CHECK }

/** A checkable output predicate, not a model's declaration that an action worked. */
@Serializable
data class StepPolicy(
    val domains: List<String> = emptyList(),
    val proof: ProofKind = ProofKind.READ_RESULT,
    val checkTool: String = "",
    val contains: String = "",
    val approvalKind: String? = null
)

@Serializable
data class PlannedStep(
    val key: String,
    val description: String,
    val dependsOnKeys: List<String> = emptyList(),
    val expectedOutput: String,
    val completionCondition: String,
    val policy: StepPolicy = StepPolicy()
) {
    fun newStep() = NewStep(key, description, dependsOnKeys, expectedOutput, completionCondition)
}

@Serializable
data class StructuredPlan(
    val objective: String,
    val summary: String,
    val constraints: List<String> = emptyList(),
    val steps: List<PlannedStep>
)

/** Only metadata absent from P1's existing step rows. No second copy of the execution plan. */
@Serializable
data class PlanMetadata(
    val version: Int = 1,
    val objective: String,
    val summary: String,
    val constraints: List<String>,
    val policies: Map<String, StepPolicy>,
    /** Runtime-owned identity of the user objective, never supplied by a model. */
    val sourceObjectiveDigest: String
)

object PlanCodec {
    val json = Json { encodeDefaults = true; ignoreUnknownKeys = false; isLenient = false }
    const val MAX_JSON = 40_000
    fun metadata(raw: String): PlanMetadata? = if (raw.isBlank() || raw.length > MAX_JSON) null else
        runCatching { json.decodeFromString<PlanMetadata>(raw) }.getOrNull()?.takeIf { it.version == 1 }
    fun metadata(plan: StructuredPlan, source: String) = json.encodeToString(PlanMetadata(
        objective = plan.objective, summary = plan.summary, constraints = plan.constraints,
        policies = plan.steps.associate { it.key to it.policy }, sourceObjectiveDigest = TaskRunRecorder.sha(source)
    ))
    fun restore(metadata: PlanMetadata, steps: List<StepRecord>) = StructuredPlan(
        metadata.objective, metadata.summary, metadata.constraints,
        steps.map { PlannedStep(it.key, it.description, it.dependsOnKeys, it.expectedOutput, it.completionCondition,
            metadata.policies[it.key] ?: StepPolicy()) }
    )
}

/** Plan context and diagnostics never contain raw observations, credentials or command payloads. */
object PlanningPrivacy {
    private val email = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val number = Regex("(?<![A-Za-z0-9])\\+?[0-9][0-9 ()-]{7,}[0-9](?![A-Za-z0-9])")
    private val opaqueSecret = Regex("(?:gh[pousr]_[A-Za-z0-9]+|sk-[A-Za-z0-9_-]{12,}|-----BEGIN [A-Z ]*PRIVATE KEY)")
    private val payload = Regex("(?im)(?:\\$\\(|&&|```|^\\s*(?:curl|wget|bash|powershell|rm|python[0-9]?)\\s+|https?://[^/\\s]+@)")
    fun safe(text: String) = SensitiveObservationRedactor.redact(text).replace(email, "[private address]")
        .replace(number, "[private number]").replace(opaqueSecret, "[private credential]")
    fun allowed(text: String) = safe(text) == text && !payload.containsMatchIn(text) &&
        !text.contains("<think", true) && !text.contains("<analysis", true)
}

object PlanningTrigger {
    /** One atomic edit with read-back need not pay for another model call. */
    fun complex(objective: String, acceptance: List<String> = emptyList()): Boolean = acceptance.size >= 3 ||
        objective.lineSequence().count { it.trimStart().let { line ->
            line.startsWith("- ") || line.startsWith("* ") || Regex("^[0-9]+[.)] ").containsMatchIn(line)
        } } >= 3 || (objective.length >= 280 &&
            com.omnidev.workspace.domain.engine.IntentClassifier.analyze(objective).complexity >= .45f)

    fun constraints(objective: String, acceptance: List<String>): List<String> {
        val explicit = objective.lineSequence().map(String::trim).filter {
            Regex("(?i)\\b(must|never|without|preserve|keep|only|do not|don't)\\b|بدون|ممنوع|لا تحذف").containsMatchIn(it)
        }.map(PlanningPrivacy::safe).toList()
        return (acceptance.map(PlanningPrivacy::safe) + explicit).distinct()
    }
}
