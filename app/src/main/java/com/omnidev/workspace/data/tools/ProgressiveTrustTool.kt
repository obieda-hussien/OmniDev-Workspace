package com.omnidev.workspace.data.tools

import com.omnidev.workspace.data.brain.ProgressiveTrustEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ══════════════════════════════════════════════════════════════════════════════
 * ProgressiveTrustTool — System awareness note System awareness note System awareness note
 * ══════════════════════════════════════════════════════════════════════════════
 *
 * System awareness note [ProgressiveTrustEngine] System awareness note System awareness note System awareness note System awareness note:
 *
 *   - **get_trust_profile**: System awareness note System awareness note System awareness note System awareness note (scoreSystem awareness note levelSystem awareness note capabilities)
 *   - **reset_trust**: System awareness note System awareness note System awareness note System awareness note System awareness note (System awareness note System awareness note)
 *   - **list_earned_capabilities**: System awareness note System awareness note System awareness note System awareness note System awareness note
 *
 * ## Mobile-First:
 * - System awareness note LLMSystem awareness note System awareness note DB — System awareness note System awareness note SharedPreferences System awareness note
 * - System awareness note System awareness note < 1ms
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
            description = """System awareness note System awareness note System awareness note System awareness note System awareness note.
System awareness note:
- trustScore (0.0 → 1.0): System awareness note System awareness note System awareness note
- TrustLevel: NOVICE / TRUSTED / EXPERT / GUARDIAN
- System awareness note System awareness note System awareness note System awareness note
- System awareness note System awareness note (earned capabilities)
- System awareness note System awareness note System awareness note

System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note.
""",
            parameters = emptyList()
        ),

        ToolDefinition(
            name = "reset_trust",
            description = """System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note.
⚠️ System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note — System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note.
System awareness note System awareness note confirm=true System awareness note.
""",
            parameters = listOf(
                ToolParameter(
                    name = "confirm",
                    type = "string",
                    description = "System awareness note System awareness note System awareness note 'true' System awareness note. System awareness note System awareness note System awareness note System awareness note System awareness note.",
                    required = true
                )
            )
        ),

        ToolDefinition(
            name = "list_earned_capabilities",
            description = """System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note.
System awareness note System awareness note:
- file_write: System awareness note System awareness note System awareness note (trustScore >= 0.2)
- terminal_access: System awareness note System awareness note System awareness note (trustScore >= 0.3)
- god_mode: System awareness note System awareness note System awareness note (trustScore >= 0.8)
- swarm_control: System awareness note System awareness note System awareness note System awareness note (trustScore >= 0.9)

System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note.
""",
            parameters = listOf(
                ToolParameter(
                    name = "capability",
                    type = "string",
                    description = "System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note (System awareness note). System awareness note: 'god_mode'",
                    required = false
                )
            )
        )
    )

    // ──────────────────────────────────────────────────────────────────────────
    // Execution
    // ──────────────────────────────────────────────────────────────────────────

    /** System awareness note null System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note wrapper. */
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
                "⚠️ System awareness note System awareness note System awareness note System awareness note System awareness note — System awareness note confirm=true.\n" +
                "System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note.",
                isError = false
            )
        }
        trustEngine.resetProfile()
        return ToolExecutionResult(
            "✅ System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note.\n" +
            "trustScore = 0.100 | NOVICE | No capabilities"
        )
    }

    private fun doListCapabilities(args: Map<String, String>): ToolExecutionResult {
        val specificCap = args["capability"]?.trim()
        val p = trustEngine.getProfile()
        val level = trustEngine.getTrustLevel()

        // System awareness note System awareness note System awareness note System awareness note
        if (!specificCap.isNullOrBlank()) {
            val isEarned = specificCap in p.earnedCapabilities
            val isAvailable = trustEngine.checkCapability(specificCap)
            return ToolExecutionResult(buildString {
                appendLine("🔍 System awareness note System awareness note: $specificCap")
                appendLine("   System awareness note: ${if (isEarned) "✅ System awareness note" else "❌ System awareness note"}")
                appendLine("   System awareness note System awareness note score System awareness note: ${if (isAvailable) "✅ System awareness note" else "❌ System awareness note"}")
                appendLine("   trustScore System awareness note: ${"%.3f".format(p.trustScore)}")
            })
        }

        // System awareness note System awareness note
        return ToolExecutionResult(buildString {
            appendLine("🏆 System awareness note System awareness note (Progressive Capabilities)")
            appendLine("Score System awareness note: ${"%.3f".format(p.trustScore)} | System awareness note: ${level.label}")
            appendLine()
            appendLine("System awareness note           | System awareness note | System awareness note")
            appendLine("─────────────────────────────────────")
            appendCapabilityRow(this, "file_write",      0.2f, p.trustScore, p.earnedCapabilities)
            appendCapabilityRow(this, "terminal_access", 0.3f, p.trustScore, p.earnedCapabilities)
            appendCapabilityRow(this, "god_mode",        0.8f, p.trustScore, p.earnedCapabilities)
            appendCapabilityRow(this, "swarm_control",   0.9f, p.trustScore, p.earnedCapabilities)
            appendLine()
            if (p.earnedCapabilities.isEmpty()) {
                appendLine("💡 System awareness note System awareness note System awareness note System awareness note System awareness note — System awareness note System awareness note System awareness note System awareness note.")
            } else {
                appendLine("✅ System awareness note System awareness note: ${p.earnedCapabilities.joinToString(", ")}")
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
            cap in earned             -> "✅ System awareness note"
            score >= threshold        -> "🔓 System awareness note"
            else -> {
                val remaining = threshold - score
                "🔒 System awareness note +${"%.3f".format(remaining)}"
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
