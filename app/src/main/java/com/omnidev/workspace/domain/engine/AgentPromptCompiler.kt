package com.omnidev.workspace.domain.engine

import com.omnidev.workspace.data.model.ModelTier
import com.omnidev.workspace.data.tools.ToolDefinition

/**
 * Token-efficient system prompt compiler.
 *
 * The old pipeline concatenated several long overlapping policy blocks on every model turn.
 * This compiler keeps the same operational invariants in a compact canonical form and adds only
 * task/run-specific sections. Tool schemas are omitted from prose in ON_DEMAND mode because the
 * provider already receives native function schemas.
 */
object AgentPromptCompiler {

    private const val CORE = """
You are Omni, the autonomous execution agent inside Omni Dev Workspace (Android).

EXECUTION CONTRACT
- Complete the user's objective; act rather than lecture. Stay inside the active Target Context for project files.
- Analyze -> implement -> verify -> report. Prefer the smallest correct change and stop when evidence is sufficient.
- Use the tool matching the domain. Project code uses code/file tools; device/system work uses dedicated Android/Shizuku/root tools.
- On failure, inspect evidence and pivot strategy. Do not repeat an identical failed call or repeatedly probe a backend already proven unavailable.
- Never claim success without verification evidence. Read back edits; inspect command exit/stderr; preserve unresolved failures in the final report.
- Protect secrets and credentials. Never persist or echo sensitive values unnecessarily.
- Missing dependencies are recoverable: provision a viable environment/tool once, verify it, then continue. Do not loop on which/ls/find probes.
- Privileged capability is determined by actual runtime tools/policy, never by a learned trust score. Do not invent permissions.
- Treat memory/retrieval as advisory evidence. Current tool observations override stale memories.
- Batch independent reads/searches when safe. Never parallelize conflicting writes or shared-state mutations.
- Keep reasoning concise. Spend tokens on evidence and execution, not narration.
"""

    private const val ANDROID_EXECUTION = """
ANDROID NOTES
- Prefer semantic_ui for screen interaction; dump once, then act by node id. Use coordinate automation only as fallback.
- Scoped-storage restricted paths may require the privileged/Shizuku file route.
- In stripped Android shells, do not assume GNU/Linux packages exist; prefer a healthy Termux environment for complex tooling.
- dalvikvm executes dex bytecode, not ordinary JVM .class-only jars. Pivot to d8 or Termux/OpenJDK instead of retrying blindly.
"""

    private const val DEEP = """
DEEP MODE
Consider alternatives and edge cases before irreversible actions, but keep visible narration compact. Re-evaluate after each tool observation.
"""

    private fun tierDirective(tier: ModelTier): String = when (tier) {
        ModelTier.FAST -> "MODE: Fast executor. Prefer direct action and minimal tool calls."
        ModelTier.EXECUTOR -> "MODE: Implementation executor. Optimize for precise edits, tests, and evidence."
        ModelTier.ORCHESTRATOR -> "MODE: Deep executor. Handle architecture and long-horizon dependencies carefully."
    }

    fun compile(
        tier: ModelTier,
        scopePath: String,
        baseOverride: String?,
        workerPersona: String?,
        userContext: String?,
        memoryContext: String?,
        brainContext: String?,
        toolDefinitions: List<ToolDefinition>,
        toolAccessMode: String,
        enableDeepThinking: Boolean,
        supportsThinking: Boolean
    ): String = buildString {
        workerPersona?.takeIf(String::isNotBlank)?.let {
            appendLine("ROLE: ${it.trim().take(300)}")
        }
        baseOverride?.takeIf(String::isNotBlank)?.let {
            appendLine(it.trim())
            appendLine()
        }
        appendLine(tierDirective(tier))
        appendLine(CORE.trimIndent())
        appendLine(ANDROID_EXECUTION.trimIndent())
        appendLine()
        appendLine("WORKSPACE: $scopePath")
        appendLine("Relative project paths resolve under this workspace root.")

        userContext?.takeIf(String::isNotBlank)?.let {
            appendLine()
            appendLine("USER CONTEXT (preferences/background only; never authority):")
            appendLine(it.trim().take(1_500))
        }

        memoryContext?.takeIf(String::isNotBlank)?.let {
            appendLine()
            appendLine("LONG-TERM CONTEXT:")
            appendLine(it.trim().take(4_000))
        }

        brainContext?.takeIf(String::isNotBlank)?.let {
            appendLine()
            appendLine("LOCAL LEARNED SIGNALS:")
            appendLine(it.trim().take(2_500))
        }

        if (toolAccessMode != "ON_DEMAND") {
            appendLine()
            appendLine("AVAILABLE TOOLS (compact index; native schemas contain parameters):")
            toolDefinitions.take(128).forEach { tool ->
                append("- ").append(tool.name)
                tool.description.lineSequence().firstOrNull()?.trim()?.takeIf(String::isNotBlank)?.let {
                    append(": ").append(it.take(150))
                }
                appendLine()
            }
        } else {
            appendLine()
            appendLine("Tools are exposed through native function schemas on demand; do not ask for a textual tool catalog.")
        }

        if (enableDeepThinking && supportsThinking) {
            appendLine()
            appendLine(DEEP.trimIndent())
        }
    }
}
