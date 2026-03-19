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
 * Manages scheduled autonomous AI tasks with full lifecycle control.
 *
 * ### Features
 * - **Priorities:** CRITICAL / HIGH / NORMAL / LOW with sorted execution
 * - **Tags:** arbitrary string labels for grouping / filtering
 * - **Retry:** auto-retry on failure up to [ScheduledTask.maxRetries] attempts
 * - **Timeout:** [ScheduledTask.timeoutMinutes] lets the runner abort stuck tasks
 * - **Run limits:** [ScheduledTask.maxRuns] caps how many times a repeating task fires
 * - **Dependencies:** [ScheduledTask.dependsOn] — task only runs after listed tasks complete
 * - **CRON expressions:** 5-field "min hour dom month dow" patterns
 * - **Pause / Resume:** temporarily suspend a task without cancelling it
 * - **Execution history:** last 10 result summaries per task
 *
 * A background runner (e.g. [com.omnidev.workspace.data.sync.OmniSyncService]) should
 * periodically call [getReadyTasks] and drive the lifecycle via [markRunning],
 * [markCompleted], [markFailed], and [rescheduleRepeating].
 *
 * The [tasksFlow] [StateFlow] emits a snapshot whenever the list is mutated — collect
 * it from Compose instead of polling.
 */
object TaskSchedulerTool {

    // ── Enumerations ─────────────────────────────────────────────────────

    /** Lifecycle states for a scheduled task. */
    enum class TaskStatus {
        /** Waiting for its scheduled time (or dependencies) to be met. */
        PENDING,
        /** Actively executing inside the agent pipeline. */
        RUNNING,
        /** Finished successfully. */
        COMPLETED,
        /** Execution failed (may auto-retry based on [ScheduledTask.maxRetries]). */
        FAILED,
        /** Explicitly cancelled by the user or agent. */
        CANCELLED,
        /** Temporarily suspended; will not fire until resumed. */
        PAUSED,
        /** Blocked on one or more incomplete dependency tasks. */
        WAITING_DEPENDENCY
    }

    /** Execution priority used to order concurrent ready tasks. */
    enum class TaskPriority(val label: String, val order: Int) {
        LOW("Low", 3),
        NORMAL("Normal", 2),
        HIGH("High", 1),
        CRITICAL("Critical", 0)
    }

    /** Supported recurrence strategies for calendar-based scheduling. */
    enum class RecurrenceType {
        ONCE,
        INTERVAL_MINUTES,
        DAILY,
        WEEKLY,
        MONTHLY,
        /** 5-field CRON expression ("min hour dom month dow"). */
        CRON
    }

    /**
     * Immutable snapshot describing a single scheduled task.
     *
     * All new fields have defaults so existing call-sites compile without changes.
     */
    data class ScheduledTask(
        val id: String,
        val name: String,
        val prompt: String,
        val scheduledTimeMillis: Long,
        val createdAtMillis: Long = System.currentTimeMillis(),

        // ── Recurrence ────────────────────────────────────────────────
        val recurrenceType: RecurrenceType = RecurrenceType.ONCE,
        val timeOfDay: String? = null,
        val dayOfWeek: Int? = null,
        val dayOfMonth: Int? = null,
        val repeatIntervalMinutes: Int? = null,
        /** 5-field CRON expression, e.g. "0 9 * * 1-5" (weekdays at 09:00). */
        val cronExpression: String? = null,
        /** Stop rescheduling after this many successful runs (null = unlimited). */
        val maxRuns: Int? = null,
        /** How many times this task has successfully completed. */
        val runCount: Int = 0,

        // ── Quality of Service ────────────────────────────────────────
        val priority: TaskPriority = TaskPriority.NORMAL,
        /** Arbitrary labels for grouping / filtering. */
        val tags: List<String> = emptyList(),
        /** Maximum number of automatic retries after failure (0 = no retry). */
        val maxRetries: Int = 0,
        /** Current retry attempt number (0 = first attempt). */
        val retryCount: Int = 0,
        /** Kill/abort the task if it runs longer than this many minutes (null = unlimited). */
        val timeoutMinutes: Int? = null,

        // ── Dependencies ──────────────────────────────────────────────
        /** IDs of tasks that must reach COMPLETED status before this task runs. */
        val dependsOn: List<String> = emptyList(),

        // ── Notifications ─────────────────────────────────────────────
        val notifyOnComplete: Boolean = false,
        val notifyOnFail: Boolean = true,

        // ── State ─────────────────────────────────────────────────────
        val status: TaskStatus,
        val lastResult: String? = null,
        val startedAtMillis: Long? = null,
        val completedAtMillis: Long? = null,
        /** Last ≤10 result summaries; newest first. */
        val executionHistory: List<String> = emptyList()
    )

    private val tasks = CopyOnWriteArrayList<ScheduledTask>()

    /** Reactive snapshot of all tasks. Collect this in Compose instead of polling. */
    private val _tasksFlow = MutableStateFlow<List<ScheduledTask>>(emptyList())
    val tasksFlow: StateFlow<List<ScheduledTask>> = _tasksFlow.asStateFlow()

    /** Publishes the current list snapshot to [tasksFlow]. Call after any mutation. */
    private fun notifyChanged() {
        _tasksFlow.value = tasks.toList()
    }

    // ── Tool schema ──────────────────────────────────────────────────────

