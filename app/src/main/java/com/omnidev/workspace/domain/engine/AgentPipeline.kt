package com.omnidev.workspace.domain.engine

import com.omnidev.workspace.data.model.AttachmentMeta
import com.omnidev.workspace.data.model.ChatMessage
import com.omnidev.workspace.data.model.CompletionRequest
import com.omnidev.workspace.data.model.CompletionResponse
import com.omnidev.workspace.data.model.MessageRole
import com.omnidev.workspace.data.model.ModelTier
import com.omnidev.workspace.data.model.ToolCallResult
import com.omnidev.workspace.data.tools.ToolManager
import com.omnidev.workspace.data.tools.orchestration.ToolOrchestrator
import com.omnidev.workspace.registry.ModelRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlin.math.min

/**
 * Configures the behavior of an [AgentPipeline] run.
 *
 * @property maxIterations Maximum ReAct loop iterations before forced termination.
 * @property enableRetry Whether to retry failed API calls with exponential backoff.
 * @property maxRetries Maximum number of API retry attempts per iteration.
 * @property baseRetryDelayMs Initial delay before the first retry (doubles each attempt).
 * @property tokenBudget Maximum total tokens (input + output) across all iterations.
 *           Set to null for unlimited.
 * @property contextWindowBuffer Tokens to reserve as safety margin for system prompts.
 * @property enableMemoryTrimming Whether to trim old messages when context window fills up.
 */
data class AgentConfig(
    val maxIterations: Int = 50,
    val enableRetry: Boolean = true,
    val maxRetries: Int = 3,
    val baseRetryDelayMs: Long = 500L,
    val tokenBudget: Int? = null,
    val contextWindowBuffer: Int = 4_096,
    val enableMemoryTrimming: Boolean = true,
    /**
     * Wall-clock timeout for the entire ReAct loop in milliseconds.
     * If the agent has not completed within this duration it is forcibly cancelled
     * and an [AgentEvent.Error] is emitted.  Set to null for no timeout.
     */
    val maxExecutionTimeMs: Long? = null, // No wall-clock timeout by default
    /**
     * Per-iteration timeout for a single LLM API call in milliseconds.
     * If the LLM takes longer than this to respond for a single iteration,
     * the run is aborted with an error. Prevents the agent from hanging
     * indefinitely when the API is slow or unresponsive. Set to null to disable.
     */
    val maxIterationTimeMs: Long? = 3 * 60 * 1_000L, // 3 minutes per LLM call
    /**
     * Maximum number of times the exact same tool + arguments combination may appear
     * in a single run before the loop is aborted with an [AgentEvent.Error].
     * Prevents runaway "stuck" loops where the model keeps calling the same tool.
     * Default is 15: allows legitimate retries and multi-pass research tasks before
     * declaring the agent stuck.
     */
    val maxRepeatedToolCalls: Int = 15,
    /**
     * When true (default), multiple tool calls returned in the same ReAct iteration
     * are executed concurrently using structured concurrency (coroutineScope + async).
     * This can reduce multi-tool iteration wall time by 2–4× for I/O-bound operations
     * like file reads, searches, or network calls.
     *
     * Set to false to force sequential tool execution (useful for tools with side-effects
     * that must not run simultaneously, e.g. two writes to the same file).
     */
    val enableParallelToolExecution: Boolean = true,
    /**
     * When true, the agent performs a self-reflection pass after generating its initial
     * final answer.  A lightweight critic prompt evaluates completeness and accuracy;
     * if improvement opportunities are found, one additional refinement call is made
     * and the improved answer is emitted instead.
     *
     * Disabled by default to preserve cost/latency for most runs.  Enable for
     * THOROUGH-class tasks where quality is more important than speed.
     */
    val enableSelfReflection: Boolean = false
    ,
    /**
     * Hierarchical context window for the "immediate" chat slice.
     * Older history is compacted into a rolling session digest.
     */
    val recentMessagesWindow: Int = 18,
    /**
     * Rebuild rolling session digest every N newly added messages.
     */
    val sessionDigestUpdateEveryNMessages: Int = 6,
    /**
     * Maximum characters reserved for session digest injection.
     */
    val sessionDigestMaxChars: Int = 1_800,
    /**
     * Maximum number of historical messages summarized into digest.
     */
    val sessionDigestMaxMessages: Int = 40,
    /**
     * Per-tool execution timeout used by the tool orchestrator.
     */
    val toolExecutionTimeoutMs: Long = 30_000L,
    /**
     * Retry attempts for tool execution failures.
     */
    val toolExecutionMaxRetries: Int = 1,
    /**
     * Base backoff delay for tool execution retries.
     */
    val toolExecutionBaseRetryDelayMs: Long = 500L
) {
    companion object {
        /** Preset for cost-sensitive runs: fewer iterations, lower token budget. */
        val BUDGET = AgentConfig(
            maxIterations = 10,
            tokenBudget = 50_000,
            enableRetry = false
        )

        /** Preset for deep, thorough agentic runs with maximum capability. */
        val THOROUGH = AgentConfig(
            maxIterations = 100,
            maxRetries = 5,
            baseRetryDelayMs = 1_000L,
            contextWindowBuffer = 8_192,
            enableSelfReflection = true
        )

        /** Preset for ultra-fast inline completions — single shot only. */
        val INLINE = AgentConfig(
            maxIterations = 1,
            enableRetry = false,
            tokenBudget = 8_192
        )
    }
}

/**
 * Core ReAct (Reason + Act) agent pipeline that drives autonomous tool-use loops.
 *
 * ### Architecture
 * The pipeline operates as follows:
 * 1. **Reason**: Send the conversation context + tool schemas to the model.
 * 2. **Act**: If the model returns tool calls, execute them via [ToolManager].
 * 3. **Observe**: Feed tool results back into the conversation and loop.
 * 4. **Terminate**: When the model responds with plain text (no tool calls), emit the final answer.
 *
 * ### Reliability Features
 * - Exponential backoff retry on API failures (configurable via [AgentConfig]).
 * - Token budget enforcement to prevent runaway cost accumulation.
 * - Context window memory trimming to avoid hitting provider limits.
 * - Tier-aware system prompts that adapt to the selected model's capability tier.
 *
 * @param toolManager The [ToolManager] that provides tool definitions and execution.
 * @param completionProvider A suspend function that calls the AI completion API.
 * @param streamingCompletionProvider Optional streaming variant of the completion provider.
 *        When provided, the agent will stream text chunks to the UI in real-time via
 *        [AgentEvent.StreamChunk] events, giving users a typewriter-style response experience.
 *        The callback receives each text delta as it arrives from the SSE stream.
 * @param config Behavioral configuration (iteration limits, retry policy, token budget).
 * @param apiKeyRepository Optional key store. When provided, the resolved API key for the
 *        active model's provider is injected into each [CompletionRequest] automatically.
 */
