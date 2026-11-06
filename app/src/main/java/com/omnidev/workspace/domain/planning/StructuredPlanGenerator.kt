package com.omnidev.workspace.domain.planning

import com.omnidev.workspace.data.model.*
import com.omnidev.workspace.domain.engine.TokenAccounting
import com.omnidev.workspace.domain.engine.WholeResponseEnvelope
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.Serializable

@Serializable
data class PlanningInput(
    val objective: String,
    val originalRequest: String,
    val mandatoryConstraints: List<String>,
    val conversation: List<String>,
    val capabilities: List<String>,
    val previousPlan: StructuredPlan? = null,
    val completedKeys: List<String> = emptyList(),
    val recentFailures: List<String> = emptyList(),
    val reason: String = "Initial decomposition"
)

data class PlanGeneration(val plan: StructuredPlan?, val tokens: Int, val diagnostic: String)
fun interface StructuredPlanGenerator { suspend fun generate(input: PlanningInput, budget: Int?): PlanGeneration }

/** CompletionRequest has no native response-schema option. Use its bounded JSON fallback, then validate. */
class ModelStructuredPlanGenerator(
    private val prototype: CompletionRequest,
    private val complete: suspend (CompletionRequest) -> CompletionResponse,
    private val contextWindow: Int,
    private val availableTools: Set<String>? = null
) : StructuredPlanGenerator {
    override suspend fun generate(input: PlanningInput, budget: Int?): PlanGeneration {
        var spent = 0
        var feedback = ""
        val payload = PlanCodec.json.encodeToString(input)
        if (payload.length > 24_000) return PlanGeneration(null, 0, "PLANNING_CONTEXT_LIMIT")
        repeat(2) {
            val draft = prototype.copy(messages = listOf(ChatMessage(MessageRole.USER, payload + feedback)),
                systemPrompt = SYSTEM, tools = null, enableThinking = false, onReasoning = null,
                maxTokens = minOf(prototype.maxTokens, 2_400), retryManagedByCaller = true)
            val inputTokens = TokenAccounting.estimateInputTokens(draft)
            val remaining = budget?.minus(spent)
            val outputBudget = minOf(draft.maxTokens, contextWindow - inputTokens - 128,
                remaining?.minus(inputTokens) ?: Int.MAX_VALUE)
            if (outputBudget < 384) return PlanGeneration(null, spent, "PLANNING_TOKEN_BUDGET")
            val request = draft.copy(maxTokens = outputBudget)
            val response = try { complete(request) } catch (c: CancellationException) { throw c } catch (_: Exception) {
                return PlanGeneration(null, spent + inputTokens, "PLANNING_PROVIDER_UNAVAILABLE")
            }
            spent += TokenAccounting.usage(request, response).totalTokens
            val plan = if (response.toolCalls.isEmpty() && response.content.length <= PlanCodec.MAX_JSON)
                runCatching { PlanCodec.json.decodeFromString<StructuredPlan>(WholeResponseEnvelope.normalize(response.content)) }.getOrNull() else null
            val validation = plan?.let { PlanValidator.validate(it, input.mandatoryConstraints, availableTools) }
            if (validation?.valid == true) return PlanGeneration(validation.normalized, spent, "")
            feedback = "\nValidation errors: ${validation?.diagnostic() ?: "MALFORMED_JSON"}. Return a corrected JSON object only."
        }
        return PlanGeneration(null, spent, "PLAN_GENERATION_INVALID")
    }

    companion object {
        const val SYSTEM = """OMNI_STRUCTURED_PLAN_V1
Return ONLY a JSON object. No tools, commands, credentials, personal data or private reasoning. Summarize operational intent only.
Schema: {"objective":"short objective","summary":"short operational explanation","constraints":["copy every mandatory constraint exactly"],"steps":[{"key":"S1","description":"work to do","dependsOnKeys":[],"expectedOutput":"concrete result","completionCondition":"checkable condition","policy":{"domains":["CORE"],"proof":"READ_RESULT","checkTool":"","contains":"","approvalKind":null}}]}.
Use 1-24 meaningful steps with stable ASCII keys and acyclic dependencies. Preserve completed steps and their entire contracts exactly; introduce a new key for additional work. Never emit status, evidence, task ids or authorization grants.
Use only capability domains supplied in the input. READ_RESULT requires actual read-only evidence. Group each mutation AND its verification inside the same step: READ_BACK needs later exact-path read-back and a nonempty contains predicate checking requested content. For other actions, INDEPENDENT_CHECK needs a named read-only checkTool and a nonempty contains predicate proving the final business state. Transport success, a sent intent, file existence and a model claim are not proof. A later dependent verification step cannot unlock an unverified mutation.
approvalKind may name an existing ConfirmationKind only when needed; it never grants permission or replaces actual tool policy. Retain all mandatory constraints. For constrained output return a smaller valid plan, never an incomplete JSON object.
"""
    }
}
