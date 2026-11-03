package com.omnidev.workspace.domain.engine.completion

import com.omnidev.workspace.domain.engine.RunEvidenceLedger
import java.security.MessageDigest
import java.util.UUID

/**
 * Terminal states of a run under the finish protocol. The runtime, never the model, decides which
 * of them is *accepted*; the model only proposes one.
 */
enum class FinishStatus(val wire: String, val label: String) {
    COMPLETED("completed", ""),
    BLOCKED("blocked", "[Blocked]"),
    FAILED("failed", "[Failed]"),
    AWAITING_APPROVAL("awaiting_approval", "[Awaiting approval]"),
    // Must stay equal to PipelineRecovery.INCOMPLETE_PREFIX: workers/coordinator already key off it.
    INCOMPLETE("incomplete", "[Incomplete]");

    companion object {
        fun parse(raw: String?): FinishStatus? {
            val key = raw?.trim()?.lowercase()?.replace('-', '_')?.replace(' ', '_') ?: return null
            return entries.firstOrNull { it.wire == key }
        }
    }
}

/** What the assigned objective is, as far as the runtime must enforce evidence for it. */
enum class TaskKind {
    /** Single-agent run or chat-like task: only baseline checks. */
    GENERAL,
    /** Read-only research/review work: needs real tool evidence, never forced to modify anything. */
    ANALYSIS,
    /** Work that changes state: needs at least one successful non-read action as well. */
    IMPLEMENTATION
}

/** Identity of one run (and of the worker inside a team run). Created by the runtime only. */
data class RunIdentity(
    val runId: String = "run-" + UUID.randomUUID().toString().take(8),
    val workerId: String? = null,
    /** Checkable acceptance criteria for this run, usually from the coordinator's task. */
    val acceptance: List<String> = emptyList(),
    /** null = decide from task signals. */
    val requireExecution: Boolean? = null,
    val taskKind: TaskKind = TaskKind.GENERAL
)

/** Counters for measuring how often the protocol intervenes (benchmark input, never trusted by the gate). */
data class FinishStats(val rejections: Int = 0, val malformed: Int = 0, val implicit: Boolean = false)

/** Immutable, runtime-produced snapshot of one ledger entry. Models can cite it but never create it. */
data class EvidenceRef(
    val callId: String,
    val seq: Int,
    val tool: String,
    val target: String,
    val outcome: RunEvidenceLedger.Outcome,
    val kind: EvidenceKinds.Kind,
    val runId: String,
    val workerId: String?
) {
    /** The id a model is told to cite. Falls back to the sequence number for legacy entries. */
    val citeId: String get() = callId.ifBlank { "#$seq" }
}

data class CriterionClaim(val key: String, val text: String, val evidenceIds: List<String>)

data class AcceptanceCriterion(val id: String, val text: String, val source: Source) {
    enum class Source { RUNTIME, TASK, MODEL }
}

data class CriterionResult(
    val criterion: AcceptanceCriterion,
    val met: Boolean,
    val evidenceIds: List<String> = emptyList(),
    val reason: String? = null
)

/**
 * Self-contained, runtime-produced record of how a run ended. Carried on FinalAnswer so a
 * coordinator can re-check the evidence instead of trusting the worker's prose.
 */
data class CompletionReport(
    val runId: String,
    val workerId: String?,
    val status: FinishStatus,
    val summary: String,
    /** true only when status == COMPLETED and every criterion was met against the ledger. */
    val verified: Boolean,
    val criteria: List<CriterionResult>,
    val evidence: List<EvidenceRef>,
    val problems: List<String>,
    val ledgerDigest: String,
    val stats: FinishStats = FinishStats()
) {
    companion object {
        fun digest(evidence: List<EvidenceRef>): String {
            // Length-prefix every field so separators inside paths and ids cannot make an ambiguous payload.
            fun field(value: String): String = value.length.toString() + ":" + value
            val canonical = evidence.joinToString("\n") { entry ->
                listOf(
                    entry.seq.toString(), entry.callId, entry.tool, entry.target,
                    entry.outcome.name, entry.kind.name, entry.runId, entry.workerId.orEmpty()
                ).joinToString("") { field(it) }
            }
            return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }
    }
}

object EvidenceKinds {
    enum class Kind { READ, MUTATION, ACTION, OTHER }