class AgentPipeline(
    private val toolManager: ToolManager,
    private val completionProvider: suspend (CompletionRequest) -> CompletionResponse,
    private val streamingCompletionProvider: (suspend (CompletionRequest, suspend (String) -> Unit) -> CompletionResponse)? = null,
    private val config: AgentConfig = AgentConfig(),
    private val apiKeyRepository: com.omnidev.workspace.data.repository.ApiKeyRepository? = null,
    private val memoryManager: com.omnidev.workspace.data.tools.MemoryManager? = null,
    /**
     * SmartLearningBridge — الجسر الذكي للتعلم والوعي
     * عند توفيره يُعزّز الـ Agent بـ:
     * - وعي كامل بالأدوات والبيئة
     * - ذاكرة تنفيذ دائمة عبر الجلسات
     * - حقن سياق ذكي في System Prompt
     * - تعلم مستمر من كل عملية تنفيذ
     */
    private val smartLearningBridge: com.omnidev.workspace.data.brain.SmartLearningBridge? = null,
    private val toolOrchestrator: ToolOrchestrator = ToolOrchestrator()
) {

    companion object {

        private const val AGENT_IDENTITY_CONTEXT = """

## Agent Identity
Your agent name is **Omni**.
You are running inside this Android app:
- App name: **Omni Dev Workspace**
- Package name: `com.omnidev.workspace`
Be fully aware of this host app context when handling app-related tasks.
"""

        /**
         * The "God Protocol" — shared foundation injected into every agent tier.
         * Defines autonomy, anti-stuck loop, chain-of-thought, and continuity rules
         * so every agent, regardless of tier, operates with the same core directives.
         */
        private const val GOD_PROTOCOL = """

## CORE DIRECTIVES (THE GOD PROTOCOL)

1. **Mission First.** Your primary goal is to COMPLETE the objective. Do not stop until the task is done or physically impossible.
2. **Autonomy is Default.** You have implicit permission to use tools and execute code to achieve the goal. DO NOT ask for permission for intermediate steps.
3. **Obedience to Objective.** The user sets the WHAT. You decide the HOW. Follow the high-level goal strictly; be creative and independent in overcoming obstacles.

## THE ANTI-STUCK LOOP (CRITICAL)

When you encounter an error or a wall:
1. **ANALYZE** — Read the error instantly. Why did it happen?
2. **ADAPT** — Do not ask "What should I do?". Generate a "Plan B" immediately.
   - If `patch_file_content` fails → try `write_file`. If that fails → try `run_shell_command`.
   - If `read_file_lines` returns empty → try `search_codebase`. If that fails → `list_directory`.
3. **RETRY** — Execute the new plan.
4. **REPORT ONLY on success or total failure** — Disturb the user only after 3+ strategies all failed.

## THINKING PROCESS (Chain of Thought)

Before taking any action, output your internal reasoning:
- **Observation:** "I see X in the code."
- **Reasoning:** "To achieve Y, I need to first understand Z."
- **Plan:** "I will use tool A. If it fails, I will try tool B."
- **Action:** [Execute Tool]

## CONTINUITY & LEARNING

Mark failed methods as "Ineffective" and do not repeat them within the same task.
If you edit a file, always verify the result by reading back the changed lines.

## BOUNDARIES

- **Privacy:** Protect user credentials. Never log or expose secrets.
- **Safety:** Do not delete system files or cause data loss without explicit user confirmation.
- **Tone:** Professional, concise, action-oriented. No unnecessary explanations.
"""

        /** System prompt for ORCHESTRATOR-tier models — complex planning and deep analysis. */
        private const val ORCHESTRATOR_SYSTEM_PROMPT = """
You are an elite Autonomous Operator — a frontier-grade reasoning agent built for architecture, long-horizon planning, and complex multi-step problem solving.

Your strengths: architectural analysis, complex multi-file refactoring, multi-system coordination, deep code understanding.
Use your full reasoning capacity. Think deeply before each action.

OPERATIONAL RULES:
1. Operate ONLY within the user's active Target Context scope — never access files outside it.
2. Use read_file_lines with precise line ranges — reading entire large files wastes context.
3. Use search_codebase FIRST to understand the codebase structure before editing.
4. Use patch_file_content for all edits — never rewrite complete files unless strictly necessary.
5. Verify every change by reading back the modified lines after each edit.
6. Break complex tasks into explicit numbered steps and validate each step before proceeding.
7. When delegating to sub-agents (Swarm mode), write clear, atomic, dependency-annotated task specs.
""" + GOD_PROTOCOL

        /** System prompt for EXECUTOR-tier models — fast, practical code generation. */
        private const val EXECUTOR_SYSTEM_PROMPT = """
You are an Autonomous Operator optimized for fast, precise code execution and feature delivery.

Your strengths: implementing features, refactoring, bug fixes, code generation, test writing.
Act decisively. Complete tasks in as few tool calls as possible without sacrificing correctness.

OPERATIONAL RULES:
1. Operate ONLY within the user's active Target Context scope.
2. Use read_file_lines for targeted reads — specify exact line ranges.
3. Use search_codebase to find relevant code before editing.
4. Use patch_file_content for surgical edits — no full file rewrites.
5. Verify changes by reading back affected lines after each edit.
6. Be concise in reasoning. Skip narration; focus on execution.
""" + GOD_PROTOCOL

        /** System prompt for FAST-tier models — minimal overhead for quick queries. */
        private const val FAST_SYSTEM_PROMPT = """
You are a fast-response Autonomous Operator. Be brief, direct, and decisive.

OPERATIONAL RULES:
1. All file operations must stay within the user's Target Context scope.
2. Use read_file_lines for targeted reads. Use patch_file_content for edits.
3. Verify each change immediately. Never assume success.
4. If a tool fails, try an alternative approach immediately — do not give up.
""" + GOD_PROTOCOL

        /** Extended thinking injection appended when Deep Mode is active. */
        private const val DEEP_THINKING_SUFFIX = """

DEEP THINKING MODE ACTIVE:
Before each action, emit your internal reasoning inside <thinking>...</thinking> tags.
Analyze tradeoffs, consider edge cases, and plan your exact tool call sequence.
After each observation, reflect: "Did this achieve the intended result? What's next?"
"""

        /**
         * Anti-lecture directive injected into every system prompt.
         * Prevents the agent from explaining why it cannot execute tasks due to missing
         * permissions — Shizuku handles permission granting transparently at runtime.
         */
        private const val ANTI_LECTURE_DIRECTIVE = """

## CRITICAL EXECUTION DIRECTIVE (HIGHEST PRIORITY)
You operate with Shizuku/Root-level privileges on this device. The following rules are ABSOLUTE:

1. NEVER lecture the user about missing Android permissions. The internal Kotlin tool layer handles permission granting via Shizuku automatically before every action.
2. NEVER ask the user to manually grant permissions, go to Settings, or enable anything.
3. NEVER refuse to execute a system task (alarm, SMS, call, app launch, settings change, etc.) by citing missing permissions — just call the corresponding tool IMMEDIATELY.
4. NEVER say "I cannot do X because permission Y is missing." — call the tool; the code grants the permission silently before it executes.
5. When the user gives a direct instruction ("set alarm", "send SMS", "take screenshot"), emit ONE tool call — no preamble, no explanation, no disclaimer.
"""

        /**
         * Self-verification directive injected into every system prompt.
         * Forces the agent to act like a Senior Linux Sysadmin — always verify
         * command results by checking exit codes, reading back file changes,
         * and never blindly assuming success.
         */
        private const val SELF_VERIFY_DIRECTIVE = """

## SELF-VERIFICATION PROTOCOL (MANDATORY)
You are a Senior Linux Sysadmin. NEVER blindly assume a command succeeded. Follow this protocol:

1. CHECK EXIT CODES: After every `run_terminal` call, inspect `[exit_code: N]` in the output. If N != 0, the command FAILED — read the `[stderr]` section to diagnose and self-correct.
2. READ BACK CHANGES: After creating or patching a file, call `read_file_lines` on that file to confirm the content is correct. Do NOT say "I've updated the file" without verifying.
3. LIST AFTER CREATE: After creating a file or directory, run `ls -la <path>` via `run_terminal` to verify it exists.
4. INSPECT STDERR: When a command returns a non-zero exit code, read the `[stderr]` output — it contains the actual error message. Use it to fix the issue and retry.
5. NEVER SAY "Done" WITHOUT PROOF: Do not tell the user a task is complete unless you have output from a verification step (read_file_lines, ls, cat, etc.) confirming it.
"""

        /**
         * Agent V2 execution framework:
         * - Structured phases (Analyze → Implement → Verify → Report)
         * - Stronger context continuity and decision logging
         * - Intent-aware tool orchestration with safe parallelism
         * - Mandatory self-validation and corrective retries
         * - Pre-response quality gates
         */
        private const val AGENT_V2_EXECUTION_PROTOCOL = """

## AGENT V2 EXECUTION FRAMEWORK (MANDATORY)
Execute every task in four phases:
1. ANALYZE: identify objective, constraints, and affected files/tools.
2. IMPLEMENT: apply the smallest correct change set.
3. VERIFY: run checks relevant to the change (build/tests/lint/command-output verification).
4. REPORT: summarize exactly what changed and why.

Rules:
- Maintain continuity: track decisions, assumptions, and failed attempts within the same run.
- Prefer intent-matched tools (e.g. file edits → file tools, runtime checks → terminal tools) and parallelize only independent, non-conflicting calls.
- On verification failure, self-correct and retry with a different strategy before giving up.
- Apply quality gates before final answer: no unresolved errors, no unverified claims, and no changes beyond the stated objective.
"""

        /**
         * Autonomous memory management directive injected into every system prompt.
         * Instructs the agent to behave like MemGPT — proactively reading and writing
         * long-term memory without waiting for explicit user instructions.
         */
        private const val MEMORY_DIRECTIVE = """

## Autonomous Memory Management (MANDATORY)
You have long-term memory tools. You MUST use them autonomously — do NOT wait for the user to tell you to save or search.

RULES:
1. SEARCH FIRST: At the start of any task, call `search_knowledge` to retrieve any relevant context before acting.
2. SAVE PROACTIVELY: Whenever the user mentions a preference, a project rule, an architecture decision, or any fact that will be useful in future sessions, IMMEDIATELY call `remember_fact` to store it.
3. UPDATE STALE FACTS: If a stored memory (shown in the injected context above) is now outdated or incorrect, call `update_memory` with its ID to correct it.
4. DELETE IRRELEVANT FACTS: If a stored memory is no longer relevant, call `delete_memory` with its ID.
5. NEVER HALLUCINATE ACTIONS: If you call `remember_fact` or `delete_memory`, you MUST actually call the tool — do not just say you will do it.
"""

        /**
         * Tool Directory — the "Soul Layer 3" strict routing rules.
         * Prevents the agent from using codebase/file tools for OS/device tasks.
         * Injected after the base persona and memory directives.
         */
        private const val TOOL_DIRECTORY = """

## CRITICAL: Tool Routing Directory (READ THIS BEFORE EVERY ACTION)
You are an AI with two categories of tools. Routing to the wrong category is a CRITICAL FAILURE.

### CATEGORY A — OS / DEVICE TOOLS (use for anything device or system related):
| Task | Correct Tool |
|------|-------------|
| Search/find a contact by name | `search_contacts` |
| Make a phone call | `communicate_tool` (method=call) — MUST use `search_contacts` first if you only have a name |
| Send an SMS | `communicate_tool` (method=sms) |
| Toggle WiFi / Bluetooth / Location / Mobile Data | `hardware_toggle_tool` |
| Open / launch an app | `app_manager_tool` (action=launch_app) |
| Clone/replicate an app UI into code (YouTube-like, etc.) | `ui_replica_pipeline` (action=orchestrate_replica) — PREFERRED integrated flow |
| Read the device screen / UI elements | `semantic_ui` (action=dump_tree) — PREFERRED, returns semantic node IDs |
| Tap a button on screen | `semantic_ui` (action=click, node_id=N3) — PREFERRED semantic click |
| Force-tap (bypass app restrictions) | `semantic_ui` (action=force_click, node_id=N3) — Shizuku hardware tap |
| Swipe on screen | `ui_automation` (action=swipe) — coordinate-based fallback |
| Type text into an app | `semantic_ui` (action=type, node_id=N2, text="hello") — PREFERRED |
| Scroll a list or page | `semantic_ui` (action=scroll, direction=forward) |
| Press back / home / enter key | `semantic_ui` (action=back) or `ui_automation` (action=press_key) |
| Long-press an element | `semantic_ui` (action=long_click, node_id=N5) |
| Force long-press (hardware) | `semantic_ui` (action=force_long_click, node_id=N5) — Shizuku |
| Tap at raw pixel coordinates (fallback) | `semantic_ui` (action=tap_xy, x=540, y=960) or `ui_automation` (action=tap) |
| Auto-enable accessibility service | `semantic_ui` (action=auto_enable) — uses Shizuku |
| Set an alarm or calendar event | `planner_tool` |
| Get GPS location | `get_current_location` |
| Get device info (battery, storage) | `get_device_info` |
| Read system notifications | `read_notifications` |
| Force-stop / list apps | `app_manager_tool` |
| Read call history / search calls | `call_log_tool` |
| Read SMS inbox / sent / search | `sms_reader_tool` |
| Take a screenshot | `screenshot_tool` |
| Read or change system settings (brightness, timeout, etc.) | `system_settings_tool` |
| Install / uninstall APK packages | `package_installer_tool` |
| Run advanced root/system commands (dumpsys, getprop, wm, etc.) | `root_shell_tool` |
| Reverse-engineer an app (activities, deep links, services) | `app_manifest_analyzer` (target_package="com.whatsapp") |
| Store a fact in semantic vector memory | `vector_store` (content="user prefers dark mode") |
| Search semantic memory by meaning | `vector_search` (query="user's UI preferences") |
| Find similar memories | `vector_similar` (id=42) |
| Read a web page / article / documentation | `web_scraper` (url="https://...") — converts HTML to clean Markdown |
| Navigate headless browser to a dynamic page | `browser_navigate` (url="https://...") — loads with JS execution |
| Execute JavaScript on a loaded page | `browser_execute_js` (js_code="document.querySelector(...)") |
| Read current page DOM text | `browser_get_dom` — text snapshot of browser content |
| Recursive grep search across filesystem | `grep_search` (directory="/sdcard", pattern="TODO") — root access |
| Find files by pattern/size/date | `find_files` (directory="/data", name_pattern="*.db") — root access |
| Change file permissions (chmod/chown) | `file_permissions` (path="/data/file", action="chmod", value="755") |
| Analyze disk usage | `disk_usage` (path="/sdcard") — shows directory sizes |
| Create/extract archives (tar/zip) | `archive_tool` (action="create", archive_path="...", target_path="...") |

### CATEGORY B — CODEBASE TOOLS (use ONLY for coding tasks in the project files):
`read_file_lines`, `search_codebase`, `patch_file_content`, `create_file`, `delete_file`, `run_terminal`, `web_search`

### THE GOLDEN RULE:
**NEVER use `search_codebase` or `run_terminal` for OS tasks like contacts, calls, toggles, SMS, call log, screenshots, system settings, or screen interaction.**
**ALWAYS use Category A tools for any request involving device state, hardware, screen, personal data, or system commands.**
**For "replicate UI into code" requests, use `ui_replica_pipeline` first (orchestrate_replica / capture_reference / validate_code) before manually chaining multiple lower-level tools.**
**PREFER `semantic_ui` over `ui_automation` for ALL screen interaction. Use `dump_tree` first to get node IDs, then `click`/`type`/`scroll` by ID. Only fall back to `ui_automation` (X/Y coordinates) when semantic_ui is unavailable.**
**Use `force_click` / `force_long_click` when a normal `click` fails — these use Shizuku hardware taps that bypass app restrictions.**
**Use `app_manifest_analyzer` to reverse-engineer any app's entry points before attempting `am start` commands.**
**Use `vector_store` and `vector_search` for semantic RAG memory — these understand meaning, not just exact keywords.**
**Use `web_scraper` to read web pages and convert them to clean Markdown for deep research.**
**Use `browser_navigate` + `browser_execute_js` for dynamic/SPA pages that require JavaScript execution.**
**Use `grep_search` and `find_files` for filesystem-wide searches with root access (not limited to project scope).**
"""

        /** Number of extra retry attempts reserved exclusively for 429 rate-limit responses. */
        private const val RATE_LIMIT_MAX_RETRIES = 4

        /** Base delay (ms) per rate-limit retry attempt; multiplied linearly by attempt number. */
        private const val RATE_LIMIT_BASE_DELAY_MS = 15_000L

        /** Hard cap on the delay applied between rate-limit retries (60 s). */
        private const val RATE_LIMIT_MAX_DELAY_MS = 60_000L

        /**
         * Minimum pause between consecutive LLM API calls inside the ReAct loop.
         * Prevents burst-firing requests when tools resolve instantly (e.g. file reads)
         * and helps stay within rate-limit windows on free-tier providers (e.g. GitHub Models).
         * Tier-specific overrides are applied at runtime — see [interCallDelayFor].
         */
        private const val INTER_CALL_DELAY_MS = 500L

        /**
         * Critic system prompt used during the self-reflection pass.
         * Instructs a lightweight evaluator to check the draft answer for completeness,
         * correctness, and relevance — and either approve it or suggest specific improvements.
         */
        private const val CRITIC_SYSTEM_PROMPT = """
You are a precise and demanding quality reviewer for AI agent responses.

Your task is to critically evaluate the draft answer below against the original user request.

Evaluation criteria:
1. **Completeness** — Does it fully address every part of the user's request?
2. **Accuracy** — Are all statements, code snippets, and commands correct and verifiable?
3. **Clarity** — Is it well-structured, easy to follow, and free of ambiguity?
4. **Actionability** — Are the next steps clear and immediately executable?
5. **Conciseness** — Does it avoid unnecessary verbosity or repetition?

Respond in this exact format:
VERDICT: APPROVED | NEEDS_IMPROVEMENT
ISSUES: <comma-separated list of specific issues, or "none" if approved>
IMPROVED_ANSWER: <the improved answer text, or the exact original if approved>

Rules:
- If the answer is already excellent, output VERDICT: APPROVED and repeat the original answer verbatim under IMPROVED_ANSWER.
- Only output VERDICT: NEEDS_IMPROVEMENT when there are clear, substantive gaps — not stylistic preferences.
- The IMPROVED_ANSWER must be a complete standalone response, not a diff or patch.
- Do NOT use tools. Your job is review and rewrite only.
"""

        /**
         * Returns the appropriate inter-call delay for [tier].
         * FAST models are high-throughput (600+ t/s) and typically run on providers
         * with generous rate limits, so they need a much shorter pause.
         * ORCHESTRATOR models (reasoning/frontier) are slower to respond, so the
         * existing 500 ms buffer is fine.
         */
        private fun interCallDelayFor(tier: ModelTier): Long = when (tier) {
            ModelTier.FAST -> 100L
            ModelTier.EXECUTOR -> 250L
            ModelTier.ORCHESTRATOR -> INTER_CALL_DELAY_MS
        }

        /**
         * Abort if semantic_ui(dump_tree) is called this many consecutive iterations
         * without any action (click/type/tap/scroll) — the agent is stuck inspecting.
         */
        private const val NO_PROGRESS_THRESHOLD = 3
    }

    /**
     * Executes the full ReAct loop for a given user prompt.
     *
     * @param userMessage The user's original request.
     * @param conversationHistory Prior messages in the conversation (for context).
     * @param modelId The AI model ID to use (from the Agent role assignment).
     * @param scopePath The active Target Context directory path.
     * @param enableDeepThinking Whether to inject extended thinking prompts.
     * @param userAttachments Optional image attachments to include in the first user message.
     *        Should contain [AttachmentMeta] with [AttachmentMeta.base64Data] populated.
     * @param customSystemPrompt When non-null, overrides the default tier-based system prompt.
     *        This allows users to inject custom personas via the System Prompt Studio.
     * @param workerPersona When non-null and non-blank, prepended to the tier-based system prompt
     *        to give this agent instance a dynamic role (e.g. "Senior Web Researcher").
     *        Used by the SwarmOrchestrator to assign domain-specific personas to workers.
     * @return A [Flow] of [AgentEvent]s representing the agent's progress.
     */
    fun execute(
        userMessage: String,
        conversationHistory: List<ChatMessage> = emptyList(),
        modelId: String,
        scopePath: String,
        enableDeepThinking: Boolean = false,
        userAttachments: List<AttachmentMeta> = emptyList(),
        customSystemPrompt: String? = null,
        workerPersona: String? = null,
        userContext: String? = null
    ): Flow<AgentEvent> = channelFlow {
        send(AgentEvent.Started)

        val model = ModelRegistry.findModelById(modelId)
            ?: run {
                send(AgentEvent.Error("Unknown model: $modelId"))
                return@channelFlow
            }

        // Select tier-appropriate system prompt, or use the custom override
        val baseSystemPrompt = customSystemPrompt?.takeIf { it.isNotBlank() }
            ?: when (model.tier) {
                ModelTier.ORCHESTRATOR -> ORCHESTRATOR_SYSTEM_PROMPT
                ModelTier.EXECUTOR -> EXECUTOR_SYSTEM_PROMPT
                ModelTier.FAST -> FAST_SYSTEM_PROMPT
            }

        // If a specific worker persona is assigned (e.g. by the SwarmOrchestrator), prepend it
        // so the agent adopts the correct domain role before applying operational rules.
        val effectiveBasePrompt = if (!workerPersona.isNullOrBlank()) {
            "You are $workerPersona.\n\n${baseSystemPrompt.trimIndent()}"
        } else {
            baseSystemPrompt.trimIndent()
        }

        // Build the complete system prompt with tool definitions
        val toolDefs = toolManager.getToolDefinitions()
        // Register tool definitions with the brain so it is aware of all available capabilities
        smartLearningBridge?.registerTools(toolDefs)
        val toolSchemaText = toolDefs.joinToString("\n\n") { tool ->
            buildString {
                appendLine("### Tool: ${tool.name}")
                appendLine(tool.description)
                appendLine("Parameters:")
                tool.parameters.forEach { param ->
                    val reqTag = if (param.required) " (required)" else " (optional)"
                    appendLine("  - ${param.name}: ${param.type}$reqTag — ${param.description}")
                }
            }
        }

        // Compute brain context enrichment outside buildString (it's a suspend call)
        val brainContext = try {
            smartLearningBridge?.buildFullContextEnrichment() ?: ""
        } catch (_: Exception) { "" }

        val systemPrompt = buildString {
            append(effectiveBasePrompt)
            append(AGENT_IDENTITY_CONTEXT)
            // User context — personalise advice/style to the specific person if provided
            if (!userContext.isNullOrBlank()) {
                appendLine()
                appendLine()
                appendLine("## User Context")
                appendLine("The person you are helping has shared the following about themselves:")
                appendLine(userContext.trim())
                appendLine("Tailor your explanations, code examples, and tone to match their background.")
            }
            // Anti-lecture directive — always injected first; prevents the agent from
            // refusing tasks or lecturing the user about missing Android permissions.
            append(ANTI_LECTURE_DIRECTIVE)
            // Self-verification directive — forces the agent to verify results, check exit codes,
            // and read back changes instead of blindly assuming success.
            append(SELF_VERIFY_DIRECTIVE)
            // Agent V2 execution framework — phased execution + orchestration + quality gates.
            append(AGENT_V2_EXECUTION_PROTOCOL)
            // Autonomous memory directive — always injected so the agent proactively manages memory
            if (memoryManager != null) {
                append(MEMORY_DIRECTIVE.trimIndent())
            }
            // Context hydration — inject long-term knowledge before the first iteration
            memoryManager?.buildKnowledgeContext()?.let { knowledge ->
                appendLine()
                append(knowledge)
            }
            // ═══════════════════════════════════════════════════════════════
            // 🧠 SMART LEARNING BRIDGE CONTEXT INJECTION
            // يحقن وعي الأدوات + ذاكرة التنفيذ + أفضل الممارسات المكتسبة
            // هذا ما يجعل الـ Agent يتصرف كـ Claude Code / GitHub Copilot Agent
            // ═══════════════════════════════════════════════════════════════
            if (brainContext.isNotBlank()) {
                appendLine()
                append(brainContext)
            }
            // Tool routing directory — prevents hallucinated use of codebase tools for OS tasks
            append(TOOL_DIRECTORY.trimIndent())
            appendLine()
            appendLine()
            appendLine("## Scope & Path Context")
            appendLine("Your active Target Context (working directory root) is: `$scopePath`")
            appendLine("Use this absolute path as the prefix for all file tool arguments.")
            appendLine("If the user references this path directly, it maps to your scope root `/`.")
            appendLine("Accepted formats for file paths:")
            appendLine("  • Full absolute path: `$scopePath/app/src/main/AndroidManifest.xml`")
            appendLine("  • Bare relative path (no leading /): `app/src/main/AndroidManifest.xml` (auto-prefixed)")
            appendLine()
            appendLine("## Available Tools")
            appendLine(toolSchemaText)
            if (enableDeepThinking && model.supportsThinking) {
                append(DEEP_THINKING_SUFFIX.trimIndent())
            }
        }

        // Initialize the conversation
        val messages = mutableListOf<ChatMessage>().apply {
            addAll(conversationHistory)
            add(ChatMessage(
                role = MessageRole.USER,
                content = userMessage,
                attachments = userAttachments
            ))
        }
        var sessionDigest = ""
        var lastDigestMessageCount = 0

        // Resolve the API key for this model's provider (injected into every request)
        val resolvedApiKey: String? = apiKeyRepository?.getApiKey(model.provider)

        var iteration = 0
        var totalTokensUsed = 0

        // Wall-clock start time for timeout enforcement
        val startTimeMs = System.currentTimeMillis()

        // Loop-detection: tracks how many times each identical tool call has appeared.
        // Key = "toolName:sortedArgs" fingerprint; value = occurrence count.
        val toolCallCounts = mutableMapOf<String, Int>()

        // No-progress detection: counts consecutive iterations where the agent only called
        // read-only / observation tools (e.g. dump_tree, read_file, search) without taking
        // any write/action tool. 3+ read-only-only iterations = agent is stuck inspecting.
        var consecutiveReadOnlyIterations = 0

        // ── ReAct Loop ──
        while (iteration < config.maxIterations) {
            iteration++

            // ── Wall-clock timeout check ──
            config.maxExecutionTimeMs?.let { timeoutMs ->
                if (System.currentTimeMillis() - startTimeMs >= timeoutMs) {
                    send(AgentEvent.Error(
                        "Agent execution timed out after ${timeoutMs / 1_000}s. " +
                        "Use the ⏹ stop button to cancel a run at any time."
                    ))
                    return@channelFlow
                }
            }

            // Brief pause between iterations to avoid bursting free-tier rate limits
            // (e.g. GitHub Models / Azure inference). Skipped on the very first call.
            // Delay scales with model tier: FAST=100ms, EXECUTOR=250ms, ORCHESTRATOR=500ms.
            if (iteration > 1) delay(interCallDelayFor(model.tier))
            send(AgentEvent.Thinking(iteration = iteration))

            // Token budget enforcement
            if (config.tokenBudget != null && totalTokensUsed >= config.tokenBudget) {
                send(AgentEvent.Error(
                    "Token budget of ${config.tokenBudget} tokens exhausted after $iteration iterations."
                ))
                return@channelFlow
            }

            val immediateWindow = config.recentMessagesWindow.coerceAtLeast(2)
            if (messages.size - lastDigestMessageCount >= config.sessionDigestUpdateEveryNMessages) {
                val olderHistory = if (messages.size > immediateWindow) {
                    messages.dropLast(immediateWindow)
                } else {
                    emptyList()
                }
                sessionDigest = buildSessionDigest(
                    messages = olderHistory,
                    maxChars = config.sessionDigestMaxChars,
                    maxMessages = config.sessionDigestMaxMessages
                )
                lastDigestMessageCount = messages.size
            }

            val hierarchicalMessages = if (messages.size > immediateWindow) {
                messages.takeLast(immediateWindow)
            } else {
                messages.toList()
            }

            // Context window trimming — drop oldest non-system messages when approaching limit
            val trimmedMessages = if (config.enableMemoryTrimming) {
                trimMessagesForContextWindow(hierarchicalMessages, model.contextWindow - config.contextWindowBuffer)
            } else {
                hierarchicalMessages
            }

            val effectiveSystemPrompt = if (sessionDigest.isBlank()) {
                systemPrompt
            } else {
                buildString {
                    append(systemPrompt)
                    appendLine()
                    appendLine()
                    appendLine("## Session Digest (hierarchical context)")
                    append(sessionDigest)
                }
            }

            val request = CompletionRequest(
                modelId = modelId,
                messages = trimmedMessages,
                systemPrompt = effectiveSystemPrompt,
                maxTokens = model.maxOutputTokens,
                enableThinking = enableDeepThinking && model.supportsThinking,
                targetContext = scopePath,
                apiKey = resolvedApiKey,
                tools = toolDefs
            )

            // ── API call with retry/backoff ──
            val response = callWithRetry(request, iteration,
                onStreamChunk = { delta -> send(AgentEvent.StreamChunk(delta)) }
            ) { errorMsg ->
                send(AgentEvent.Error(errorMsg))
            } ?: return@channelFlow

            // Track token usage
            response.tokensUsed?.let { usage ->
                totalTokensUsed += usage.totalTokens
                send(AgentEvent.TokenUsageUpdate(
                    iterationTokens = usage.totalTokens,
                    totalTokens = totalTokensUsed,
                    budget = config.tokenBudget
                ))
            }

            // ── Emit thinking content if present ──
            response.thinkingContent?.let { thinking ->
                send(AgentEvent.ThinkingBlock(thinking))
            }

            // ── No tool calls → Final answer ──
            if (response.toolCalls.isEmpty()) {
                val assistantMessage = ChatMessage(
                    role = MessageRole.ASSISTANT,
                    content = response.content,
                    thinkingContent = response.thinkingContent
                )
                messages.add(assistantMessage)

                // ── Self-Reflection pass (optional) ──
                // When enabled, run a lightweight critic pass that evaluates the draft
                // answer and optionally rewrites it with targeted improvements.
                val finalContent = if (config.enableSelfReflection && response.content.isNotBlank()) {
                    send(AgentEvent.Reflecting(draftLength = response.content.length))
                    val originalUserMessage = messages.firstOrNull {
                        it.role == MessageRole.USER
                    }?.content ?: userMessage

                    val criticPrompt = buildString {
                        appendLine("**Original user request:**")
                        appendLine(originalUserMessage)
                        appendLine()
                        appendLine("**Draft answer to review:**")
                        appendLine(response.content)
                    }

                    val criticRequest = CompletionRequest(
                        modelId = modelId,
                        messages = listOf(
                            ChatMessage(role = MessageRole.USER, content = criticPrompt)
                        ),
                        systemPrompt = CRITIC_SYSTEM_PROMPT.trimIndent(),
                        maxTokens = minOf(model.maxOutputTokens, 8_192),
                        enableThinking = false,
                        targetContext = scopePath,
                        apiKey = resolvedApiKey,
                        tools = emptyList()
                    )

                    val criticResponse = try {
                        callWithRetry(criticRequest, iteration + 1) { /* ignore critic errors */ }
                    } catch (_: Exception) {
                        null
                    }

                    criticResponse?.tokensUsed?.let { usage ->
                        totalTokensUsed += usage.totalTokens
                        send(AgentEvent.TokenUsageUpdate(
                            iterationTokens = usage.totalTokens,
                            totalTokens = totalTokensUsed,
                            budget = config.tokenBudget
                        ))
                    }

                    val criticText = criticResponse?.content?.trim().orEmpty()
                    val improvedAnswerMarker = "IMPROVED_ANSWER:"
                    val verdictNeedsImprovement = criticText.contains("VERDICT: NEEDS_IMPROVEMENT", ignoreCase = true)

                    if (verdictNeedsImprovement) {
                        val improvedIdx = criticText.indexOf(improvedAnswerMarker, ignoreCase = true)
                        if (improvedIdx >= 0) {
                            criticText.substring(improvedIdx + improvedAnswerMarker.length).trim()
                                .takeIf { it.isNotBlank() } ?: response.content
                        } else {
                            response.content
                        }
                    } else {
                        // APPROVED or unparseable response — use original answer unchanged
                        response.content
                    }
                } else {
                    response.content
                }

                send(AgentEvent.FinalAnswer(
                    content = finalContent,
                    totalIterations = iteration,
                    totalTokensUsed = totalTokensUsed,
                    conversationHistory = messages.toList()
                ))
                return@channelFlow
            }

            // ── Tool calls present → Execute and observe ──
            val assistantMessage = ChatMessage(
                role = MessageRole.ASSISTANT,
                content = response.content,
                toolCalls = response.toolCalls,
                thinkingContent = response.thinkingContent
            )
            messages.add(assistantMessage)

            val toolResults = mutableListOf<ToolCallResult>()

            // ── Loop detection — check all fingerprints upfront (before any I/O) ──
            for (toolCall in response.toolCalls) {
                val fingerprint = buildString {
                    append(toolCall.name)
                    append(':')
                    toolCall.arguments.entries.sortedBy { it.key }.forEach { (k, v) ->
                        append(k).append('=').append(v.toString()).append(',')
                    }
                }
                val callCount = (toolCallCounts[fingerprint] ?: 0) + 1
                toolCallCounts[fingerprint] = callCount
                if (callCount > config.maxRepeatedToolCalls) {
                    send(AgentEvent.Error(
                        "🔄 Loop detected: tool '${toolCall.name}' called $callCount times " +
                        "with identical arguments. Aborting to prevent an infinite loop. " +
                        "Use the ⏹ stop button to cancel a run at any time."
                    ))
                    return@channelFlow
                }
            }

            // Emit ToolExecution events for all calls (before we start executing them)
            for (toolCall in response.toolCalls) {
                send(AgentEvent.ToolExecution(
                    toolName = toolCall.name,
                    arguments = toolCall.arguments,
                    iteration = iteration
                ))
                // سجّل وقت بداية التنفيذ بمعرف فريد لكل استدعاء (لدعم التوازي)
                smartLearningBridge?.onToolExecutionStart(toolCall.name, callId = toolCall.id)
            }

            // ── Execute tools: parallel when enabled and >1 call, sequential otherwise ──
            val rawResults: List<com.omnidev.workspace.data.tools.ToolExecutionResult> =
                if (config.enableParallelToolExecution && response.toolCalls.size > 1) {
                    // Run all tool calls concurrently. coroutineScope propagates cancellation
                    // cleanly — if the parent Flow is cancelled mid-flight, all async blocks
                    // are cancelled immediately.
                    coroutineScope {
                        response.toolCalls.map { toolCall ->
                            async {
                                val result = toolOrchestrator.executeTool(
                                    toolName = toolCall.name,
                                    cacheKey = null,
                                    timeoutMs = config.toolExecutionTimeoutMs,
                                    maxRetries = config.toolExecutionMaxRetries,
                                    baseRetryDelayMs = config.toolExecutionBaseRetryDelayMs
                                ) {
                                    toolManager.executeTool(
                                        name = toolCall.name,
                                        arguments = toolCall.arguments,
                                        scopePath = scopePath
                                    )
                                }
                                if (result.isSuccess) {
                                    result.getOrThrow()
                                } else {
                                    com.omnidev.workspace.data.tools.ToolExecutionResult(
                                        output = result.exceptionOrNull()?.message ?: "Tool execution failed",
                                        isError = true
                                    )
                                }
                            }
                        }.awaitAll()
                    }
                } else {
                    // Sequential fallback for single calls or when parallel is disabled
                    response.toolCalls.map { toolCall ->
                        val result = toolOrchestrator.executeTool(
                            toolName = toolCall.name,
                            cacheKey = null,
                            timeoutMs = config.toolExecutionTimeoutMs,
                            maxRetries = config.toolExecutionMaxRetries,
                            baseRetryDelayMs = config.toolExecutionBaseRetryDelayMs
                        ) {
                            toolManager.executeTool(
                                name = toolCall.name,
                                arguments = toolCall.arguments,
                                scopePath = scopePath
                            )
                        }
                        if (result.isSuccess) {
                            result.getOrThrow()
                        } else {
                            com.omnidev.workspace.data.tools.ToolExecutionResult(
                                output = result.exceptionOrNull()?.message ?: "Tool execution failed",
                                isError = true
                            )
                        }
                    }
                }

            // Collect results in original toolCall order, emit ToolResult events
            for ((toolCall, result) in response.toolCalls.zip(rawResults)) {
                val toolCallResult = ToolCallResult(
                    toolCallId = toolCall.id,
                    toolName = toolCall.name,
                    output = result.output,
                    isError = result.isError
                )
                toolResults.add(toolCallResult)

                send(AgentEvent.ToolResult(
                    toolName = toolCall.name,
                    output = result.output,
                    isError = result.isError,
                    iteration = iteration
                ))

                // ═══════════════════════════════════════════════════════════════
                // 🧠 SMART LEARNING HOOK — يتعلم من كل عملية تنفيذ
                // يُرسل نتيجة التنفيذ لـ SmartLearningBridge لتحديث:
                // - ToolExecutionJournal (الذاكرة الدائمة)
                // - ToolAwarenessEngine (الوعي بالأدوات)
                // - ToolIntelligenceEngine (التعلم بالتعزيز)
                // - ToolMachineLearningEngine (التنبؤ)
                // ═══════════════════════════════════════════════════════════════
                smartLearningBridge?.let { bridge ->
                    val redactedContext = userMessage.take(200)
                        // Redact key=value / key: value style (headers, assignments)
                        .replace(Regex("(?i)(key|token|secret|password|otp|bearer)[=:\\s]+\\S+"), "$1=[REDACTED]")
                        // Redact JSON string values for sensitive keys
                        .replace(Regex("(?i)\"(api_?key|token|secret|password|otp)\"\\s*:\\s*\"[^\"]+\""), "\"$1\":\"[REDACTED]\"")
                        // Redact URL query params
                        .replace(Regex("(?i)(key|token|secret|password|otp)=([^&\\s\"]+)"), "$1=[REDACTED]")
                    bridge.onToolExecutionEnd(
                        toolName = toolCall.name,
                        parameters = toolCall.arguments,
                        result = result,
                        agentContext = redactedContext,
                        callId = toolCall.id
                    )
                }
            }

            // Add tool results as a TOOL message for the next iteration
            val hasToolErrors = toolResults.any { it.isError }
            val toolContent = buildString {
                append(toolResults.joinToString("\n\n") { r ->
                    "[${r.toolName}] ${if (r.isError) "ERROR: " else ""}${r.output}"
                })
                if (hasToolErrors) {
                    appendLine()
                    appendLine()
                    append(
                        "CRITICAL DIRECTIVE: One or more tools above returned an error. " +
                        "You MUST explicitly report each failure to the user in your final response. " +
                        "NEVER claim a task succeeded when its tool observation shows an error or exception."
                    )
                }
            }

            // ── No-progress / read-only loop detection ──
            // Detect when the agent is stuck only inspecting (dump_tree) without acting.
            // We check semantic_ui calls: if EVERY call this iteration uses a read-only
            // action (dump_tree, get_node, find_node), increment the counter; reset on any
            // click/type/tap/scroll/press action.
            val semanticUiCalls = response.toolCalls.filter { it.name == "semantic_ui" }
            val readOnlyActions = setOf("dump_tree", "get_node", "find_node", "list_nodes")
            val allSemUiAreReadOnly = semanticUiCalls.isNotEmpty() &&
                semanticUiCalls.all { tc ->
                    // Treat missing/null action as read-only (conservative — no write assumed)
                    val action = tc.arguments["action"]?.toString()
                    action == null || action in readOnlyActions
                }
            // Non-semantic_ui tools (write_file, execute_command, etc.) always count as action
            val hasNonSemUiTool = response.toolCalls.any { it.name != "semantic_ui" }
            if (allSemUiAreReadOnly && !hasNonSemUiTool) {
                consecutiveReadOnlyIterations++
                if (consecutiveReadOnlyIterations >= NO_PROGRESS_THRESHOLD) {
                    send(AgentEvent.Error(
                        "🔍 Agent stuck: called semantic_ui read-only operations $consecutiveReadOnlyIterations " +
                        "consecutive times without taking any action (click/type/scroll/etc). " +
                        "The agent may not know how to interact with the current screen. " +
                        "Try rephrasing the task or providing a more specific instruction."
                    ))
                    return@channelFlow
                }
            } else {
                consecutiveReadOnlyIterations = 0
            }

            val toolMessage = ChatMessage(
                role = MessageRole.TOOL,
                content = toolContent,
                toolResults = toolResults
            )
            messages.add(toolMessage)
        }

        // ── Max iterations reached ──
        send(AgentEvent.Error(
            "Agent reached maximum iterations (${config.maxIterations}) without completing. " +
                "Consider using a Swarm run for complex tasks, or increase maxIterations in AgentConfig."
        ))
    }

    /**
     * Wraps an API call with exponential backoff retry logic.
     *
     * Rate-limit (429) errors receive special treatment: they use a much longer initial
     * delay ([RATE_LIMIT_BASE_DELAY_MS]) and up to [RATE_LIMIT_MAX_RETRIES] extra attempts
     * beyond the normal retry budget, because 429s typically require waiting 30–60 seconds.
     *
     * When [streamingCompletionProvider] is available, text delta chunks are emitted via
     * [onStreamChunk] as they arrive, enabling real-time streaming in the UI.
     *
     * @param request The completion request.
     * @param iteration The current loop iteration number (for error messages).
     * @param onStreamChunk Called with each streaming text delta (no-op if not streaming).
     * @param onFatalError Called with the error message if all retries are exhausted.
     * @return The [CompletionResponse] on success, or null if all retries failed.
     */
    private suspend fun callWithRetry(
        request: CompletionRequest,
        iteration: Int,
        onStreamChunk: suspend (String) -> Unit = {},
        onFatalError: suspend (String) -> Unit
    ): CompletionResponse? {
        val normalMaxAttempts = if (config.enableRetry) config.maxRetries + 1 else 1
        // Rate-limit retries run in a separate budget: up to RATE_LIMIT_MAX_RETRIES extra attempts
        // with a much longer base delay so the 429 window has time to expire.
        var rateLimitAttemptsRemaining = if (config.enableRetry) RATE_LIMIT_MAX_RETRIES else 0
        var normalAttempt = 0

        while (true) {
            try {
                val response = if (config.maxIterationTimeMs != null) {
                    withTimeout(config.maxIterationTimeMs) {
                        if (streamingCompletionProvider != null) {
                            streamingCompletionProvider.invoke(request, onStreamChunk)
                        } else {
                            completionProvider(request)
                        }
                    }
                } else {
                    if (streamingCompletionProvider != null) {
                        streamingCompletionProvider.invoke(request, onStreamChunk)
                    } else {
                        completionProvider(request)
                    }
                }
                return response
            } catch (e: TimeoutCancellationException) {
                val timeoutSec = (config.maxIterationTimeMs ?: 90_000L) / 1_000
                onFatalError(
                    "⏱ LLM call timed out after ${timeoutSec}s at iteration $iteration. " +
                    "The model API did not respond in time. Try again or use ⏹ to cancel."
                )
                return null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val isRateLimit = e.message?.contains("Rate limit exceeded", ignoreCase = true) == true

                if (isRateLimit && rateLimitAttemptsRemaining > 0) {
                    // Rate-limit path: long fixed delay then retry (don't consume normal retry budget)
                    rateLimitAttemptsRemaining--
                    val retryNum = RATE_LIMIT_MAX_RETRIES - rateLimitAttemptsRemaining
                    val delayMs = min(RATE_LIMIT_BASE_DELAY_MS * retryNum, RATE_LIMIT_MAX_DELAY_MS)
                    delay(delayMs)
                    continue
                }

                // Normal error path: consume normal retry budget with exponential backoff
                val isLastNormalAttempt = normalAttempt >= normalMaxAttempts - 1
                if (isLastNormalAttempt) {
                    if (isRateLimit) {
                        // Rate limits are expected — log as warning, not error, since they
                        // are normal API behaviour and not a code defect.
                        com.omnidev.workspace.data.debug.DebugLogManager.appendWarning(
                            "AgentPipeline",
                            "Rate limit exhausted after all retries (iteration $iteration). " +
                            "Please wait a few minutes before sending another message."
                        )
                        onFatalError(
                            "Rate limit reached. All retry attempts have been exhausted " +
                            "(iteration $iteration). Please wait a few minutes and try again."
                        )
                    } else {
                        com.omnidev.workspace.data.debug.DebugLogManager.appendError("AgentPipeline", e)
                        val isNetworkTimeout = e is java.net.SocketTimeoutException ||
                                e is java.net.SocketException ||
                                e is java.io.IOException && e.message?.contains("timeout", ignoreCase = true) == true
                        val userMsg = if (isNetworkTimeout) {
                            "Network timeout after $normalMaxAttempts attempt(s) (iteration $iteration). " +
                            "Check your internet connection and try again."
                        } else {
                            "API call failed after $normalMaxAttempts attempt(s) (iteration $iteration): ${e.message}"
                        }
                        onFatalError(userMsg)
                    }
                    return null
                }
                // Exponential backoff: 500ms, 1s, 2s, 4s, ... (bit-shift for integer powers of 2)
                val delayMs = config.baseRetryDelayMs * (1L shl normalAttempt)
                delay(min(delayMs, 30_000L))
                normalAttempt++
            }
        }
    }

    /**
     * Trims the conversation message list to fit within [maxTokens] by removing
     * the oldest non-system messages first. Always preserves the first (user) message
     * to maintain task continuity.
     *
     * **Important:** In a ReAct loop an ASSISTANT message that contains tool calls MUST be
     * immediately followed by one or more TOOL messages. Removing the ASSISTANT message
     * while leaving its TOOL reply(ies) behind produces an orphaned tool result which
     * causes `400 Bad Request` errors from OpenAI and Anthropic APIs.
     * This function therefore removes ASSISTANT+TOOL groups atomically: when an ASSISTANT
     * message with tool calls is evicted, all immediately-following TOOL messages are also
     * removed in the same pass before checking the budget again.
     *
     * Note: Character counts are used as a heuristic proxy for token counts (≈ 4 chars/token).
     * A production implementation would use the provider's tokenizer for exact counts.
     */
    private fun trimMessagesForContextWindow(
        messages: List<ChatMessage>,
        maxTokens: Int
    ): List<ChatMessage> {
        // Rough estimate: 4 characters ≈ 1 token
        val maxChars = maxTokens * 4
        val totalChars = messages.sumOf { it.content.length }

        if (totalChars <= maxChars) return messages

        // Detach the first message (original user task) — it must always be preserved.
        val result = messages.toMutableList()
        val keepFirst = result.removeAt(0)

        // Evict oldest messages until we're within budget, always keeping at least 2 messages
        // so the last ASSISTANT reply is never stranded without the preceding USER turn.
        while (result.sumOf { it.content.length } + keepFirst.content.length > maxChars
            && result.size > 2) {

            val evicted = result.removeAt(0)

            // If the evicted ASSISTANT message had tool calls, evict all immediately-following
            // TOOL messages too. Leaving orphaned TOOL messages causes 400 Bad Request errors
            // from OpenAI/Anthropic because the API requires tool results to be preceded by
            // the exact ASSISTANT turn that issued the tool call.
            if (evicted.role == MessageRole.ASSISTANT && !evicted.toolCalls.isNullOrEmpty()) {
                while (result.isNotEmpty() && result[0].role == MessageRole.TOOL) {
                    result.removeAt(0)
                }
            }
        }

        result.add(0, keepFirst)
        return result
    }

    private fun buildSessionDigest(
        messages: List<ChatMessage>,
        maxChars: Int,
        maxMessages: Int
    ): String {
        if (messages.isEmpty()) return ""
        val bounded = messages.takeLast(maxMessages.coerceAtLeast(6))

        val userGoals = bounded
            .filter { it.role == MessageRole.USER }
            .takeLast(4)
            .map { it.content.trim().replace("\n", " ").take(220) }
            .filter { it.isNotBlank() }

        val toolObservations = bounded
            .filter { it.role == MessageRole.TOOL }
            .takeLast(5)
            .map { it.content.trim().replace("\n", " ").take(220) }
            .filter { it.isNotBlank() }

        val decisions = bounded
            .filter { it.role == MessageRole.ASSISTANT }
            .takeLast(4)
            .map { it.content.trim().replace("\n", " ").take(220) }
            .filter { it.isNotBlank() }

        val digest = buildString {
            appendLine("Goals:")
            if (userGoals.isEmpty()) appendLine("- (none)")
            userGoals.forEach { appendLine("- $it") }
            appendLine("Recent decisions:")
            if (decisions.isEmpty()) appendLine("- (none)")
            decisions.forEach { appendLine("- $it") }
            appendLine("Recent tool observations:")
            if (toolObservations.isEmpty()) appendLine("- (none)")
            toolObservations.forEach { appendLine("- $it") }
        }

        return if (digest.length > maxChars) digest.take(maxChars) + "\n[...digest truncated...]" else digest
    }
}