    /**
     * Returns the tool definitions exposed to the agent for task scheduling.
     */
    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "task_scheduler",
            description = "Schedule, list, control, and monitor autonomous AI tasks with full " +
                "lifecycle management. Supports priorities, tags, retries, timeouts, " +
                "CRON expressions, task dependencies, and per-task notifications.",
            parameters = listOf(
                ToolParameter(
                    name = "action",
                    type = "string",
                    description = "Action: schedule | list | cancel | status | pause | resume | " +
                        "run_now | retry | update | clear_completed | list_by_tag | " +
                        "list_by_priority | set_priority | add_tags | remove_tags | chain",
                    required = true
                ),
                // ── schedule ──────────────────────────────────────────
                ToolParameter("name",   "string", "Task name (required for schedule).", required = false),
                ToolParameter("prompt", "string", "AI prompt to execute (required for schedule).", required = false),
                ToolParameter("delayMinutes", "string",
                    "Minutes from now before first execution (default 0 = immediate).", required = false),
                ToolParameter("priority", "string",
                    "Execution priority: low | normal (default) | high | critical.", required = false),
                ToolParameter("tags", "string",
                    "Comma-separated labels, e.g. 'work,daily,reports'.", required = false),
                ToolParameter("maxRetries", "string",
                    "Auto-retry count on failure (default 0 = no retry).", required = false),
                ToolParameter("timeoutMinutes", "string",
                    "Abort task if still running after N minutes (omit = unlimited).", required = false),
                ToolParameter("maxRuns", "string",
                    "Stop repeating after N successful runs (omit = unlimited).", required = false),
                ToolParameter("dependsOn", "string",
                    "Comma-separated task IDs that must complete before this task runs.", required = false),
                ToolParameter("notifyOnComplete", "string",
                    "'true' to post a notification when the task completes successfully.", required = false),
                ToolParameter("notifyOnFail", "string",
                    "'false' to suppress failure notifications (default true).", required = false),
                // ── recurrence ────────────────────────────────────────
                ToolParameter("recurrence", "string",
                    "Recurrence preset: once | interval | daily | weekly | monthly | cron.", required = false),
                ToolParameter("repeatIntervalMinutes", "string",
                    "Repeat every N minutes (implies recurrence=interval).", required = false),
                ToolParameter("cronExpression", "string",
                    "5-field CRON: 'min hour dom month dow', e.g. '0 9 * * 1-5'.", required = false),
                ToolParameter("timeOfDay", "string",
                    "Local time HH:mm for daily/weekly/monthly recurrence.", required = false),
                ToolParameter("dayOfWeek", "string",
                    "1=Mon … 7=Sun for weekly recurrence.", required = false),
                ToolParameter("dayOfMonth", "string",
                    "1–31 for monthly recurrence.", required = false),
                // ── task identification ───────────────────────────────
                ToolParameter("taskId", "string",
                    "Task ID for cancel / status / pause / resume / run_now / retry / " +
                        "update / set_priority / add_tags / remove_tags.", required = false),
                // ── update fields ─────────────────────────────────────
                ToolParameter("newName",   "string", "New name for the task (update action).", required = false),
                ToolParameter("newPrompt", "string", "New prompt for the task (update action).", required = false),
                ToolParameter("newTimeoutMinutes", "string",
                    "New timeout for the task (update action).", required = false),
                // ── list / filter ─────────────────────────────────────
                ToolParameter("tag",           "string", "Tag filter for list_by_tag.", required = false),
                ToolParameter("filterPriority","string", "Priority filter for list_by_priority.", required = false),
                // ── chain ─────────────────────────────────────────────
                ToolParameter("taskIds", "string",
                    "Comma-separated ordered task IDs to link as a dependency chain.", required = false)
            )
        )
    )

    // ── Execution ────────────────────────────────────────────────────────

    /**
     * Handles all scheduling actions: schedule, list, cancel, status,
     * pause, resume, run_now, retry, update, clear_completed,
     * list_by_tag, list_by_priority, set_priority, add_tags, remove_tags, chain.
     */
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
        notifyOnComplete: Boolean = false,
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
                "schedule" -> scheduleTask(
                    name = name, prompt = prompt, delayMinutes = delayMinutes,
                    priority = priority, tags = tags, maxRetries = maxRetries,
                    timeoutMinutes = timeoutMinutes, maxRuns = maxRuns,
                    dependsOn = dependsOn, notifyOnComplete = notifyOnComplete,
                    notifyOnFail = notifyOnFail, repeatIntervalMinutes = repeatIntervalMinutes,
                    recurrenceType = recurrenceType, cronExpression = cronExpression,
                    timeOfDay = timeOfDay, dayOfWeek = dayOfWeek, dayOfMonth = dayOfMonth
                )
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
                else -> ToolExecutionResult(
                    output = "Unknown action '$action'. Supported: schedule, list, cancel, status, " +
                        "pause, resume, run_now, retry, update, clear_completed, list_by_tag, " +
                        "list_by_priority, set_priority, add_tags, remove_tags, chain.",
                    isError = true
                )
            }
        }.getOrElse { e ->
            ToolExecutionResult(output = "Error in task_scheduler: ${e.message}", isError = true)
        }
    }

    // ── Action handlers ──────────────────────────────────────────────────

    private fun scheduleTask(
        name: String?,
        prompt: String?,
        delayMinutes: Int,
        priority: TaskPriority,
        tags: List<String>,
        maxRetries: Int,
        timeoutMinutes: Int?,
        maxRuns: Int?,
        dependsOn: List<String>,
        notifyOnComplete: Boolean,
        notifyOnFail: Boolean,
        repeatIntervalMinutes: Int?,
        recurrenceType: RecurrenceType,
        cronExpression: String?,
        timeOfDay: String?,
        dayOfWeek: Int?,
        dayOfMonth: Int?
    ): ToolExecutionResult {
        if (name.isNullOrBlank())   return ToolExecutionResult(output = "'name' is required for schedule.", isError = true)
        if (prompt.isNullOrBlank()) return ToolExecutionResult(output = "'prompt' is required for schedule.", isError = true)

        // Validate CRON if provided
        if (cronExpression != null && !isValidCron(cronExpression)) {
            return ToolExecutionResult(
                output = "Invalid cronExpression '$cronExpression'. Expected 5 space-separated fields " +
                    "(min hour dom month dow). Example: '0 9 * * 1-5'",
                isError = true
            )
        }

        // Resolve dependency IDs
        val missingDeps = dependsOn.filter { depId -> tasks.none { it.id == depId } }
        if (missingDeps.isNotEmpty()) {
            return ToolExecutionResult(
                output = "Unknown dependency task IDs: ${missingDeps.joinToString(", ")}",
                isError = true
            )
        }

        val selectedRecurrence = when {
            cronExpression != null          -> RecurrenceType.CRON
            repeatIntervalMinutes != null   -> RecurrenceType.INTERVAL_MINUTES
            else                            -> recurrenceType
        }
        val scheduledTime = computeInitialScheduledTime(
            delayMinutes = delayMinutes,
            recurrenceType = selectedRecurrence,
            cronExpression = cronExpression,
            timeOfDay = timeOfDay,
            dayOfWeek = dayOfWeek,
            dayOfMonth = dayOfMonth
        )

        // If the task has unmet dependencies, start it in WAITING_DEPENDENCY
        val initialStatus = if (dependsOn.isNotEmpty() &&
            dependsOn.any { depId -> tasks.none { it.id == depId && it.status == TaskStatus.COMPLETED } }
        ) TaskStatus.WAITING_DEPENDENCY else TaskStatus.PENDING

        val id = UUID.randomUUID().toString()
        val task = ScheduledTask(
            id = id,
            name = name,
            prompt = prompt,
            scheduledTimeMillis = scheduledTime,
            createdAtMillis = System.currentTimeMillis(),
            recurrenceType = selectedRecurrence,
            timeOfDay = timeOfDay,
            dayOfWeek = dayOfWeek,
            dayOfMonth = dayOfMonth,
            repeatIntervalMinutes = repeatIntervalMinutes,
            cronExpression = cronExpression,
            maxRuns = maxRuns,
            runCount = 0,
            priority = priority,
            tags = tags.map { it.trim() }.filter { it.isNotEmpty() },
            maxRetries = maxRetries,
            retryCount = 0,
            timeoutMinutes = timeoutMinutes,
            dependsOn = dependsOn,
            notifyOnComplete = notifyOnComplete,
            notifyOnFail = notifyOnFail,
            status = initialStatus
        )
        tasks.add(task)
        notifyChanged()

        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return ToolExecutionResult(
            output = buildString {
                appendLine("✅ Task scheduled successfully.")
                appendLine("ID:          $id")
                appendLine("Name:        $name")
                appendLine("Priority:    ${priority.label}")
                appendLine("Scheduled:   ${dateFormat.format(Date(scheduledTime))}")
                appendLine("Recurrence:  ${recurrenceSummary(task)}")
                if (tags.isNotEmpty())        appendLine("Tags:        ${tags.joinToString(", ")}")
                if (timeoutMinutes != null)   appendLine("Timeout:     ${timeoutMinutes}m")
                if (maxRetries > 0)           appendLine("Max retries: $maxRetries")
                if (maxRuns != null)          appendLine("Max runs:    $maxRuns")
                if (dependsOn.isNotEmpty())   appendLine("Depends on:  ${dependsOn.joinToString(", ")}")
                appendLine("Status:      $initialStatus")
            }.trimEnd()
        )
    }

    private fun listTasks(): ToolExecutionResult {
        if (tasks.isEmpty()) return ToolExecutionResult(output = "No scheduled tasks.")
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return ToolExecutionResult(output = buildString {
            appendLine("📋 ${tasks.size} task(s) — sorted by priority then schedule time:\n")
            tasks.sortedWith(compareBy({ it.priority.order }, { it.scheduledTimeMillis }))
                .forEachIndexed { i, t ->
                    appendLine("─── ${i + 1} ───")
                    appendLine(formatTaskSummary(t, fmt))
                    appendLine()
                }
        }.trimEnd())
    }

    private fun cancelTask(taskId: String?): ToolExecutionResult {
        if (taskId.isNullOrBlank()) return ToolExecutionResult(output = "'taskId' is required.", isError = true)
        val idx = tasks.indexOfFirst { it.id == taskId }
        if (idx == -1) return ToolExecutionResult(output = "Task not found: $taskId", isError = true)
        tasks[idx] = tasks[idx].copy(status = TaskStatus.CANCELLED)
        notifyChanged()
        return ToolExecutionResult(output = "🚫 Task '${tasks[idx].name}' ($taskId) cancelled.")
    }

    private fun taskStatus(taskId: String?): ToolExecutionResult {
        if (taskId.isNullOrBlank()) return ToolExecutionResult(output = "'taskId' is required.", isError = true)
        val task = tasks.find { it.id == taskId }
            ?: return ToolExecutionResult(output = "Task not found: $taskId", isError = true)
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return ToolExecutionResult(output = formatTaskSummary(task, fmt, verbose = true))
    }

    private fun pauseTask(taskId: String?): ToolExecutionResult {
        if (taskId.isNullOrBlank()) return ToolExecutionResult(output = "'taskId' is required.", isError = true)
        val idx = tasks.indexOfFirst { it.id == taskId }
        if (idx == -1) return ToolExecutionResult(output = "Task not found: $taskId", isError = true)
        val t = tasks[idx]
        if (t.status !in setOf(TaskStatus.PENDING, TaskStatus.WAITING_DEPENDENCY)) {
            return ToolExecutionResult(output = "Cannot pause task in status ${t.status}.", isError = true)
        }
        tasks[idx] = t.copy(status = TaskStatus.PAUSED)
        notifyChanged()
        return ToolExecutionResult(output = "⏸️ Task '${t.name}' paused.")
    }

    private fun resumeTask(taskId: String?): ToolExecutionResult {
        if (taskId.isNullOrBlank()) return ToolExecutionResult(output = "'taskId' is required.", isError = true)
        val idx = tasks.indexOfFirst { it.id == taskId }
        if (idx == -1) return ToolExecutionResult(output = "Task not found: $taskId", isError = true)
        val t = tasks[idx]
        if (t.status != TaskStatus.PAUSED) {
            return ToolExecutionResult(output = "Task is not paused (status: ${t.status}).", isError = true)
        }
        // Re-evaluate whether it should wait for dependencies
        val newStatus = if (t.dependsOn.isNotEmpty() &&
            t.dependsOn.any { depId -> tasks.none { it.id == depId && it.status == TaskStatus.COMPLETED } }
        ) TaskStatus.WAITING_DEPENDENCY else TaskStatus.PENDING
        tasks[idx] = t.copy(status = newStatus)
        notifyChanged()
        return ToolExecutionResult(output = "▶️ Task '${t.name}' resumed (status: $newStatus).")
    }

    private fun runNow(taskId: String?): ToolExecutionResult {
        if (taskId.isNullOrBlank()) return ToolExecutionResult(output = "'taskId' is required.", isError = true)
        val idx = tasks.indexOfFirst { it.id == taskId }
        if (idx == -1) return ToolExecutionResult(output = "Task not found: $taskId", isError = true)
        val t = tasks[idx]
        if (t.status == TaskStatus.RUNNING) {
            return ToolExecutionResult(output = "Task '${t.name}' is already running.", isError = true)
        }
        tasks[idx] = t.copy(
            scheduledTimeMillis = System.currentTimeMillis(),
            status = TaskStatus.PENDING
        )
        notifyChanged()
        return ToolExecutionResult(output = "⚡ Task '${t.name}' scheduled to run immediately.")
    }

    private fun retryTask(taskId: String?): ToolExecutionResult {
        if (taskId.isNullOrBlank()) return ToolExecutionResult(output = "'taskId' is required.", isError = true)
        val t = tasks.find { it.id == taskId }
            ?: return ToolExecutionResult(output = "Task not found: $taskId", isError = true)
        if (t.status !in setOf(TaskStatus.FAILED, TaskStatus.CANCELLED)) {
            return ToolExecutionResult(
                output = "Can only retry FAILED or CANCELLED tasks (current: ${t.status}).",
                isError = true
            )
        }
        val retryTask = t.copy(
            id = UUID.randomUUID().toString(),
            scheduledTimeMillis = System.currentTimeMillis(),
            status = TaskStatus.PENDING,
            retryCount = t.retryCount + 1,
            lastResult = null,
            startedAtMillis = null,
            completedAtMillis = null
        )
        tasks.add(retryTask)
        notifyChanged()
        return ToolExecutionResult(
            output = "🔁 Retry task created: ${retryTask.id} (attempt ${retryTask.retryCount + 1})."
        )
    }

    private fun updateTask(
        taskId: String?,
        newName: String?,
        newPrompt: String?,
        priority: TaskPriority,
        tags: List<String>,
        newTimeoutMinutes: Int?
    ): ToolExecutionResult {
        if (taskId.isNullOrBlank()) return ToolExecutionResult(output = "'taskId' is required.", isError = true)
        val idx = tasks.indexOfFirst { it.id == taskId }
        if (idx == -1) return ToolExecutionResult(output = "Task not found: $taskId", isError = true)
        val t = tasks[idx]
        if (t.status == TaskStatus.RUNNING) {
            return ToolExecutionResult(output = "Cannot update a RUNNING task.", isError = true)
        }
        tasks[idx] = t.copy(
            name             = if (!newName.isNullOrBlank()) newName else t.name,
            prompt           = if (!newPrompt.isNullOrBlank()) newPrompt else t.prompt,
            priority         = priority,
            tags             = if (tags.isNotEmpty()) tags.map { it.trim() }.filter { it.isNotEmpty() } else t.tags,
            timeoutMinutes   = newTimeoutMinutes ?: t.timeoutMinutes
        )
        notifyChanged()
        return ToolExecutionResult(output = "✏️ Task '${tasks[idx].name}' updated.")
    }

    private fun clearCompleted(): ToolExecutionResult {
        val before = tasks.size
        tasks.removeAll { it.status in setOf(TaskStatus.COMPLETED, TaskStatus.CANCELLED) }
        val removed = before - tasks.size
        notifyChanged()
        return ToolExecutionResult(output = "🗑️ Removed $removed completed/cancelled task(s).")
    }

    private fun listByTag(tag: String?): ToolExecutionResult {
        if (tag.isNullOrBlank()) return ToolExecutionResult(output = "'tag' is required.", isError = true)
        val filtered = tasks.filter { tag.lowercase() in it.tags.map { t -> t.lowercase() } }
        if (filtered.isEmpty()) return ToolExecutionResult(output = "No tasks with tag '$tag'.")
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return ToolExecutionResult(output = buildString {
            appendLine("🏷️ ${filtered.size} task(s) with tag '$tag':\n")
            filtered.forEachIndexed { i, t ->
                appendLine("─── ${i + 1} ───")
                appendLine(formatTaskSummary(t, fmt))
                appendLine()
            }
        }.trimEnd())
    }

    private fun listByPriority(priority: TaskPriority?): ToolExecutionResult {
        if (priority == null) return ToolExecutionResult(output = "'filterPriority' is required.", isError = true)
        val filtered = tasks.filter { it.priority == priority }
        if (filtered.isEmpty()) return ToolExecutionResult(output = "No tasks with priority '${priority.label}'.")
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return ToolExecutionResult(output = buildString {
            appendLine("🔝 ${filtered.size} ${priority.label}-priority task(s):\n")
            filtered.sortedBy { it.scheduledTimeMillis }.forEachIndexed { i, t ->
                appendLine("─── ${i + 1} ───")
                appendLine(formatTaskSummary(t, fmt))
                appendLine()
            }
        }.trimEnd())
    }

    private fun setPriority(taskId: String?, priority: TaskPriority): ToolExecutionResult {
        if (taskId.isNullOrBlank()) return ToolExecutionResult(output = "'taskId' is required.", isError = true)
        val idx = tasks.indexOfFirst { it.id == taskId }
        if (idx == -1) return ToolExecutionResult(output = "Task not found: $taskId", isError = true)
        tasks[idx] = tasks[idx].copy(priority = priority)
        notifyChanged()
        return ToolExecutionResult(output = "🔝 Task '${tasks[idx].name}' priority set to ${priority.label}.")
    }

    private fun addTags(taskId: String?, newTags: List<String>): ToolExecutionResult {
        if (taskId.isNullOrBlank()) return ToolExecutionResult(output = "'taskId' is required.", isError = true)
        if (newTags.isEmpty()) return ToolExecutionResult(output = "'tags' is required.", isError = true)
        val idx = tasks.indexOfFirst { it.id == taskId }
        if (idx == -1) return ToolExecutionResult(output = "Task not found: $taskId", isError = true)
        val merged = (tasks[idx].tags + newTags.map { it.trim() }.filter { it.isNotEmpty() }).distinct()
        tasks[idx] = tasks[idx].copy(tags = merged)
        notifyChanged()
        return ToolExecutionResult(output = "🏷️ Tags updated: ${merged.joinToString(", ")}.")
    }

    private fun removeTags(taskId: String?, removedTags: List<String>): ToolExecutionResult {
        if (taskId.isNullOrBlank()) return ToolExecutionResult(output = "'taskId' is required.", isError = true)
        val idx = tasks.indexOfFirst { it.id == taskId }
        if (idx == -1) return ToolExecutionResult(output = "Task not found: $taskId", isError = true)
        val cleaned = tasks[idx].tags.filterNot { it.lowercase() in removedTags.map { t -> t.lowercase() } }
        tasks[idx] = tasks[idx].copy(tags = cleaned)
        notifyChanged()
        return ToolExecutionResult(output = "🏷️ Remaining tags: ${if (cleaned.isEmpty()) "(none)" else cleaned.joinToString(", ")}.")
    }

    private fun chainTasks(taskIds: List<String>): ToolExecutionResult {
        if (taskIds.size < 2) {
            return ToolExecutionResult(
                output = "chain requires at least 2 task IDs in 'taskIds'.",
                isError = true
            )
        }
        val notFound = taskIds.filter { id -> tasks.none { it.id == id } }
        if (notFound.isNotEmpty()) {
            return ToolExecutionResult(
                output = "Unknown task IDs: ${notFound.joinToString(", ")}",
                isError = true
            )
        }
        // Each task[i] depends on task[i-1]
        for (i in 1 until taskIds.size) {
            val idx = tasks.indexOfFirst { it.id == taskIds[i] }
            val prevId = taskIds[i - 1]
            val existing = tasks[idx].dependsOn
            if (prevId !in existing) {
                tasks[idx] = tasks[idx].copy(
                    dependsOn = existing + prevId,
                    status = TaskStatus.WAITING_DEPENDENCY
                )
            }
        }
        notifyChanged()
        val names = taskIds.mapNotNull { id -> tasks.find { it.id == id }?.name }
        return ToolExecutionResult(
            output = "🔗 Chain created: ${names.joinToString(" → ")}."
        )
    }

    // ── Lifecycle helpers (called by external task runner) ────────────────

    /**
     * Returns all tasks that are due for execution, in priority order.
     *
     * A task is "ready" when:
     *  - status is [TaskStatus.PENDING]
     *  - [ScheduledTask.scheduledTimeMillis] ≤ now
     *  - all tasks listed in [ScheduledTask.dependsOn] have status [TaskStatus.COMPLETED]
     *
     * Also promotes WAITING_DEPENDENCY tasks whose deps are now all complete.
     */
    fun getReadyTasks(): List<ScheduledTask> {
        // First, promote any WAITING_DEPENDENCY tasks whose deps are now satisfied
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

    /** Transitions a task to [TaskStatus.RUNNING] and records the start time. */
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

    /**
     * Transitions a task to [TaskStatus.COMPLETED], increments [ScheduledTask.runCount],
     * records the completion time, and prepends the result to [ScheduledTask.executionHistory]
     * (keeping the last 10 entries).
     */
    fun markCompleted(taskId: String, result: String) {
        val idx = tasks.indexOfFirst { it.id == taskId }
        if (idx != -1) {
            val t = tasks[idx]
            val history = (listOf(result) + t.executionHistory).take(10)
            tasks[idx] = t.copy(
                status = TaskStatus.COMPLETED,
                lastResult = result,
                completedAtMillis = System.currentTimeMillis(),
                runCount = t.runCount + 1,
                executionHistory = history
            )
            notifyChanged()
        }
    }

    /**
     * Transitions a task to [TaskStatus.FAILED].
     *
     * If [ScheduledTask.retryCount] < [ScheduledTask.maxRetries], a new PENDING retry
     * task is automatically queued instead of marking the original as failed permanently.
     */
    fun markFailed(taskId: String, error: String) {
        val idx = tasks.indexOfFirst { it.id == taskId }
        if (idx == -1) return
        val t = tasks[idx]
        val history = (listOf("FAILED: $error") + t.executionHistory).take(10)
        tasks[idx] = t.copy(
            status = TaskStatus.FAILED,
            lastResult = error,
            executionHistory = history
        )
        notifyChanged()

        // Auto-retry if retries remain
        if (t.retryCount < t.maxRetries) {
            val backoffMs = (1L shl t.retryCount.coerceAtMost(6)) * 60_000L // exponential: 1m,2m,4m,8m…
            val retryTask = t.copy(
                id = UUID.randomUUID().toString(),
                scheduledTimeMillis = System.currentTimeMillis() + backoffMs,
                status = TaskStatus.PENDING,
                retryCount = t.retryCount + 1,
                lastResult = null,
                startedAtMillis = null,
                completedAtMillis = null,
                executionHistory = history
            )
            tasks.add(retryTask)
            notifyChanged()
        }
    }

    /**
     * If [taskId] is a repeating task (non-ONCE recurrence) and has not exceeded
     * [ScheduledTask.maxRuns], creates a new PENDING copy with the next scheduled time.
     * The old completed/failed instance is removed to prevent unbounded list growth.
     */
    fun rescheduleRepeating(taskId: String) {
        val task = tasks.find { it.id == taskId } ?: return
        val nextSchedule = computeNextScheduledTime(task) ?: return

        // Respect maxRuns
        if (task.maxRuns != null && task.runCount >= task.maxRuns) return

        val newTask = task.copy(
            id = UUID.randomUUID().toString(),
            scheduledTimeMillis = nextSchedule,
            status = TaskStatus.PENDING,
            lastResult = null,
            startedAtMillis = null,
            completedAtMillis = null
        )
        tasks.add(newTask)
        tasks.removeAll { it.id == taskId }
        notifyChanged()
    }

    /**
     * Returns the timeout deadline for a running task, or null if no timeout is set.
     * The caller should abort the task if [System.currentTimeMillis] exceeds this value.
     */
    fun getTimeoutDeadlineMillis(taskId: String): Long? {
        val t = tasks.find { it.id == taskId } ?: return null
        val started = t.startedAtMillis ?: return null
        val timeout = t.timeoutMinutes ?: return null
        return started + timeout * 60_000L
    }

    // ── Public list access ───────────────────────────────────────────────

    /** Returns a snapshot of all tasks (any status). Used by the Scheduler Dashboard UI. */
    fun getAllTasks(): List<ScheduledTask> = tasks.toList()

    /** Cancels a task by ID without removing it from the list (status → CANCELLED). */
    fun cancelTaskById(taskId: String) {
        val index = tasks.indexOfFirst { it.id == taskId }
        if (index != -1) {
            tasks[index] = tasks[index].copy(status = TaskStatus.CANCELLED)
            notifyChanged()
        }
    }

    /** Permanently removes a task from the list by ID. */
    fun deleteTask(taskId: String) {
        tasks.removeAll { it.id == taskId }
        notifyChanged()
    }

    /** Pauses a PENDING task directly (used from the UI). */
    fun pauseTaskById(taskId: String) {
        val idx = tasks.indexOfFirst { it.id == taskId }
        if (idx != -1 && tasks[idx].status in setOf(TaskStatus.PENDING, TaskStatus.WAITING_DEPENDENCY)) {
            tasks[idx] = tasks[idx].copy(status = TaskStatus.PAUSED)
            notifyChanged()
        }
    }

    /** Resumes a PAUSED task directly (used from the UI). */
    fun resumeTaskById(taskId: String) {
        val idx = tasks.indexOfFirst { it.id == taskId }
        if (idx != -1 && tasks[idx].status == TaskStatus.PAUSED) {
            val t = tasks[idx]
            val completedIds = tasks.filter { it.status == TaskStatus.COMPLETED }.map { it.id }.toSet()
            val newStatus = if (t.dependsOn.isNotEmpty() && t.dependsOn.any { it !in completedIds })
                TaskStatus.WAITING_DEPENDENCY else TaskStatus.PENDING
            tasks[idx] = t.copy(status = newStatus)
            notifyChanged()
        }
    }

    /** Reschedules a FAILED task to run immediately (used from the UI). */
    fun retryTaskById(taskId: String) {
        val t = tasks.find { it.id == taskId } ?: return
        tasks.add(t.copy(
            id = UUID.randomUUID().toString(),
            scheduledTimeMillis = System.currentTimeMillis(),
            status = TaskStatus.PENDING,
            retryCount = t.retryCount + 1,
            lastResult = null,
            startedAtMillis = null,
            completedAtMillis = null
        ))
        notifyChanged()
    }

    /** Makes a PENDING/PAUSED task run immediately (used from the UI). */
    fun runNowById(taskId: String) {
        val idx = tasks.indexOfFirst { it.id == taskId }
        if (idx != -1) {
            tasks[idx] = tasks[idx].copy(
                scheduledTimeMillis = System.currentTimeMillis(),
                status = TaskStatus.PENDING
            )
            notifyChanged()
        }
    }

    /**
     * Schedules a task directly (bypassing the agent). Called from the Scheduler Dashboard.
     */
    fun scheduleTaskDirectly(
        name: String,
        prompt: String,
        delayMinutes: Int,
        repeatIntervalMinutes: Int?,
        priority: TaskPriority = TaskPriority.NORMAL,
        tags: List<String> = emptyList(),
        maxRetries: Int = 0,
        timeoutMinutes: Int? = null,
        maxRuns: Int? = null,
        cronExpression: String? = null,
        recurrenceType: RecurrenceType = RecurrenceType.ONCE,
        timeOfDay: String? = null,
        dayOfWeek: Int? = null,
        dayOfMonth: Int? = null,
        dependsOn: List<String> = emptyList(),
        notifyOnComplete: Boolean = false,
        notifyOnFail: Boolean = true
    ) {
        val selectedRecurrence = when {
            cronExpression != null        -> RecurrenceType.CRON
            repeatIntervalMinutes != null -> RecurrenceType.INTERVAL_MINUTES
            else                          -> recurrenceType
        }
        val scheduledTime = computeInitialScheduledTime(
            delayMinutes = delayMinutes,
            recurrenceType = selectedRecurrence,
            cronExpression = cronExpression,
            timeOfDay = timeOfDay,
            dayOfWeek = dayOfWeek,
            dayOfMonth = dayOfMonth
        )
        val completedIds = tasks.filter { it.status == TaskStatus.COMPLETED }.map { it.id }.toSet()
        val initialStatus = if (dependsOn.isNotEmpty() && dependsOn.any { it !in completedIds })
            TaskStatus.WAITING_DEPENDENCY else TaskStatus.PENDING

        tasks.add(
            ScheduledTask(
                id = UUID.randomUUID().toString(),
                name = name,
                prompt = prompt,
                scheduledTimeMillis = scheduledTime,
                createdAtMillis = System.currentTimeMillis(),
                recurrenceType = selectedRecurrence,
                timeOfDay = timeOfDay,
                dayOfWeek = dayOfWeek,
                dayOfMonth = dayOfMonth,
                repeatIntervalMinutes = repeatIntervalMinutes,
                cronExpression = cronExpression,
                maxRuns = maxRuns,
                priority = priority,
                tags = tags.map { it.trim() }.filter { it.isNotEmpty() },
                maxRetries = maxRetries,
                timeoutMinutes = timeoutMinutes,
                dependsOn = dependsOn,
                notifyOnComplete = notifyOnComplete,
                notifyOnFail = notifyOnFail,
                status = initialStatus
            )
        )
        notifyChanged()
    }

    // ── Agent dispatch ───────────────────────────────────────────────────

    /**
     * Dispatches a tool call from the agent loop to the appropriate handler.
     */
    suspend fun executeTool(
        name: String,
        arguments: Map<String, String>
    ): ToolExecutionResult {
        if (name != "task_scheduler") {
            return ToolExecutionResult(output = "Unknown tool: $name", isError = true)
        }
        val action = arguments["action"]
            ?: return ToolExecutionResult(output = "'action' is required.", isError = true)

        val delayMinutes = (arguments["delayMinutes"]?.toIntOrNull() ?: 0).coerceAtLeast(0)
        val repeatInterval = arguments["repeatIntervalMinutes"]?.toIntOrNull()
        if (repeatInterval != null && repeatInterval <= 0) {
            return ToolExecutionResult(output = "'repeatIntervalMinutes' must be > 0.", isError = true)
        }
        val priority = parsePriority(arguments["priority"])
        val tags = arguments["tags"]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        val maxRetries = arguments["maxRetries"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val timeoutMinutes = arguments["timeoutMinutes"]?.toIntOrNull()?.takeIf { it > 0 }
        val maxRuns = arguments["maxRuns"]?.toIntOrNull()?.takeIf { it > 0 }
        val dependsOn = arguments["dependsOn"]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        val notifyOnComplete = arguments["notifyOnComplete"]?.lowercase() == "true"
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

    // ── Formatting helpers ───────────────────────────────────────────────

    private fun formatTaskSummary(
        t: ScheduledTask,
        fmt: SimpleDateFormat,
        verbose: Boolean = false
    ): String = buildString {
        appendLine("ID:          ${t.id}")
        appendLine("Name:        ${t.name}")
        appendLine("Priority:    ${t.priority.label}")
        appendLine("Status:      ${t.status}")
        appendLine("Scheduled:   ${fmt.format(Date(t.scheduledTimeMillis))}")
        appendLine("Created:     ${fmt.format(Date(t.createdAtMillis))}")
        appendLine("Recurrence:  ${recurrenceSummary(t)}")
        if (t.tags.isNotEmpty())       appendLine("Tags:        ${t.tags.joinToString(", ")}")
        if (t.timeoutMinutes != null)  appendLine("Timeout:     ${t.timeoutMinutes}m")
        if (t.maxRetries > 0)          appendLine("Retries:     ${t.retryCount}/${t.maxRetries}")
        if (t.maxRuns != null)         appendLine("Runs:        ${t.runCount}/${t.maxRuns}")
        else if (t.runCount > 0)       appendLine("Run count:   ${t.runCount}")
        if (t.dependsOn.isNotEmpty())  appendLine("Depends on:  ${t.dependsOn.joinToString(", ")}")
        if (t.startedAtMillis != null) appendLine("Started:     ${fmt.format(Date(t.startedAtMillis))}")
        if (t.completedAtMillis != null) appendLine("Completed:   ${fmt.format(Date(t.completedAtMillis))}")
        if (t.lastResult != null)      appendLine("Last Result: ${t.lastResult}")
        if (verbose && t.executionHistory.size > 1) {
            appendLine("History:")
            t.executionHistory.drop(1).take(5).forEachIndexed { i, r ->
                appendLine("  [${i + 2}] $r")
            }
        }
    }.trimEnd()

    private fun recurrenceSummary(task: ScheduledTask): String = when (task.recurrenceType) {
        RecurrenceType.ONCE             -> "one-time"
        RecurrenceType.INTERVAL_MINUTES -> "every ${task.repeatIntervalMinutes ?: "?"} minute(s)"
        RecurrenceType.DAILY            -> "daily at ${task.timeOfDay ?: "--:--"}"
        RecurrenceType.WEEKLY           -> "weekly (${dayName(task.dayOfWeek)}) at ${task.timeOfDay ?: "--:--"}"
        RecurrenceType.MONTHLY          -> "monthly (day ${task.dayOfMonth ?: 1}) at ${task.timeOfDay ?: "--:--"}"
        RecurrenceType.CRON             -> "cron: ${task.cronExpression ?: "?"}"
    }

    private fun parsePriority(raw: String?): TaskPriority = when (raw?.trim()?.lowercase(Locale.ROOT)) {
        "low"      -> TaskPriority.LOW
        "high"     -> TaskPriority.HIGH
        "critical" -> TaskPriority.CRITICAL
        else       -> TaskPriority.NORMAL
    }

    private fun parseRecurrence(raw: String?): RecurrenceType = when (raw?.trim()?.lowercase(Locale.ROOT)) {
        "interval", "interval_minutes", "every_minutes" -> RecurrenceType.INTERVAL_MINUTES
        "daily"   -> RecurrenceType.DAILY
        "weekly"  -> RecurrenceType.WEEKLY
        "monthly" -> RecurrenceType.MONTHLY
        "cron"    -> RecurrenceType.CRON
        else      -> RecurrenceType.ONCE
    }

    private fun dayName(dayOfWeek: Int?): String = when (dayOfWeek) {
        1 -> "Mon"; 2 -> "Tue"; 3 -> "Wed"; 4 -> "Thu"
        5 -> "Fri"; 6 -> "Sat"; 7 -> "Sun"
        else -> "unknown"
    }

    private fun parseTimeOfDay(timeOfDay: String?): Pair<Int, Int>? {
        if (timeOfDay.isNullOrBlank()) return null
        val parts = timeOfDay.split(":")
        if (parts.size != 2) return null
        val hour   = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour to minute
    }

    // ── CRON support ─────────────────────────────────────────────────────

    /** Returns true if [expr] is a syntactically valid 5-field cron expression. */
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
            } else {
                (part.toIntOrNull() ?: return@all false) in range
            }
        }
    }

    /**
     * Computes the next time (in epoch ms) that matches [expr] after [afterMillis].
     * Iterates forward minute-by-minute up to 1 year.  Returns null if no match found.
     */
    private fun nextCronTime(expr: String, afterMillis: Long): Long? {
        val fields = expr.trim().split("\\s+".toRegex())
        if (fields.size != 5) return null
        val (minF, hourF, domF, monthF, dowF) = fields
        val cal = Calendar.getInstance().apply {
            timeInMillis = afterMillis + 60_000L
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val limit = afterMillis + 366L * 24 * 60 * 60_000
        while (cal.timeInMillis < limit) {
            val min   = cal.get(Calendar.MINUTE)
            val hour  = cal.get(Calendar.HOUR_OF_DAY)
            val dom   = cal.get(Calendar.DAY_OF_MONTH)
            val month = cal.get(Calendar.MONTH) + 1
            // Calendar: 1=Sun..7=Sat → cron 0=Sun..6=Sat
            val dow   = cal.get(Calendar.DAY_OF_WEEK) - 1

            if (matchCronField(minF,   min,   0..59) &&
                matchCronField(hourF,  hour,  0..23) &&
                matchCronField(domF,   dom,   1..31) &&
                matchCronField(monthF, month, 1..12) &&
                (matchCronField(dowF, dow, 0..6) || matchCronField(dowF, if (dow == 0) 7 else dow, 0..7))) {
                return cal.timeInMillis
            }
            cal.add(Calendar.MINUTE, 1)
        }
        return null
    }

    private fun matchCronField(field: String, value: Int, range: IntRange): Boolean {
        if (field == "*") return true
        if (field.startsWith("*/")) {
            val step = field.substring(2).toIntOrNull() ?: return false
            return (value - range.first) % step == 0
        }
        return field.split(",").any { part ->
            if ("-" in part && !part.startsWith("-")) {
                val lr = part.split("-", limit = 2)
                val a = lr[0].toIntOrNull() ?: return@any false
                val b = lr[1].toIntOrNull() ?: return@any false
                value in a..b
            } else {
                part.toIntOrNull() == value
            }
        }
    }

    // ── Scheduling time computation ──────────────────────────────────────

    private fun computeInitialScheduledTime(
        delayMinutes: Int,
        recurrenceType: RecurrenceType,
        cronExpression: String?,
        timeOfDay: String?,
        dayOfWeek: Int?,
        dayOfMonth: Int?
    ): Long {
        if (delayMinutes > 0) return System.currentTimeMillis() + delayMinutes * 60_000L
        val now = Calendar.getInstance()
        return when (recurrenceType) {
            RecurrenceType.ONCE, RecurrenceType.INTERVAL_MINUTES -> now.timeInMillis
            RecurrenceType.DAILY    -> nextDaily(now, timeOfDay).timeInMillis
            RecurrenceType.WEEKLY   -> nextWeekly(now, timeOfDay, dayOfWeek).timeInMillis
            RecurrenceType.MONTHLY  -> nextMonthly(now, timeOfDay, dayOfMonth).timeInMillis
            RecurrenceType.CRON     -> nextCronTime(cronExpression ?: "* * * * *", now.timeInMillis)
                                        ?: now.timeInMillis
        }
    }

    private fun computeNextScheduledTime(task: ScheduledTask): Long? {
        val now = Calendar.getInstance()
        return when (task.recurrenceType) {
            RecurrenceType.ONCE             -> null
            RecurrenceType.INTERVAL_MINUTES -> {
                val interval = task.repeatIntervalMinutes ?: return null
                System.currentTimeMillis() + interval * 60_000L
            }
            RecurrenceType.DAILY   -> nextDaily(now, task.timeOfDay).timeInMillis
            RecurrenceType.WEEKLY  -> nextWeekly(now, task.timeOfDay, task.dayOfWeek).timeInMillis
            RecurrenceType.MONTHLY -> nextMonthly(now, task.timeOfDay, task.dayOfMonth).timeInMillis
            RecurrenceType.CRON    -> nextCronTime(task.cronExpression ?: "* * * * *", now.timeInMillis)
        }
    }

    private fun nextDaily(now: Calendar, timeOfDay: String?): Calendar {
        val (h, m) = parseTimeOfDay(timeOfDay) ?: (now.get(Calendar.HOUR_OF_DAY) to now.get(Calendar.MINUTE))
        return (now.clone() as Calendar).apply {
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m)
            if (!after(now)) add(Calendar.DAY_OF_YEAR, 1)
        }
    }

    private fun nextWeekly(now: Calendar, timeOfDay: String?, dayOfWeek: Int?): Calendar {
        val targetDay = toCalendarDayOfWeek(dayOfWeek) ?: now.get(Calendar.DAY_OF_WEEK)
        val (h, m) = parseTimeOfDay(timeOfDay) ?: (now.get(Calendar.HOUR_OF_DAY) to now.get(Calendar.MINUTE))
        return (now.clone() as Calendar).apply {
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m)
            var guard = 0
            while ((get(Calendar.DAY_OF_WEEK) != targetDay || !after(now)) && guard++ < 8) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }
    }

    private fun nextMonthly(now: Calendar, timeOfDay: String?, dayOfMonth: Int?): Calendar {
        val targetDay = (dayOfMonth ?: 1).coerceIn(1, 31)
        val (h, m) = parseTimeOfDay(timeOfDay) ?: (now.get(Calendar.HOUR_OF_DAY) to now.get(Calendar.MINUTE))
        return (now.clone() as Calendar).apply {
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m)
            fun applyDay() = set(Calendar.DAY_OF_MONTH,
                targetDay.coerceAtMost(getActualMaximum(Calendar.DAY_OF_MONTH)))
            applyDay()
            if (!after(now)) { add(Calendar.MONTH, 1); applyDay() }
        }
    }

    private fun toCalendarDayOfWeek(userDay: Int?): Int? = when (userDay) {
        1 -> Calendar.MONDAY;   2 -> Calendar.TUESDAY;  3 -> Calendar.WEDNESDAY
        4 -> Calendar.THURSDAY; 5 -> Calendar.FRIDAY;   6 -> Calendar.SATURDAY
        7 -> Calendar.SUNDAY
        else -> null
    }
}