    private val MUTATION = setOf(
        "create_file", "append_to_file", "clear_file", "delete_file", "delete_lines", "delete_text",
        "insert_lines", "replace_lines", "patch_file_content", "multi_patch_file_content",
        "god_write_file", "god_copy_file", "god_delete_file", "copy_move", "secure_delete",
        "install_tool", "package_installer_tool", "create_notion_page", "remember_fact", "update_memory", "delete_memory"
    )
    private val ACTION = setOf(
        "install_tool", "package_installer_tool", "create_notion_page", "remember_fact", "update_memory",
        "device_admin", "hardware_toggle_tool", "app_manager_tool", "communicate_tool", "oem_system"
    )
    private val READ = setOf(
        "read_file", "read_file_lines", "multi_read", "god_read_file", "god_list_directory",
        "god_stat_file", "find_files", "file_info", "hex_dump", "diff_files", "grep_search",
        "search_codebase", "repo_find_context", "repo_file_symbols", "repo_search_symbols",
        "search_messages", "list_chat_sessions", "read_chat_session", "read_chat_message",
        "fetch_page", "web_search", "web_search_deep", "web_scraper", "scrape_multiple", "headless_browser"
    )
    private val COMMAND_TOOLS = setOf(
        "run_terminal", "agent_runtime", "privileged_tool", "root_shell_tool", "advanced_root_shell",
        "direct_terminal", "execute_terminal_command", "python_runner", "python_runtime"
    )
    private val COMMAND_ARGUMENTS = setOf("command", "cmd", "shell_command", "script", "code", "source", "expression")
    private val PATH = Regex("""(?:path|filePath|file_path|filepath|directory|file|filename|output_path)=(\S+)""")
    private val MUTATING_COMMAND = Regex(
        """(?is)(?:^|[;&|]\s*)(?:sudo\s+)?(?:tee|touch|mkdir|rmdir|rm|mv|cp|install|truncate|patch)\b|(?:sed|perl)\s+-i\b|\bgit\s+(?:apply|checkout|merge|cherry-pick|reset|revert|clean)\b|\b(?:write_text|write_bytes|unlink|mkdir|remove|rename|replace)\s*\(|\bopen\s*\([^)]*,\s*['"][wax]|(?<!>)>(?!>)"""
    )
    private val READ_ONLY_COMMAND = Regex(
        """(?is)(?:^|[;&|]\\s*)(?:cd\\s+[^;&|]+\\s*(?:&&|;)\\s*)?(?:pwd|ls|find|cat|head|tail|grep|rg|stat|file|wc|git\\s+(?:status|diff|log|show|branch|rev-parse))\\b|\\b(?:read_text|read_bytes|exists|is_file|is_dir)\\s*\\(|\\bopen\\s*\\([^)]*,\\s*['"]r"""
    )
    private val SYSTEM_ACTION_COMMAND = Regex(
        """(?is)(?:^|[;&|]\\s*)(?:settings\\s+put|pm\\s+(?:install|uninstall|enable|disable)|svc\\s+\\w+\\s+(?:enable|disable)|setprop|chmod|chown|am\\s+(?:start|force-stop)|reboot)\\b"""
    )
    private val REDIRECT_TARGET = Regex(
        """(?:>>?|\btee\s+)\s*["']?(/[^\s"';&|]+|~?/[^\s"';&|]+|(?:\./)?[A-Za-z0-9_.-]+\.[A-Za-z0-9_-]+)"""
    )
    private val FILE_TARGET = Regex(
        """(?is)\b(?:touch|mkdir|rmdir|rm|mv|cp|install|truncate|patch)\b(?:\s+-[^\s]+)*\s+(/[^\s;&|]+|~?/[^\s;&|]+|(?:\./)?[A-Za-z0-9_.-]+\.[A-Za-z0-9_-]+)"""
    )
    private val PYTHON_TARGET = Regex(
        """(?:write_text|write_bytes|open)\s*\(\s*['"]([^'"]+)['"]"""
    )

    fun classify(tool: String): Kind = when (tool) {
        in MUTATION -> Kind.MUTATION
        in ACTION -> Kind.ACTION
        in READ -> Kind.READ
        else -> Kind.OTHER
    }

    /** Inspect command payload only to classify side effects; raw command text is never persisted as evidence. */
    fun classify(tool: String, arguments: Map<String, String>): Kind {
        val known = classify(tool)
        if (known != Kind.OTHER || tool !in COMMAND_TOOLS) return known
        val command = arguments.entries.filter { it.key.lowercase() in COMMAND_ARGUMENTS }
            .joinToString("\n") { it.value.take(20_000) }
        return when {
            MUTATING_COMMAND.containsMatchIn(command) -> Kind.MUTATION
            SYSTEM_ACTION_COMMAND.containsMatchIn(command) -> Kind.ACTION
            READ_ONLY_COMMAND.containsMatchIn(command) -> Kind.READ
            else -> Kind.OTHER
        }
    }

    /** Best-effort target extraction for common shell redirection and file mutation operations. */
    fun commandTarget(tool: String, arguments: Map<String, String>): String? {
        if (tool !in COMMAND_TOOLS) return null
        val command = arguments.entries.filter { it.key.lowercase() in COMMAND_ARGUMENTS }
            .joinToString("\n") { it.value.take(20_000) }
        if (!MUTATING_COMMAND.containsMatchIn(command)) return null
        return REDIRECT_TARGET.find(command)?.groupValues?.getOrNull(1)
            ?: PYTHON_TARGET.find(command)?.groupValues?.getOrNull(1)
            ?: FILE_TARGET.find(command)?.groupValues?.getOrNull(1)
    }

    /** Path-like target recorded by the ledger (already redacted), or null. */
    fun pathOf(target: String): String? = PATH.find(target)?.groupValues?.get(1)
}
