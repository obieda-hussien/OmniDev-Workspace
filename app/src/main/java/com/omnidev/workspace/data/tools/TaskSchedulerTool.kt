package com.omnidev.workspace.data.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList

/**
 * TaskSchedulerTool — v2.0 "Execution Engine Edition"
 *
 * KEY IMPROVEMENTS:
 * 1. EXECUTION BRIDGE: `executionCallback` — set by MainActivity after AgentPipeline
 *    is initialized. OmniSyncService calls this to actually execute task prompts.
 *    Without this, tasks were only "marked running" but never executed.
 *
 * 2. EXECUTION SUMMARY: `ExecutionSummary` data class with full telemetry:
 *    start/end times, tool count, tool names, result, iterations used.
 *
 * 3. EXECUTION HISTORY: Each task stores last 10 `ExecutionSummary` records.
 *
 * 4. NOTIFICATION BRIDGE: `notificationCallback` — posts rich summaries to system
 *    notifications with start time, end time, duration, tools used.
 *
 * 5. TASK DEPENDENCY EXECUTION: Dependencies are resolved BEFORE execution starts.
 *
 * 6. EXECUTION CONTEXT: Tasks get dependency results injected into their prompt.
 */
object TaskSchedulerTool {

    // ── Enumerations ─────────────────────────────────────────────────────

    enum class TaskStatus {
        PENDING, RUNNING, COMPLETED, FAILED, CANCELLED, PAUSED, WAITING_DEPENDENCY
    }

    enum class TaskPriority(val label: String, val order: Int) {
        LOW("Low", 3), NORMAL("Normal", 2), HIGH("High", 1), CRITICAL("Critical", 0)
    }

    enum class RecurrenceType {
        ONCE, INTERVAL_MINUTES, DAILY, WEEKLY, MONTHLY, CRON
    }

    // ── Execution Bridge (SET BY MAINACTIVITY AFTER AGENTPIPELINE IS READY) ─

    /**
     * Called by OmniSyncService.performSync() to actually execute a task's prompt.
     * Must be set in MainActivity.onCreate() after AgentPipeline is initialized:
     *
     * ```kotlin
     * TaskSchedulerTool.executionCallback = { task ->
     *     var toolsUsed = 0
     *     val toolNames = mutableListOf<String>()
     *     val startTime = System.currentTimeMillis()
     *     var result = ""
     *     var iterations = 0
     *
     *     agentPipeline.execute(
     *         userMessage = task.prompt,
     *         modelId = modelId,
     *         scopePath = scopePath
     *     ).collect { event ->
     *         when (event) {
     *             is AgentEvent.ToolExecution -> { toolsUsed++; toolNames.add(event.toolName) }
     *             is AgentEvent.FinalAnswer -> { result = event.content; iterations = event.totalIterations }
     *         }
     *     }
     *
     *     TaskSchedulerTool.ExecutionSummary(
     *         taskId = task.id,
     *         startTimeMs = startTime,
     *         endTimeMs = System.currentTimeMillis(),
     *         toolsUsed = toolsUsed,
     *         toolNames = toolNames,
     *         result = result,
     *         isSuccess = true,
     *         iterationsUsed = iterations
     *     )
     * }
     * ```
     */
    @Volatile
    var executionCallback: (suspend (ScheduledTask) -> ExecutionSummary)? = null

    /**
     * Called after task execution to post a rich completion notification.
     * Set by OmniSyncService or MainActivity.
     *
     * ```kotlin
     * TaskSchedulerTool.notificationCallback = { task, summary ->
     *     OmniSyncService.postCompletionNotification(context, task, summary)
     * }
     * ```
     */
    @Volatile
    var notificationCallback: ((ScheduledTask, ExecutionSummary) -> Unit)? = null

    // ── Execution Summary ─────────────────────────────────────────────────

    data class ExecutionSummary(
        val taskId: String,
        val taskName: String = "",
        val startTimeMs: Long,
        val endTimeMs: Long,
        val toolsUsed: Int,
        val toolNames: List<String>,
        val result: String,
        val isSuccess: Boolean,
        val iterationsUsed: Int = 0,
        val errorMessage: String? = null
    ) {
        val durationMs: Long get() = endTimeMs - startTimeMs
        val durationSec: Long get() = durationMs / 1000

        private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

        fun toNotificationTitle(): String =
            if (isSuccess) "✅ ${taskName.take(40)}" else "❌ فشلت: ${taskName.take(35)}"

        fun toNotificationBody(): String = buildString {
            appendLine("⏱ بدأ: ${timeFmt.format(Date(startTimeMs))} | انتهى: ${timeFmt.format(Date(endTimeMs))} (${durationSec}s)")
            appendLine("🛠 أدوات: $toolsUsed | تكرارات: $iterationsUsed")
            if (toolNames.isNotEmpty()) {
                appendLine("📋 ${toolNames.distinct().take(4).joinToString(", ")}${if(toolNames.distinct().size > 4) "..." else ""}")
            }
            if (!isSuccess && errorMessage != null) {
                appendLine("💥 ${errorMessage.take(100)}")
            } else if (result.isNotBlank()) {
                appendLine("💬 ${result.take(120)}${if(result.length > 120) "..." else ""}")
            }
        }.trimEnd()

        fun toHistoryEntry(): String {
            val statusIcon = if (isSuccess) "✅" else "❌"
            val startStr = timeFmt.format(Date(startTimeMs))
            return "$statusIcon ${startStr} (${durationSec}s, $toolsUsed tools, $iterationsUsed iter): ${result.take(100)}"
        }

        fun toMarkdownReport(): String = buildString {
            appendLine("## ${if(isSuccess) "✅" else "❌"} Task: $taskName")
            appendLine("| Field | Value |")
            appendLine("|-------|-------|")
            appendLine("| Start | ${timeFmt.format(Date(startTimeMs))} |")
            appendLine("| End | ${timeFmt.format(Date(endTimeMs))} |")
            appendLine("| Duration | ${durationSec}s |")
            appendLine("| Tools Used | $toolsUsed |")
            appendLine("| Iterations | $iterationsUsed |")
            if (toolNames.isNotEmpty()) {
                appendLine("| Tool Names | ${toolNames.distinct().joinToString(", ")} |")
            }
            appendLine()
            appendLine("### Result")
            appendLine(result.ifBlank { "(no result)" })
            if (!isSuccess && errorMessage != null) {
                appendLine()
                appendLine("### Error")
                appendLine(errorMessage)
            }
        }
    }

