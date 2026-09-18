package com.omnidev.workspace.data.tools

import com.omnidev.workspace.data.brain.ProgressiveTrustEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Read-only tooling for the Agent Brain execution-reliability profile.
 *
 * Important: this profile is advisory. It never grants God Mode, privileged execution, automatic
 * mode switching, or bypasses confirmation gates. Those remain controlled by explicit user policy.
 */
class ProgressiveTrustTool(
    private val trustEngine: ProgressiveTrustEngine
) {

    fun getDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "get_trust_profile",
            description = """
Read the local execution-reliability profile: evidence maturity, Bayesian reliability estimate,
operation counts, and non-authoritative familiarity badges. This is diagnostic/advisory data only;
it never grants permissions or bypasses confirmation.
""".trimIndent(),
            parameters = emptyList()
        ),
        ToolDefinition(
            name = "reset_trust",
            description = """
Reset the local execution-reliability learning profile. This clears learned reliability evidence
and familiarity badges only. It does not change user permissions, God Mode settings, or mode-switch
approvals. Requires confirm=true.
""".trimIndent(),
            parameters = listOf(
                ToolParameter(
                    name = "confirm",
                    type = "string",
                    description = "Must be 'true' to reset the reliability profile.",
                    required = true
                )
            )
        ),
        ToolDefinition(
            name = "list_earned_capabilities",
            description = """
Compatibility tool for viewing learned familiarity badges. file_write and terminal_access badges
mean the agent has accumulated execution experience; they are NOT permission grants. god_mode and
swarm_control are user-authority domains and can never be earned from trust score.
""".trimIndent(),
            parameters = listOf(
                ToolParameter(
                    name = "capability",
                    type = "string",
                    description = "Optional badge/authority name to inspect.",
                    required = false
                )
            )
        )
    )

    suspend fun execute(name: String, args: Map<String, String>): ToolExecutionResult? {
        if (name !in HANDLED) return null
        return try {
            withContext(Dispatchers.IO) {
                when (name) {
                    "get_trust_profile" -> doGetProfile()
                    "reset_trust" -> doResetTrust(args)
                    "list_earned_capabilities" -> doListCapabilities(args)
                    else -> ToolExecutionResult("Unknown trust tool: $name", isError = true)
                }
            }
        } catch (t: Throwable) {
            ToolExecutionResult(
                output = "ProgressiveTrustTool error: ${t.message}",
                isError = true,
                classification = "TRUST_PROFILE_ERROR",
                retryable = false
            )
        }
    }

    fun handles(name: String): Boolean = name in HANDLED

    private fun doGetProfile(): ToolExecutionResult = ToolExecutionResult(
        output = trustEngine.buildProfileSummary(),
        classification = "TRUST_PROFILE",
        verification = "read-only local reliability profile"
    )

    private fun doResetTrust(args: Map<String, String>): ToolExecutionResult {
        val confirm = args["confirm"]?.trim()?.lowercase()
        if (confirm != "true") {
            return ToolExecutionResult(
                output = "Reliability profile was not reset. Pass confirm=true to proceed. " +
                    "User permissions and authority settings are never affected by this operation.",
                isError = false,
                classification = "CONFIRMATION_REQUIRED"
            )
        }

        trustEngine.resetProfile()
        return ToolExecutionResult(
            output = "Execution-reliability profile reset. Authority/permission settings were unchanged.",
            isError = false,
            classification = "TRUST_PROFILE_RESET",
            verification = "profile returned to local defaults"
        )
    }

    private fun doListCapabilities(args: Map<String, String>): ToolExecutionResult {
        val requested = args["capability"]?.trim()?.lowercase()
        val profile = trustEngine.getProfile()

        if (!requested.isNullOrBlank()) {
            val familiarity = requested in profile.earnedCapabilities
            val advisoryEligibility = trustEngine.checkCapability(requested)
            val authorityControlled = requested in USER_AUTHORITY_CAPABILITIES
            return ToolExecutionResult(
                output = buildString {
                    appendLine("Capability/badge: $requested")
                    if (authorityControlled) {
                        appendLine("Type: user-controlled authority")
                        appendLine("Earnable from reliability score: no")
                        appendLine("Authorized by this trust engine: no")
                        appendLine("Use the dedicated explicit user permission/policy flow.")
                    } else {
                        appendLine("Type: advisory familiarity badge")
                        appendLine("Learned badge present: $familiarity")
                        appendLine("Current familiarity threshold met: $advisoryEligibility")
                        appendLine("Permission effect: none")
                    }
                    appendLine("Reliability score: ${"%.3f".format(profile.trustScore)}")
                },
                classification = "TRUST_BADGE_QUERY",
                verification = "read-only local profile lookup"
            )
        }

        return ToolExecutionResult(
            output = buildString {
                appendLine("Execution familiarity and authority")
                appendLine("──────────────────────────────────")
                appendLine("Reliability score: ${"%.3f".format(profile.trustScore)}")
                appendLine("Reliability level: ${trustEngine.getTrustLevel().label}")
                appendLine("Bayesian reliability: ${pct(trustEngine.reliabilityPosterior())}")
                appendLine("Evidence maturity: ${pct(trustEngine.evidenceMaturity())}")
                appendLine()
                appendLine("Advisory familiarity badges (permission effect: none):")
                appendFamiliarityRow(this, "file_write", profile.earnedCapabilities)
                appendFamiliarityRow(this, "terminal_access", profile.earnedCapabilities)
                appendLine()
                appendLine("Explicit user-controlled authority (never earned here):")
                appendLine("• god_mode — managed by user/tier policy")
                appendLine("• swarm_control / mode switching — managed by scoped user approvals")
                appendLine("• destructive confirmations — managed by confirmation policy")
            },
            classification = "TRUST_BADGE_LIST",
            verification = "read-only local profile lookup"
        )
    }

    private fun appendFamiliarityRow(
        builder: StringBuilder,
        capability: String,
        earned: Set<String>
    ) {
        val state = if (capability in earned) "experienced" else "learning"
        builder.appendLine("• $capability: $state (advisory only)")
    }

    private fun pct(value: Float): String =
        "${(value.coerceIn(0f, 1f) * 100f).toInt()}%"

    companion object {
        private val USER_AUTHORITY_CAPABILITIES = setOf("god_mode", "swarm_control")

        val HANDLED = setOf(
            "get_trust_profile",
            "reset_trust",
            "list_earned_capabilities"
        )
    }
}