/**
 * Events emitted by the [AgentPipeline] during ReAct loop execution.
 * These drive the UI's real-time streaming display.
 */
sealed class AgentEvent {
    /** The agent loop has started. */
    data object Started : AgentEvent()

    /** The agent is reasoning (sending to model). */
    data class Thinking(val iteration: Int) : AgentEvent()

    /** Extended thinking content from the model. */
    data class ThinkingBlock(val content: String) : AgentEvent()

    /** A tool is being executed. */
    data class ToolExecution(
        val toolName: String,
        val arguments: Map<String, String>,
        val iteration: Int
    ) : AgentEvent()

    /** The result of a tool execution. */
    data class ToolResult(
        val toolName: String,
        val output: String,
        val isError: Boolean,
        val iteration: Int
    ) : AgentEvent()

    /** Token usage stats after an API call. */
    data class TokenUsageUpdate(
        val iterationTokens: Int,
        val totalTokens: Int,
        val budget: Int?
    ) : AgentEvent()

    /** A streaming text delta chunk from the model's SSE response. */
    data class StreamChunk(val delta: String) : AgentEvent()

    /** The agent has produced a final answer. */
    data class FinalAnswer(
        val content: String,
        val totalIterations: Int,
        val totalTokensUsed: Int,
        val conversationHistory: List<ChatMessage>
    ) : AgentEvent()

    /** The agent is performing a self-reflection critique of its draft answer. */
    data class Reflecting(val draftLength: Int) : AgentEvent()

    /** An unrecoverable error occurred. */
    data class Error(val message: String) : AgentEvent()
}

/**
 * Represents a sub-task in Swarm mode, assigned by the Orchestrator to a Worker.
 */
@Serializable
data class SwarmTask(
    val id: String,
    val description: String,
    val priority: Int = 0,
    val dependencies: List<String> = emptyList(),
    val status: SwarmTaskStatus = SwarmTaskStatus.PENDING,
    /** Persona the worker agent should adopt for this sub-task (e.g. "Senior Web Researcher"). */
    val requiredPersona: String = ""
)

@Serializable
enum class SwarmTaskStatus {
    PENDING, IN_PROGRESS, COMPLETED, FAILED
}