    // ── Data Model ───────────────────────────────────────────────────────

    data class ScheduledTask(
        val id: String,
        val name: String,
        val prompt: String,
        val scheduledTimeMillis: Long,
        val createdAtMillis: Long = System.currentTimeMillis(),

        val recurrenceType: RecurrenceType = RecurrenceType.ONCE,
        val timeOfDay: String? = null,
        val dayOfWeek: Int? = null,
        val dayOfMonth: Int? = null,
        val repeatIntervalMinutes: Int? = null,
        val cronExpression: String? = null,
        val maxRuns: Int? = null,
        val runCount: Int = 0,

        val priority: TaskPriority = TaskPriority.NORMAL,
        val tags: List<String> = emptyList(),
        val maxRetries: Int = 0,
        val retryCount: Int = 0,
        val timeoutMinutes: Int? = null,
        val dependsOn: List<String> = emptyList(),
        val notifyOnComplete: Boolean = true,   // Default ON now
        val notifyOnFail: Boolean = true,

        val status: TaskStatus,
        val lastResult: String? = null,
        val startedAtMillis: Long? = null,
        val completedAtMillis: Long? = null,
        /** Rich execution summaries — newest first, max 10 */
        val executionSummaries: List<ExecutionSummary> = emptyList(),
        /** Plain text history (legacy) */
        val executionHistory: List<String> = emptyList()
    )

    private val tasks = CopyOnWriteArrayList<ScheduledTask>()

    private val _tasksFlow = MutableStateFlow<List<ScheduledTask>>(emptyList())
    val tasksFlow: StateFlow<List<ScheduledTask>> = _tasksFlow.asStateFlow()

    private fun notifyChanged() { _tasksFlow.value = tasks.toList() }

