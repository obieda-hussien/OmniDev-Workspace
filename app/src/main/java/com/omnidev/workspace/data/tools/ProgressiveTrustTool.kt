package com.omnidev.workspace.data.tools

import com.omnidev.workspace.data.brain.ProgressiveTrustEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ══════════════════════════════════════════════════════════════════════════════
 * ProgressiveTrustTool — [Localized] [Localized] [Localized]
 * ══════════════════════════════════════════════════════════════════════════════
 *
 * [Localized] [ProgressiveTrustEngine] [Localized] [Localized] [Localized] [Localized]:
 *
 *   - **get_trust_profile**: [Localized] [Localized] [Localized] [Localized] (score[Localized] level[Localized] capabilities)
 *   - **reset_trust**: [Localized] [Localized] [Localized] [Localized] [Localized] ([Localized] [Localized])
 *   - **list_earned_capabilities**: [Localized] [Localized] [Localized] [Localized] [Localized]
 *
 * ## Mobile-First:
 * - [Localized] LLM[Localized] [Localized] DB — [Localized] [Localized] SharedPreferences [Localized]
 * - [Localized] [Localized] < 1ms
 */
class ProgressiveTrustTool(
    private val trustEngine: ProgressiveTrustEngine
) {

    // ──────────────────────────────────────────────────────────────────────────
    // Tool Definitions
    // ──────────────────────────────────────────────────────────────────────────

    fun getDefinitions(): List<ToolDefinition> = listOf(

        ToolDefinition(
            name = "get_trust_profile",
            description = """[Localized] [Localized] [Localized] [Localized] [Localized].
[Localized]:
- trustScore (0.0 → 1.0): [Localized] [Localized] [Localized]
- TrustLevel: NOVICE / TRUSTED / EXPERT / GUARDIAN
- [Localized] [Localized] [Localized] [Localized]
- [Localized] [Localized] (earned capabilities)
- [Localized] [Localized] [Localized]

[Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized].
""",
            parameters = emptyList()
        ),

        ToolDefinition(
            name = "reset_trust",
            description = """[Localized] [Localized] [Localized] [Localized] [Localized] [Localized].
⚠️ [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] — [Localized] [Localized] [Localized] [Localized] [Localized] [Localized].
[Localized] [Localized] confirm=true [Localized].
""",
            parameters = listOf(
                ToolParameter(
                    name = "confirm",
                    type = "string",
                    description = "[Localized] [Localized] [Localized] 'true' [Localized]. [Localized] [Localized] [Localized] [Localized] [Localized].",
                    required = true
                )
            )
        ),

        ToolDefinition(
            name = "list_earned_capabilities",
            description = """[Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized].
[Localized] [Localized]:
- file_write: [Localized] [Localized] [Localized] (trustScore >= 0.2)
- terminal_access: [Localized] [Localized] [Localized] (trustScore >= 0.3)
- god_mode: [Localized] [Localized] [Localized] (trustScore >= 0.8)
- swarm_control: [Localized] [Localized] [Localized] [Localized] (trustScore >= 0.9)

[Localized] [Localized] [Localized] [Localized] [Localized] [Localized].
""",
            parameters = listOf(
                ToolParameter(
                    name = "capability",
                    type = "string",
                    description = "[Localized] [Localized] [Localized] [Localized] [Localized] [Localized] ([Localized]). [Localized]: 'god_mode'",
                    required = false
                )
            )
        )
    )

    // ──────────────────────────────────────────────────────────────────────────
    // Execution
    // ──────────────────────────────────────────────────────────────────────────

    /** [Localized] null [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] wrapper. */
    suspend fun execute(name: String, args: Map<String, String>): ToolExecutionResult? {
        if (name !in HANDLED) return null
        return try {
            withContext(Dispatchers.IO) {
                when (name) {
                    "get_trust_profile"         -> doGetProfile()
                    "reset_trust"               -> doResetTrust(args)
                    "list_earned_capabilities"  -> doListCapabilities(args)
                    else -> ToolExecutionResult("Unknown trust tool: $name", isError = true)
                }
            }
        } catch (t: Throwable) {
            ToolExecutionResult("ProgressiveTrustTool error: ${t.message}", isError = true)
        }
    }

    fun handles(name: String): Boolean = name in HANDLED

    // ──────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ──────────────────────────────────────────────────────────────────────────

    private fun doGetProfile(): ToolExecutionResult {
        return ToolExecutionResult(trustEngine.buildProfileSummary())
    }

    private fun doResetTrust(args: Map<String, String>): ToolExecutionResult {
        val confirm = args["confirm"]?.trim()?.lowercase()
        if (confirm != "true") {
            return ToolExecutionResult(
                "⚠️ [Localized] [Localized] [Localized] [Localized] [Localized] — [Localized] confirm=true.\n" +
                "[Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized].",
                isError = false
            )
        }
        trustEngine.resetProfile()
        return ToolExecutionResult(
            "✅ [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized].\n" +
            "trustScore = 0.100 | NOVICE | No capabilities"
        )
    }

    private fun doListCapabilities(args: Map<String, String>): ToolExecutionResult {
        val specificCap = args["capability"]?.trim()
        val p = trustEngine.getProfile()
        val level = trustEngine.getTrustLevel()

        // [Localized] [Localized] [Localized] [Localized]
        if (!specificCap.isNullOrBlank()) {
            val isEarned = specificCap in p.earnedCapabilities
            val isAvailable = trustEngine.checkCapability(specificCap)
            return ToolExecutionResult(buildString {
                appendLine("🔍 [Localized] [Localized]: $specificCap")
                appendLine("   [Localized]: ${if (isEarned) "✅ [Localized]" else "❌ [Localized]"}")
                appendLine("   [Localized] [Localized] score [Localized]: ${if (isAvailable) "✅ [Localized]" else "❌ [Localized]"}")
                appendLine("   trustScore [Localized]: ${"%.3f".format(p.trustScore)}")
            })
        }

        // [Localized] [Localized]
        return ToolExecutionResult(buildString {
            appendLine("🏆 [Localized] [Localized] (Progressive Capabilities)")
            appendLine("Score [Localized]: ${"%.3f".format(p.trustScore)} | [Localized]: ${level.label}")
            appendLine()
            appendLine("[Localized]           | [Localized] | [Localized]")
            appendLine("─────────────────────────────────────")
            appendCapabilityRow(this, "file_write",      0.2f, p.trustScore, p.earnedCapabilities)
            appendCapabilityRow(this, "terminal_access", 0.3f, p.trustScore, p.earnedCapabilities)
            appendCapabilityRow(this, "god_mode",        0.8f, p.trustScore, p.earnedCapabilities)
            appendCapabilityRow(this, "swarm_control",   0.9f, p.trustScore, p.earnedCapabilities)
            appendLine()
            if (p.earnedCapabilities.isEmpty()) {
                appendLine("💡 [Localized] [Localized] [Localized] [Localized] [Localized] — [Localized] [Localized] [Localized] [Localized].")
            } else {
                appendLine("✅ [Localized] [Localized]: ${p.earnedCapabilities.joinToString(", ")}")
            }
        })
    }

    private fun appendCapabilityRow(
        sb: StringBuilder,
        cap: String,
        threshold: Float,
        score: Float,
        earned: Set<String>
    ) {
        val statusIcon = when {
            cap in earned             -> "✅ [Localized]"
            score >= threshold        -> "🔓 [Localized]"
            else -> {
                val remaining = threshold - score
                "🔒 [Localized] +${"%.3f".format(remaining)}"
            }
        }
        sb.appendLine("%-20s | %-6.1f | %s".format(cap, threshold, statusIcon))
    }

    companion object {
        val HANDLED = setOf(
            "get_trust_profile",
            "reset_trust",
            "list_earned_capabilities"
        )
    }
}
