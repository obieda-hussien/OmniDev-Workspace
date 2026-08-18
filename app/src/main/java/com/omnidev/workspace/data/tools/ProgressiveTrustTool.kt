package com.omnidev.workspace.data.tools

import com.omnidev.workspace.data.brain.ProgressiveTrustEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ══════════════════════════════════════════════════════════════════════════════
 * ProgressiveTrustTool — Context note Context note Context note
 * ══════════════════════════════════════════════════════════════════════════════
 *
 * Context note [ProgressiveTrustEngine] Context note Context note Context note Context note:
 *
 *   - **get_trust_profile**: Context note Context note Context note Context note (scoreContext note levelContext note capabilities)
 *   - **reset_trust**: Context note Context note Context note Context note Context note (Context note Context note)
 *   - **list_earned_capabilities**: Context note Context note Context note Context note Context note
 *
 * ## Mobile-First:
 * - Context note LLMContext note Context note DB — Context note Context note SharedPreferences Context note
 * - Context note Context note < 1ms
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
            description = """Info Info Info Info Info.
Info:
- trustScore (0.0 → 1.0): Info Info Info
- TrustLevel: NOVICE / TRUSTED / EXPERT / GUARDIAN
- Info Info Info Info
- Info Info (earned capabilities)
- Info Info Info

Info Info Info Info Info Info Info.
""",
            parameters = emptyList()
        ),

        ToolDefinition(
            name = "reset_trust",
            description = """Info Info Info Info Info Info.
⚠️ Info Info Info Info Info Info — Info Info Info Info Info Info.
Info Info confirm=true Info.
""",
            parameters = listOf(
                ToolParameter(
                    name = "confirm",
                    type = "string",
                    description = "Info Info Info 'true' Info. Info Info Info Info Info.",
                    required = true
                )
            )
        ),

        ToolDefinition(
            name = "list_earned_capabilities",
            description = """Info Info Info Info Info Info Info.
Info Info:
- file_write: Info Info Info (trustScore >= 0.2)
- terminal_access: Info Info Info (trustScore >= 0.3)
- god_mode: Info Info Info (trustScore >= 0.8)
- swarm_control: Info Info Info Info (trustScore >= 0.9)

Info Info Info Info Info Info.
""",
            parameters = listOf(
                ToolParameter(
                    name = "capability",
                    type = "string",
                    description = "Info Info Info Info Info Info (Info). Info: 'god_mode'",
                    required = false
                )
            )
        )
    )

    // ──────────────────────────────────────────────────────────────────────────
    // Execution
    // ──────────────────────────────────────────────────────────────────────────

    /** Context note null Context note Context note Context note Context note Context note Context note wrapper. */
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
                "⚠️ Info Info Info Info Info — Info confirm=true.\n" +
                "Info Info Info Info Info Info Info Info.",
                isError = false
            )
        }
        trustEngine.resetProfile()
        return ToolExecutionResult(
            "✅ Info Info Info Info Info Info Info.\n" +
            "trustScore = 0.100 | NOVICE | No capabilities"
        )
    }

    private fun doListCapabilities(args: Map<String, String>): ToolExecutionResult {
        val specificCap = args["capability"]?.trim()
        val p = trustEngine.getProfile()
        val level = trustEngine.getTrustLevel()

        // Context note Context note Context note Context note
        if (!specificCap.isNullOrBlank()) {
            val isEarned = specificCap in p.earnedCapabilities
            val isAvailable = trustEngine.checkCapability(specificCap)
            return ToolExecutionResult(buildString {
                appendLine("🔍 Info Info: $specificCap")
                appendLine("   Info: ${if (isEarned) "✅ Info" else "❌ Info"}")
                appendLine("   Info Info score Info: ${if (isAvailable) "✅ Info" else "❌ Info"}")
                appendLine("   trustScore Info: ${"%.3f".format(p.trustScore)}")
            })
        }

        // Context note Context note
        return ToolExecutionResult(buildString {
            appendLine("🏆 Info Info (Progressive Capabilities)")
            appendLine("Score Info: ${"%.3f".format(p.trustScore)} | Info: ${level.label}")
            appendLine()
            appendLine("Info           | Info | Info")
            appendLine("─────────────────────────────────────")
            appendCapabilityRow(this, "file_write",      0.2f, p.trustScore, p.earnedCapabilities)
            appendCapabilityRow(this, "terminal_access", 0.3f, p.trustScore, p.earnedCapabilities)
            appendCapabilityRow(this, "god_mode",        0.8f, p.trustScore, p.earnedCapabilities)
            appendCapabilityRow(this, "swarm_control",   0.9f, p.trustScore, p.earnedCapabilities)
            appendLine()
            if (p.earnedCapabilities.isEmpty()) {
                appendLine("💡 Info Info Info Info Info — Info Info Info Info.")
            } else {
                appendLine("✅ Info Info: ${p.earnedCapabilities.joinToString(", ")}")
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
            cap in earned             -> "✅ Info"
            score >= threshold        -> "🔓 Info"
            else -> {
                val remaining = threshold - score
                "🔒 Info +${"%.3f".format(remaining)}"
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