    // ── Tool Schema ───────────────────────────────────────────────────────

    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "task_scheduler",
            description = """
Schedule, execute, and monitor autonomous AI tasks with rich execution reporting.
IMPORTANT: Tasks are ACTUALLY EXECUTED through the AgentPipeline — the agent runs
the task's prompt autonomously and the result is saved with full telemetry.

After execution, notifications show:
  • Start time, end time, duration
  • Number of tools used and their names
  • Number of reasoning iterations
  • Result summary

Actions: schedule | list | cancel | status | pause | resume | run_now | retry |
         update | clear_completed | list_by_tag | list_by_priority |
         set_priority | add_tags | remove_tags | chain | get_summary
""".trimIndent(),
            parameters = listOf(
                ToolParameter("action", "string", "Action to perform (see description)", true),
                ToolParameter("name",   "string", "Task name (required for schedule)", false),
                ToolParameter("prompt", "string", "AI prompt to execute (required for schedule)", false),
                ToolParameter("delayMinutes", "string", "Minutes from now (default 0 = immediate)", false),
                ToolParameter("priority", "string", "low | normal | high | critical", false),
                ToolParameter("tags", "string", "Comma-separated labels", false),
                ToolParameter("maxRetries", "string", "Auto-retry count on failure (default 0)", false),
                ToolParameter("timeoutMinutes", "string", "Abort after N minutes running", false),
                ToolParameter("maxRuns", "string", "Stop repeating after N runs", false),
                ToolParameter("dependsOn", "string", "Comma-separated task IDs to depend on", false),
                ToolParameter("notifyOnComplete", "string", "true/false — notify on success", false),
                ToolParameter("notifyOnFail", "string", "true/false — notify on failure", false),
                ToolParameter("recurrence", "string", "once|interval|daily|weekly|monthly|cron", false),
                ToolParameter("repeatIntervalMinutes", "string", "Repeat every N minutes", false),
                ToolParameter("cronExpression", "string", "5-field CRON: 'min hour dom month dow'", false),
                ToolParameter("timeOfDay", "string", "HH:mm for daily/weekly/monthly", false),
                ToolParameter("dayOfWeek", "string", "1=Mon..7=Sun for weekly", false),
                ToolParameter("dayOfMonth", "string", "1-31 for monthly", false),
                ToolParameter("taskId", "string", "Task ID for most actions", false),
                ToolParameter("newName",   "string", "New name (update action)", false),
                ToolParameter("newPrompt", "string", "New prompt (update action)", false),
                ToolParameter("newTimeoutMinutes", "string", "New timeout (update)", false),
                ToolParameter("tag",           "string", "Tag filter for list_by_tag", false),
                ToolParameter("filterPriority","string", "Priority filter for list_by_priority", false),
                ToolParameter("taskIds", "string", "Comma-separated IDs to chain", false)
            )
        )
    )

    // ── Agent Dispatch ────────────────────────────────────────────────────

    suspend fun executeTool(name: String, arguments: Map<String, String>): ToolExecutionResult {
        if (name != "task_scheduler") return ToolExecutionResult("Unknown tool: $name", isError = true)
        val action = arguments["action"] ?: return ToolExecutionResult("'action' is required.", isError = true)

        val delayMinutes = (arguments["delayMinutes"]?.toIntOrNull() ?: 0).coerceAtLeast(0)
        val repeatInterval = arguments["repeatIntervalMinutes"]?.toIntOrNull()
        val priority = parsePriority(arguments["priority"])
        val tags = arguments["tags"]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        val maxRetries = arguments["maxRetries"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val timeoutMinutes = arguments["timeoutMinutes"]?.toIntOrNull()?.takeIf { it > 0 }
        val maxRuns = arguments["maxRuns"]?.toIntOrNull()?.takeIf { it > 0 }
        val dependsOn = arguments["dependsOn"]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        val notifyOnComplete = arguments["notifyOnComplete"]?.lowercase() != "false"
        val notifyOnFail = arguments["notifyOnFail"]?.lowercase() != "false"
        val recurrenceType = parseRecurrence(arguments["recurrence"])
        val dayOfWeek = arguments["dayOfWeek"]?.toIntOrNull()
        val dayOfMonth = arguments["dayOfMonth"]?.toIntOrNull()
        val filterPriority = arguments["filterPriority"]?.let { parsePriority(it) }
        val taskIds = arguments["taskIds"]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

        return execute(
            action = action,
            name = arguments["name"],
            prompt = arguments["prompt"],
            delayMinutes = delayMinutes,
            priority = priority,
            tags = tags,
            maxRetries = maxRetries,
            timeoutMinutes = timeoutMinutes,
            maxRuns = maxRuns,
            dependsOn = dependsOn,
            notifyOnComplete = notifyOnComplete,
            notifyOnFail = notifyOnFail,
            repeatIntervalMinutes = repeatInterval,
            recurrenceType = recurrenceType,
            cronExpression = arguments["cronExpression"],
            timeOfDay = arguments["timeOfDay"],
            dayOfWeek = dayOfWeek,
            dayOfMonth = dayOfMonth,
            taskId = arguments["taskId"],
            newName = arguments["newName"],
            newPrompt = arguments["newPrompt"],
            newTimeoutMinutes = arguments["newTimeoutMinutes"]?.toIntOrNull(),
            tag = arguments["tag"],
            filterPriority = filterPriority,
            taskIds = taskIds
        )
    }

    // ── Core Execute ──────────────────────────────────────────────────────

    suspend fun execute(
        action: String,
        name: String? = null,
        prompt: String? = null,
        delayMinutes: Int = 0,
        priority: TaskPriority = TaskPriority.NORMAL,
        tags: List<String> = emptyList(),
        maxRetries: Int = 0,
        timeoutMinutes: Int? = null,
        maxRuns: Int? = null,
        dependsOn: List<String> = emptyList(),
        notifyOnComplete: Boolean = true,
        notifyOnFail: Boolean = true,
        repeatIntervalMinutes: Int? = null,
        recurrenceType: RecurrenceType = RecurrenceType.ONCE,
        cronExpression: String? = null,
        timeOfDay: String? = null,
        dayOfWeek: Int? = null,
        dayOfMonth: Int? = null,
        taskId: String? = null,
        newName: String? = null,
        newPrompt: String? = null,
        newTimeoutMinutes: Int? = null,
        tag: String? = null,
        filterPriority: TaskPriority? = null,
        taskIds: List<String> = emptyList()
    ): ToolExecutionResult = withContext(Dispatchers.IO) {
        runCatching {
            when (action.lowercase(Locale.ROOT)) {
                "schedule" -> scheduleTask(name, prompt, delayMinutes, priority, tags,
                    maxRetries, timeoutMinutes, maxRuns, dependsOn, notifyOnComplete,
                    notifyOnFail, repeatIntervalMinutes, recurrenceType, cronExpression,
                    timeOfDay, dayOfWeek, dayOfMonth)
                "list"            -> listTasks()
                "cancel"          -> cancelTask(taskId)
                "status"          -> taskStatus(taskId)
                "pause"           -> pauseTask(taskId)
                "resume"          -> resumeTask(taskId)
                "run_now"         -> runNow(taskId)
                "retry"           -> retryTask(taskId)
                "update"          -> updateTask(taskId, newName, newPrompt, priority, tags, newTimeoutMinutes)
                "clear_completed" -> clearCompleted()
                "list_by_tag"     -> listByTag(tag)
                "list_by_priority"-> listByPriority(filterPriority)
                "set_priority"    -> setPriority(taskId, priority)
                "add_tags"        -> addTags(taskId, tags)
                "remove_tags"     -> removeTags(taskId, tags)
                "chain"           -> chainTasks(taskIds)
                "get_summary"     -> getExecutionSummary(taskId)
                else -> ToolExecutionResult("Unknown action '$action'.", isError = true)
            }
        }.getOrElse { e ->
            ToolExecutionResult("Error in task_scheduler: ${e.message}", isError = true)
        }
    }

    // ── Lifecycle (Called by OmniSyncService) ─────────────────────────────

    fun getReadyTasks(): List<ScheduledTask> {
        val completedIds = tasks.filter { it.status == TaskStatus.COMPLETED }.map { it.id }.toSet()
        for (i in tasks.indices) {
            val t = tasks[i]
            if (t.status == TaskStatus.WAITING_DEPENDENCY && t.dependsOn.all { it in completedIds }) {
                tasks[i] = t.copy(status = TaskStatus.PENDING)
            }
        }
        val now = System.currentTimeMillis()
        return tasks
            .filter { it.status == TaskStatus.PENDING && now >= it.scheduledTimeMillis }
            .sortedWith(compareBy({ it.priority.order }, { it.scheduledTimeMillis }))
    }

    fun markRunning(taskId: String, executionDetails: String? = null) {
        val idx = tasks.indexOfFirst { it.id == taskId }
        if (idx != -1) {
            tasks[idx] = tasks[idx].copy(
                status = TaskStatus.RUNNING,
                startedAtMillis = System.currentTimeMillis(),
                lastResult = executionDetails ?: tasks[idx].lastResult
            )
            notifyChanged()
        }
    }

    fun markCompleted(taskId: String, result: String, summary: ExecutionSummary? = null) {
        val idx = tasks.indexOfFirst { it.id == taskId }
        if (idx != -1) {
            val t = tasks[idx]
            val summaries = if (summary != null) {
                (listOf(summary) + t.executionSummaries).take(10)
            } else t.executionSummaries
            val historyEntry = summary?.toHistoryEntry() ?: result
            val history = (listOf(historyEntry) + t.executionHistory).take(10)
            tasks[idx] = t.copy(
                status = TaskStatus.COMPLETED,
                lastResult = result.take(500),
                completedAtMillis = System.currentTimeMillis(),
                runCount = t.runCount + 1,
                executionSummaries = summaries,
                executionHistory = history
            )
            notifyChanged()
            // Fire notification if enabled
            if (t.notifyOnComplete && summary != null) {
                notificationCallback?.invoke(tasks[idx], summary)
            }
        }
    }

    fun markFailed(taskId: String, error: String, summary: ExecutionSummary? = null) {
        val idx = tasks.indexOfFirst { it.id == taskId }
        if (idx == -1) return
        val t = tasks[idx]
        val summaries = if (summary != null) {
            (listOf(summary) + t.executionSummaries).take(10)
        } else t.executionSummaries
        val historyEntry = summary?.toHistoryEntry() ?: "FAILED: $error"
        val history = (listOf(historyEntry) + t.executionHistory).take(10)
        tasks[idx] = t.copy(
            status = TaskStatus.FAILED,
            lastResult = error.take(500),
            executionSummaries = summaries,
            executionHistory = history
        )
        notifyChanged()
        // Notification on failure
        if (t.notifyOnFail && summary != null) {
            notificationCallback?.invoke(tasks[idx], summary)
        }
        // Auto-retry
        if (t.retryCount < t.maxRetries) {
            val backoffMs = (1L shl t.retryCount.coerceAtMost(6)) * 60_000L
            tasks.add(t.copy(
                id = UUID.randomUUID().toString(),
                scheduledTimeMillis = System.currentTimeMillis() + backoffMs,
                status = TaskStatus.PENDING,
                retryCount = t.retryCount + 1,
                lastResult = null,
                startedAtMillis = null,
                completedAtMillis = null
            ))
            notifyChanged()
        }
    }

    fun rescheduleRepeating(taskId: String) {
        val task = tasks.find { it.id == taskId } ?: return
        val nextSchedule = computeNextScheduledTime(task) ?: return
        if (task.maxRuns != null && task.runCount >= task.maxRuns) return
        tasks.add(task.copy(
            id = UUID.randomUUID().toString(),
            scheduledTimeMillis = nextSchedule,
            status = TaskStatus.PENDING,
            lastResult = null,
            startedAtMillis = null,
            completedAtMillis = null,
            executionSummaries = task.executionSummaries // Carry forward history
        ))
        tasks.removeAll { it.id == taskId }
        notifyChanged()
    }

    fun getTimeoutDeadlineMillis(taskId: String): Long? {
        val t = tasks.find { it.id == taskId } ?: return null
        val started = t.startedAtMillis ?: return null
        val timeout = t.timeoutMinutes ?: return null
        return started + timeout * 60_000L
    }

    // ── Action Implementations ────────────────────────────────────────────

    private fun scheduleTask(
        name: String?, prompt: String?, delayMinutes: Int, priority: TaskPriority,
        tags: List<String>, maxRetries: Int, timeoutMinutes: Int?, maxRuns: Int?,
        dependsOn: List<String>, notifyOnComplete: Boolean, notifyOnFail: Boolean,
        repeatIntervalMinutes: Int?, recurrenceType: RecurrenceType,
        cronExpression: String?, timeOfDay: String?, dayOfWeek: Int?, dayOfMonth: Int?
    ): ToolExecutionResult {
        if (name.isNullOrBlank()) return ToolExecutionResult("'name' is required.", isError = true)
        if (prompt.isNullOrBlank()) return ToolExecutionResult("'prompt' is required.", isError = true)
        if (cronExpression != null && !isValidCron(cronExpression))
            return ToolExecutionResult("Invalid cronExpression '$cronExpression'.", isError = true)
        val missingDeps = dependsOn.filter { dep -> tasks.none { it.id == dep } }
        if (missingDeps.isNotEmpty())
            return ToolExecutionResult("Unknown dependency IDs: ${missingDeps.joinToString()}", isError = true)

        val selectedRecurrence = when {
            cronExpression != null -> RecurrenceType.CRON
            repeatIntervalMinutes != null -> RecurrenceType.INTERVAL_MINUTES
            else -> recurrenceType
        }
        val scheduledTime = computeInitialScheduledTime(delayMinutes, selectedRecurrence,
            cronExpression, timeOfDay, dayOfWeek, dayOfMonth)
        val initialStatus = if (dependsOn.isNotEmpty() &&
            dependsOn.any { dep -> tasks.none { it.id == dep && it.status == TaskStatus.COMPLETED } })
            TaskStatus.WAITING_DEPENDENCY else TaskStatus.PENDING

        val id = UUID.randomUUID().toString()
        val task = ScheduledTask(
            id = id, name = name, prompt = prompt,
            scheduledTimeMillis = scheduledTime,
            createdAtMillis = System.currentTimeMillis(),
            recurrenceType = selectedRecurrence,
            timeOfDay = timeOfDay, dayOfWeek = dayOfWeek, dayOfMonth = dayOfMonth,
            repeatIntervalMinutes = repeatIntervalMinutes,
            cronExpression = cronExpression, maxRuns = maxRuns,
            priority = priority,
            tags = tags.map { it.trim() }.filter { it.isNotEmpty() },
            maxRetries = maxRetries, timeoutMinutes = timeoutMinutes,
            dependsOn = dependsOn,
            notifyOnComplete = notifyOnComplete, notifyOnFail = notifyOnFail,
            status = initialStatus
        )
        tasks.add(task)
        notifyChanged()

        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val bridge = if (executionCallback != null) "✅ Execution bridge: connected" else "⚠️ Execution bridge: NOT SET (tasks will not execute!)"
        return ToolExecutionResult(buildString {
            appendLine("✅ Task scheduled.")
            appendLine("ID:        $id")
            appendLine("Name:      $name")
            appendLine("Priority:  ${priority.label}")
            appendLine("Scheduled: ${fmt.format(Date(scheduledTime))}")
            appendLine("Status:    $initialStatus")
            appendLine(bridge)
            if (tags.isNotEmpty()) appendLine("Tags:      ${tags.joinToString()}")
            if (dependsOn.isNotEmpty()) appendLine("Depends:   ${dependsOn.joinToString()}")
        }.trimEnd())
    }

    private fun listTasks(): ToolExecutionResult {
        if (tasks.isEmpty()) return ToolExecutionResult("No scheduled tasks.")
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return ToolExecutionResult(buildString {
            appendLine("📋 ${tasks.size} task(s):\n")
            tasks.sortedWith(compareBy({ it.priority.order }, { it.scheduledTimeMillis }))
                .forEachIndexed { i, t ->
                    appendLine("─── ${i+1} ───")
                    appendLine(formatTaskSummary(t, fmt))
                    appendLine()
                }
        }.trimEnd())
    }

    private fun cancelTask(taskId: String?): ToolExecutionResult {
        if (taskId.isNullOrBlank()) return ToolExecutionResult("'taskId' required.", isError = true)
        val idx = tasks.indexOfFirst { it.id == taskId }
        if (idx == -1) return ToolExecutionResult("Task not found: $taskId", isError = true)
        tasks[idx] = tasks[idx].copy(status = TaskStatus.CANCELLED)
        notifyChanged()
        return ToolExecutionResult("🚫 Task '${tasks[idx].name}' cancelled.")
    }

    private fun taskStatus(taskId: String?): ToolExecutionResult {
        if (taskId.isNullOrBlank()) return ToolExecutionResult("'taskId' required.", isError = true)
        val task = tasks.find { it.id == taskId }
            ?: return ToolExecutionResult("Task not found: $taskId", isError = true)
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return ToolExecutionResult(formatTaskSummary(task, fmt, verbose = true))
    }

    private fun getExecutionSummary(taskId: String?): ToolExecutionResult {
        if (taskId.isNullOrBlank()) return ToolExecutionResult("'taskId' required.", isError = true)
        val task = tasks.find { it.id == taskId }
            ?: return ToolExecutionResult("Task not found: $taskId", isError = true)
        if (task.executionSummaries.isEmpty())
            return ToolExecutionResult("No execution records for '${task.name}' yet.")
        return ToolExecutionResult(buildString {
            appendLine("## Execution History: ${task.name}")
            appendLine("Total runs: ${task.runCount}")
            appendLine()
            task.executionSummaries.forEachIndexed { i, s ->
                appendLine("### Run ${i + 1} — ${if(s.isSuccess) "✅ SUCCESS" else "❌ FAILED"}")
                appendLine(s.toMarkdownReport())
                appendLine()
            }
        }.trimEnd())
    }

    private fun pauseTask(taskId: String?): ToolExecutionResult {
        if (taskId.isNullOrBlank()) return ToolExecutionResult("'taskId' required.", isError = true)
        val idx = tasks.indexOfFirst { it.id == taskId }
        if (idx == -1) return ToolExecutionResult("Task not found: $taskId", isError = true)
        val t = tasks[idx]
        if (t.status !in setOf(TaskStatus.PENDING, TaskStatus.WAITING_DEPENDENCY))
            return ToolExecutionResult("Cannot pause task in status ${t.status}.", isError = true)
        tasks[idx] = t.copy(status = TaskStatus.PAUSED)
        notifyChanged()
        return ToolExecutionResult("⏸️ Task '${t.name}' paused.")
    }

    private fun resumeTask(taskId: String?): ToolExecutionResult {
        if (taskId.isNullOrBlank()) return ToolExecutionResult("'taskId' required.", isError = true)
        val idx = tasks.indexOfFirst { it.id == taskId }
        if (idx == -1) return ToolExecutionResult("Task not found: $taskId", isError = true)
        val t = tasks[idx]
        if (t.status != TaskStatus.PAUSED) return ToolExecutionResult("Task not paused.", isError = true)
        val completedIds = tasks.filter { it.status == TaskStatus.COMPLETED }.map { it.id }.toSet()
        val newStatus = if (t.dependsOn.isNotEmpty() && t.dependsOn.any { it !in completedIds })
            TaskStatus.WAITING_DEPENDENCY else TaskStatus.PENDING
        tasks[idx] = t.copy(status = newStatus)
        notifyChanged()
        return ToolExecutionResult("▶️ Task '${t.name}' resumed.")
    }

    private fun runNow(taskId: String?): ToolExecutionResult {
        if (taskId.isNullOrBlank()) return ToolExecutionResult("'taskId' required.", isError = true)
        val idx = tasks.indexOfFirst { it.id == taskId }
        if (idx == -1) return ToolExecutionResult("Task not found: $taskId", isError = true)
        if (tasks[idx].status == TaskStatus.RUNNING)
            return ToolExecutionResult("Task already running.", isError = true)
        tasks[idx] = tasks[idx].copy(scheduledTimeMillis = System.currentTimeMillis(), status = TaskStatus.PENDING)
        notifyChanged()
        return ToolExecutionResult("⚡ Task '${tasks[idx].name}' scheduled immediately.")
    }

    private fun retryTask(taskId: String?): ToolExecutionResult {
        if (taskId.isNullOrBlank()) return ToolExecutionResult("'taskId' required.", isError = true)
        val t = tasks.find { it.id == taskId }
            ?: return ToolExecutionResult("Task not found: $taskId", isError = true)
        if (t.status !in setOf(TaskStatus.FAILED, TaskStatus.CANCELLED))
            return ToolExecutionResult("Can only retry FAILED or CANCELLED tasks.", isError = true)
        val retry = t.copy(
            id = UUID.randomUUID().toString(), scheduledTimeMillis = System.currentTimeMillis(),
            status = TaskStatus.PENDING, retryCount = t.retryCount + 1,
            lastResult = null, startedAtMillis = null, completedAtMillis = null
        )
        tasks.add(retry)
        notifyChanged()
        return ToolExecutionResult("🔁 Retry task created: ${retry.id} (attempt ${retry.retryCount + 1}).")
    }

    private fun updateTask(taskId: String?, newName: String?, newPrompt: String?,
                           priority: TaskPriority, tags: List<String>, newTimeout: Int?): ToolExecutionResult {
        if (taskId.isNullOrBlank()) return ToolExecutionResult("'taskId' required.", isError = true)
        val idx = tasks.indexOfFirst { it.id == taskId }
        if (idx == -1) return ToolExecutionResult("Task not found: $taskId", isError = true)
        if (tasks[idx].status == TaskStatus.RUNNING) return ToolExecutionResult("Cannot update running task.", isError = true)
        val t = tasks[idx]
        tasks[idx] = t.copy(
            name = if (!newName.isNullOrBlank()) newName else t.name,
            prompt = if (!newPrompt.isNullOrBlank()) newPrompt else t.prompt,
            priority = priority,
            tags = if (tags.isNotEmpty()) tags.map { it.trim() }.filter { it.isNotEmpty() } else t.tags,
            timeoutMinutes = newTimeout ?: t.timeoutMinutes
        )
        notifyChanged()
        return ToolExecutionResult("✏️ Task '${tasks[idx].name}' updated.")
    }

    private fun clearCompleted(): ToolExecutionResult {
        val before = tasks.size
        tasks.removeAll { it.status in setOf(TaskStatus.COMPLETED, TaskStatus.CANCELLED) }
        val removed = before - tasks.size
        notifyChanged()
        return ToolExecutionResult("🗑️ Removed $removed task(s).")
    }

    private fun listByTag(tag: String?): ToolExecutionResult {
        if (tag.isNullOrBlank()) return ToolExecutionResult("'tag' required.", isError = true)
        val filtered = tasks.filter { tag.lowercase() in it.tags.map { t -> t.lowercase() } }
        if (filtered.isEmpty()) return ToolExecutionResult("No tasks with tag '$tag'.")
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        return ToolExecutionResult(buildString {
            appendLine("🏷 ${filtered.size} task(s) with tag '$tag':")
            filtered.forEach { t -> appendLine("  • ${t.id} [${t.status}] ${t.name} — ${fmt.format(Date(t.scheduledTimeMillis))}") }
        }.trimEnd())
    }

    private fun listByPriority(priority: TaskPriority?): ToolExecutionResult {
        if (priority == null) return ToolExecutionResult("'filterPriority' required.", isError = true)
        val filtered = tasks.filter { it.priority == priority }
        if (filtered.isEmpty()) return ToolExecutionResult("No tasks with priority '${priority.label}'.")
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        return ToolExecutionResult(buildString {
            appendLine("🔝 ${filtered.size} ${priority.label}-priority task(s):")
            filtered.sortedBy { it.scheduledTimeMillis }.forEach { t ->
                appendLine("  • ${t.id} [${t.status}] ${t.name} — ${fmt.format(Date(t.scheduledTimeMillis))}")
            }
        }.trimEnd())
    }

    private fun setPriority(taskId: String?, priority: TaskPriority): ToolExecutionResult {
        if (taskId.isNullOrBlank()) return ToolExecutionResult("'taskId' required.", isError = true)
        val idx = tasks.indexOfFirst { it.id == taskId }
        if (idx == -1) return ToolExecutionResult("Task not found: $taskId", isError = true)
        tasks[idx] = tasks[idx].copy(priority = priority)
        notifyChanged()
        return ToolExecutionResult("🔝 Task '${tasks[idx].name}' priority set to ${priority.label}.")
    }

    private fun addTags(taskId: String?, newTags: List<String>): ToolExecutionResult {
        if (taskId.isNullOrBlank()) return ToolExecutionResult("'taskId' required.", isError = true)
        val idx = tasks.indexOfFirst { it.id == taskId }
        if (idx == -1) return ToolExecutionResult("Task not found: $taskId", isError = true)
        val merged = (tasks[idx].tags + newTags.map { it.trim() }.filter { it.isNotEmpty() }).distinct()
        tasks[idx] = tasks[idx].copy(tags = merged)
        notifyChanged()
        return ToolExecutionResult("🏷 Tags: ${merged.joinToString()}")
    }

    private fun removeTags(taskId: String?, removedTags: List<String>): ToolExecutionResult {
        if (taskId.isNullOrBlank()) return ToolExecutionResult("'taskId' required.", isError = true)
        val idx = tasks.indexOfFirst { it.id == taskId }
        if (idx == -1) return ToolExecutionResult("Task not found: $taskId", isError = true)
        val cleaned = tasks[idx].tags.filterNot { it.lowercase() in removedTags.map { t -> t.lowercase() } }
        tasks[idx] = tasks[idx].copy(tags = cleaned)
        notifyChanged()
        return ToolExecutionResult("🏷 Remaining tags: ${if(cleaned.isEmpty()) "(none)" else cleaned.joinToString()}")
    }

    private fun chainTasks(taskIds: List<String>): ToolExecutionResult {
        if (taskIds.size < 2) return ToolExecutionResult("chain requires ≥2 task IDs.", isError = true)
        val notFound = taskIds.filter { id -> tasks.none { it.id == id } }
        if (notFound.isNotEmpty()) return ToolExecutionResult("Unknown task IDs: ${notFound.joinToString()}", isError = true)
        for (i in 1 until taskIds.size) {
            val idx = tasks.indexOfFirst { it.id == taskIds[i] }
            val prevId = taskIds[i - 1]
            if (prevId !in tasks[idx].dependsOn) {
                tasks[idx] = tasks[idx].copy(
                    dependsOn = tasks[idx].dependsOn + prevId,
                    status = TaskStatus.WAITING_DEPENDENCY
                )
            }
        }
        notifyChanged()
        val names = taskIds.mapNotNull { id -> tasks.find { it.id == id }?.name }
        return ToolExecutionResult("🔗 Chain: ${names.joinToString(" → ")}")
    }

    // ── Public List Access ────────────────────────────────────────────────

    fun getAllTasks(): List<ScheduledTask> = tasks.toList()
    fun cancelTaskById(taskId: String) { val i = tasks.indexOfFirst { it.id == taskId }; if(i != -1){tasks[i] = tasks[i].copy(status = TaskStatus.CANCELLED); notifyChanged()} }
    fun deleteTask(taskId: String) { tasks.removeAll { it.id == taskId }; notifyChanged() }
    fun pauseTaskById(taskId: String) { val i = tasks.indexOfFirst { it.id == taskId }; if(i != -1 && tasks[i].status in setOf(TaskStatus.PENDING, TaskStatus.WAITING_DEPENDENCY)){tasks[i] = tasks[i].copy(status = TaskStatus.PAUSED); notifyChanged()} }
    fun resumeTaskById(taskId: String) { val i = tasks.indexOfFirst { it.id == taskId }; if(i != -1 && tasks[i].status == TaskStatus.PAUSED){val t = tasks[i]; val cids = tasks.filter{it.status==TaskStatus.COMPLETED}.map{it.id}.toSet(); tasks[i] = t.copy(status = if(t.dependsOn.isNotEmpty()&&t.dependsOn.any{it !in cids}) TaskStatus.WAITING_DEPENDENCY else TaskStatus.PENDING); notifyChanged()} }
    fun runNowById(taskId: String) { val i = tasks.indexOfFirst { it.id == taskId }; if(i != -1){tasks[i] = tasks[i].copy(scheduledTimeMillis = System.currentTimeMillis(), status = TaskStatus.PENDING); notifyChanged()} }
    fun retryTaskById(taskId: String) { val t = tasks.find { it.id == taskId } ?: return; tasks.add(t.copy(id = UUID.randomUUID().toString(), scheduledTimeMillis = System.currentTimeMillis(), status = TaskStatus.PENDING, retryCount = t.retryCount + 1, lastResult = null, startedAtMillis = null, completedAtMillis = null)); notifyChanged() }

    fun scheduleTaskDirectly(
        name: String, prompt: String, delayMinutes: Int, repeatIntervalMinutes: Int?,
        priority: TaskPriority = TaskPriority.NORMAL, tags: List<String> = emptyList(),
        maxRetries: Int = 0, timeoutMinutes: Int? = null, maxRuns: Int? = null,
        cronExpression: String? = null, recurrenceType: RecurrenceType = RecurrenceType.ONCE,
        timeOfDay: String? = null, dayOfWeek: Int? = null, dayOfMonth: Int? = null,
        dependsOn: List<String> = emptyList(), notifyOnComplete: Boolean = true, notifyOnFail: Boolean = true
    ) {
        val selectedRecurrence = when { cronExpression != null -> RecurrenceType.CRON; repeatIntervalMinutes != null -> RecurrenceType.INTERVAL_MINUTES; else -> recurrenceType }
        val scheduledTime = computeInitialScheduledTime(delayMinutes, selectedRecurrence, cronExpression, timeOfDay, dayOfWeek, dayOfMonth)
        val completedIds = tasks.filter { it.status == TaskStatus.COMPLETED }.map { it.id }.toSet()
        val initialStatus = if (dependsOn.isNotEmpty() && dependsOn.any { it !in completedIds }) TaskStatus.WAITING_DEPENDENCY else TaskStatus.PENDING
        tasks.add(ScheduledTask(
            id = UUID.randomUUID().toString(), name = name, prompt = prompt,
            scheduledTimeMillis = scheduledTime, createdAtMillis = System.currentTimeMillis(),
            recurrenceType = selectedRecurrence, timeOfDay = timeOfDay, dayOfWeek = dayOfWeek,
            dayOfMonth = dayOfMonth, repeatIntervalMinutes = repeatIntervalMinutes,
            cronExpression = cronExpression, maxRuns = maxRuns, priority = priority,
            tags = tags.map { it.trim() }.filter { it.isNotEmpty() }, maxRetries = maxRetries,
            timeoutMinutes = timeoutMinutes, dependsOn = dependsOn,
            notifyOnComplete = notifyOnComplete, notifyOnFail = notifyOnFail,
            status = initialStatus
        ))
        notifyChanged()
    }

    // ── Formatting ────────────────────────────────────────────────────────

    private fun formatTaskSummary(t: ScheduledTask, fmt: SimpleDateFormat, verbose: Boolean = false): String = buildString {
        appendLine("ID:         ${t.id}")
        appendLine("Name:       ${t.name}")
        appendLine("Priority:   ${t.priority.label}")
        appendLine("Status:     ${t.status}")
        appendLine("Scheduled:  ${fmt.format(Date(t.scheduledTimeMillis))}")
        appendLine("Created:    ${fmt.format(Date(t.createdAtMillis))}")
        if (t.tags.isNotEmpty()) appendLine("Tags:       ${t.tags.joinToString()}")
        if (t.timeoutMinutes != null) appendLine("Timeout:    ${t.timeoutMinutes}m")
        if (t.maxRetries > 0) appendLine("Retries:    ${t.retryCount}/${t.maxRetries}")
        if (t.dependsOn.isNotEmpty()) appendLine("Depends:    ${t.dependsOn.joinToString()}")
        if (t.runCount > 0) appendLine("Runs:       ${t.runCount}${if(t.maxRuns!=null) "/${t.maxRuns}" else ""}")
        if (t.startedAtMillis != null) appendLine("Started:    ${fmt.format(Date(t.startedAtMillis))}")
        if (t.completedAtMillis != null) appendLine("Completed:  ${fmt.format(Date(t.completedAtMillis))}")
        if (!t.lastResult.isNullOrBlank()) appendLine("Last Result:${t.lastResult.take(200)}")
        if (verbose && t.executionSummaries.isNotEmpty()) {
            appendLine("Last Run:")
            val last = t.executionSummaries.first()
            appendLine("  Duration: ${last.durationSec}s | Tools: ${last.toolsUsed} | Iterations: ${last.iterationsUsed}")
            if (last.toolNames.isNotEmpty()) appendLine("  Tools used: ${last.toolNames.distinct().joinToString()}")
        }
    }.trimEnd()

    // ── CRON Support ──────────────────────────────────────────────────────

    fun isValidCron(expr: String): Boolean {
        val fields = expr.trim().split("\\s+".toRegex())
        if (fields.size != 5) return false
        val ranges = listOf(0..59, 0..23, 1..31, 1..12, 0..7)
        return fields.zip(ranges).all { (f, r) -> isValidCronField(f, r) }
    }

    private fun isValidCronField(field: String, range: IntRange): Boolean {
        if (field == "*") return true
        if (field.startsWith("*/")) return field.substring(2).toIntOrNull() != null
        return field.split(",").all { part ->
            if ("-" in part && !part.startsWith("-")) {
                val lr = part.split("-", limit = 2)
                val a = lr[0].toIntOrNull() ?: return@all false
                val b = lr[1].toIntOrNull() ?: return@all false
                a in range && b in range && a <= b
            } else { (part.toIntOrNull() ?: return@all false) in range }
        }
    }

    private fun nextCronTime(expr: String, afterMillis: Long): Long? {
        val fields = expr.trim().split("\\s+".toRegex())
        if (fields.size != 5) return null
        val (minF, hourF, domF, monthF, dowF) = fields
        val cal = Calendar.getInstance().apply {
            timeInMillis = afterMillis + 60_000L
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val limit = afterMillis + 366L * 24 * 60 * 60_000
        while (cal.timeInMillis < limit) {
            val min = cal.get(Calendar.MINUTE); val hour = cal.get(Calendar.HOUR_OF_DAY)
            val dom = cal.get(Calendar.DAY_OF_MONTH); val month = cal.get(Calendar.MONTH) + 1
            val dow = cal.get(Calendar.DAY_OF_WEEK) - 1
            if (matchCronField(minF,min,0..59)&&matchCronField(hourF,hour,0..23)&&
                matchCronField(domF,dom,1..31)&&matchCronField(monthF,month,1..12)&&
                (matchCronField(dowF,dow,0..6)||matchCronField(dowF,if(dow==0) 7 else dow,0..7)))
                return cal.timeInMillis
            cal.add(Calendar.MINUTE, 1)
        }
        return null
    }

    private fun matchCronField(field: String, value: Int, range: IntRange): Boolean {
        if (field == "*") return true
        if (field.startsWith("*/")) { val step = field.substring(2).toIntOrNull() ?: return false; return (value - range.first) % step == 0 }
        return field.split(",").any { part ->
            if ("-" in part && !part.startsWith("-")) { val lr = part.split("-",limit=2); val a = lr[0].toIntOrNull()?:return@any false; val b = lr[1].toIntOrNull()?:return@any false; value in a..b }
            else part.toIntOrNull() == value
        }
    }

    // ── Time Computation ──────────────────────────────────────────────────

    private fun computeInitialScheduledTime(delayMinutes: Int, recurrenceType: RecurrenceType, cronExpression: String?, timeOfDay: String?, dayOfWeek: Int?, dayOfMonth: Int?): Long {
        if (delayMinutes > 0) return System.currentTimeMillis() + delayMinutes * 60_000L
        val now = Calendar.getInstance()
        return when (recurrenceType) {
            RecurrenceType.ONCE, RecurrenceType.INTERVAL_MINUTES -> now.timeInMillis
            RecurrenceType.DAILY   -> nextDaily(now, timeOfDay).timeInMillis
            RecurrenceType.WEEKLY  -> nextWeekly(now, timeOfDay, dayOfWeek).timeInMillis
            RecurrenceType.MONTHLY -> nextMonthly(now, timeOfDay, dayOfMonth).timeInMillis
            RecurrenceType.CRON    -> nextCronTime(cronExpression ?: "* * * * *", now.timeInMillis) ?: now.timeInMillis
        }
    }

    private fun computeNextScheduledTime(task: ScheduledTask): Long? = when (task.recurrenceType) {
        RecurrenceType.ONCE -> null
        RecurrenceType.INTERVAL_MINUTES -> { val i = task.repeatIntervalMinutes ?: return null; System.currentTimeMillis() + i * 60_000L }
        RecurrenceType.DAILY   -> nextDaily(Calendar.getInstance(), task.timeOfDay).timeInMillis
        RecurrenceType.WEEKLY  -> nextWeekly(Calendar.getInstance(), task.timeOfDay, task.dayOfWeek).timeInMillis
        RecurrenceType.MONTHLY -> nextMonthly(Calendar.getInstance(), task.timeOfDay, task.dayOfMonth).timeInMillis
        RecurrenceType.CRON    -> nextCronTime(task.cronExpression ?: "* * * * *", Calendar.getInstance().timeInMillis)
    }

    private fun parseTimeOfDay(timeOfDay: String?): Pair<Int,Int>? {
        if (timeOfDay.isNullOrBlank()) return null
        val parts = timeOfDay.split(":"); if (parts.size != 2) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return h to m
    }

    private fun nextDaily(now: Calendar, timeOfDay: String?): Calendar {
        val (h,m) = parseTimeOfDay(timeOfDay) ?: (now.get(Calendar.HOUR_OF_DAY) to now.get(Calendar.MINUTE))
        return (now.clone() as Calendar).apply {
            set(Calendar.SECOND,0); set(Calendar.MILLISECOND,0)
            set(Calendar.HOUR_OF_DAY,h); set(Calendar.MINUTE,m)
            if (!after(now)) add(Calendar.DAY_OF_YEAR,1)
        }
    }

    private fun nextWeekly(now: Calendar, timeOfDay: String?, dayOfWeek: Int?): Calendar {
        val targetDay = toCalendarDayOfWeek(dayOfWeek) ?: now.get(Calendar.DAY_OF_WEEK)
        val (h,m) = parseTimeOfDay(timeOfDay) ?: (now.get(Calendar.HOUR_OF_DAY) to now.get(Calendar.MINUTE))
        return (now.clone() as Calendar).apply {
            set(Calendar.SECOND,0); set(Calendar.MILLISECOND,0)
            set(Calendar.HOUR_OF_DAY,h); set(Calendar.MINUTE,m)
            var g = 0
            while ((get(Calendar.DAY_OF_WEEK) != targetDay || !after(now)) && g++ < 8) add(Calendar.DAY_OF_YEAR,1)
        }
    }

    private fun nextMonthly(now: Calendar, timeOfDay: String?, dayOfMonth: Int?): Calendar {
        val td = (dayOfMonth ?: 1).coerceIn(1,31)
        val (h,m) = parseTimeOfDay(timeOfDay) ?: (now.get(Calendar.HOUR_OF_DAY) to now.get(Calendar.MINUTE))
        return (now.clone() as Calendar).apply {
            set(Calendar.SECOND,0); set(Calendar.MILLISECOND,0)
            set(Calendar.HOUR_OF_DAY,h); set(Calendar.MINUTE,m)
            fun applyDay() = set(Calendar.DAY_OF_MONTH, td.coerceAtMost(getActualMaximum(Calendar.DAY_OF_MONTH)))
            applyDay()
            if (!after(now)) { add(Calendar.MONTH,1); applyDay() }
        }
    }

    private fun toCalendarDayOfWeek(userDay: Int?): Int? = when(userDay) {
        1 -> Calendar.MONDAY; 2 -> Calendar.TUESDAY; 3 -> Calendar.WEDNESDAY
        4 -> Calendar.THURSDAY; 5 -> Calendar.FRIDAY; 6 -> Calendar.SATURDAY; 7 -> Calendar.SUNDAY
        else -> null
    }

    private fun parsePriority(raw: String?): TaskPriority = when (raw?.trim()?.lowercase(Locale.ROOT)) {
        "low" -> TaskPriority.LOW; "high" -> TaskPriority.HIGH; "critical" -> TaskPriority.CRITICAL; else -> TaskPriority.NORMAL
    }

    private fun parseRecurrence(raw: String?): RecurrenceType = when (raw?.trim()?.lowercase(Locale.ROOT)) {
        "interval","interval_minutes","every_minutes" -> RecurrenceType.INTERVAL_MINUTES
        "daily" -> RecurrenceType.DAILY; "weekly" -> RecurrenceType.WEEKLY
        "monthly" -> RecurrenceType.MONTHLY; "cron" -> RecurrenceType.CRON; else -> RecurrenceType.ONCE
    }
}
