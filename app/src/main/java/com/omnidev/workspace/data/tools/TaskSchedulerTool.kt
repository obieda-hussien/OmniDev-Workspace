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
 * Manages scheduled autonomous AI tasks.
 *
 * Tasks are stored in a thread-safe in-memory list and can be scheduled,
 * listed, cancelled, or queried by the ReAct agent through the `task_scheduler`
 * tool. A background runner (external to this object) should periodically call
 * [getReadyTasks] and drive their execution lifecycle via [markRunning],
 * [markCompleted], [markFailed], and [rescheduleRepeating].
 *
 * The [tasksFlow] [StateFlow] emits the current snapshot whenever the list
 * is mutated — use it from Compose screens instead of polling.
 */
object TaskSchedulerTool {

    /** Possible lifecycle states for a scheduled task. */
    enum class TaskStatus { PENDING, RUNNING, COMPLETED, FAILED, CANCELLED }

    /** Supported recurrence presets for calendar-based scheduling. */
    enum class RecurrenceType { ONCE, INTERVAL_MINUTES, DAILY, WEEKLY, MONTHLY }

    /**
     * Immutable snapshot describing a single scheduled task.
     */
    data class ScheduledTask(
        val id: String,
        val name: String,
        val prompt: String,
        val scheduledTimeMillis: Long,
        val recurrenceType: RecurrenceType = RecurrenceType.ONCE,
        val timeOfDay: String? = null,
        val dayOfWeek: Int? = null,
        val dayOfMonth: Int? = null,
        val repeatIntervalMinutes: Int?,
        val status: TaskStatus,
        val lastResult: String? = null
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
            description = "Schedule, list, or cancel autonomous AI tasks. " +
                    "Scheduled tasks run automatically at the specified time.",
            parameters = listOf(
                ToolParameter(
                    name = "action",
                    type = "string",
                    description = "Action: 'schedule', 'list', 'cancel', 'status'",
                    required = true
                ),
                ToolParameter(
                    name = "name",
                    type = "string",
                    description = "Task name for 'schedule' action",
                    required = false
                ),
                ToolParameter(
                    name = "prompt",
                    type = "string",
                    description = "AI prompt to execute for 'schedule' action",
                    required = false
                ),
                ToolParameter(
                    name = "delayMinutes",
                    type = "string",
                    description = "Minutes from now to execute (default: 0 for immediate)",
                    required = false
                ),
                ToolParameter(
                    name = "repeatIntervalMinutes",
                    type = "string",
                    description = "Repeat every N minutes (null for one-shot)",
                    required = false
                ),
                ToolParameter(
                    name = "recurrence",
                    type = "string",
                    description = "Optional recurrence preset: once, daily, weekly, monthly.",
                    required = false
                ),
                ToolParameter(
                    name = "timeOfDay",
                    type = "string",
                    description = "Optional local time in HH:mm for daily/weekly/monthly runs.",
                    required = false
                ),
                ToolParameter(
                    name = "dayOfWeek",
                    type = "string",
                    description = "For weekly recurrence: day index 1..7 (Mon..Sun).",
                    required = false
                ),
                ToolParameter(
                    name = "dayOfMonth",
                    type = "string",
                    description = "For monthly recurrence: day 1..31.",
                    required = false
                ),
                ToolParameter(
                    name = "taskId",
                    type = "string",
                    description = "Task ID for 'cancel' or 'status' actions",
                    required = false
                )
            )
        )
    )

    // ── Execution ────────────────────────────────────────────────────────

    /**
     * Handles the four scheduling actions: schedule, list, cancel, status.
     */
    suspend fun execute(
        action: String,
        name: String? = null,
        prompt: String? = null,
        delayMinutes: Int = 0,
        repeatIntervalMinutes: Int? = null,
        recurrenceType: RecurrenceType = RecurrenceType.ONCE,
        timeOfDay: String? = null,
        dayOfWeek: Int? = null,
        dayOfMonth: Int? = null,
        taskId: String? = null
    ): ToolExecutionResult = withContext(Dispatchers.IO) {
        runCatching {
            when (action.lowercase(Locale.ROOT)) {
                "schedule" -> scheduleTask(
                    name = name,
                    prompt = prompt,
                    delayMinutes = delayMinutes,
                    repeatIntervalMinutes = repeatIntervalMinutes,
                    recurrenceType = recurrenceType,
                    timeOfDay = timeOfDay,
                    dayOfWeek = dayOfWeek,
                    dayOfMonth = dayOfMonth
                )
                "list" -> listTasks()
                "cancel" -> cancelTask(taskId)
                "status" -> taskStatus(taskId)
                else -> ToolExecutionResult(
                    output = "Unknown action '$action'. Use: schedule, list, cancel, status.",
                    isError = true
                )
            }
        }.getOrElse { e ->
            ToolExecutionResult(
                output = "Error in task_scheduler: ${e.message}",
                isError = true
            )
        }
    }

    // ── Action handlers ──────────────────────────────────────────────────

    private fun scheduleTask(
        name: String?,
        prompt: String?,
        delayMinutes: Int,
        repeatIntervalMinutes: Int?,
        recurrenceType: RecurrenceType,
        timeOfDay: String?,
        dayOfWeek: Int?,
        dayOfMonth: Int?
    ): ToolExecutionResult {
        if (name.isNullOrBlank()) {
            return ToolExecutionResult(output = "'name' is required for schedule action.", isError = true)
        }
        if (prompt.isNullOrBlank()) {
            return ToolExecutionResult(output = "'prompt' is required for schedule action.", isError = true)
        }

        val id = UUID.randomUUID().toString()
        val selectedRecurrence = when {
            repeatIntervalMinutes != null -> RecurrenceType.INTERVAL_MINUTES
            else -> recurrenceType
        }
        val scheduledTime = computeInitialScheduledTime(
            delayMinutes = delayMinutes,
            recurrenceType = selectedRecurrence,
            timeOfDay = timeOfDay,
            dayOfWeek = dayOfWeek,
            dayOfMonth = dayOfMonth
        )
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

        val task = ScheduledTask(
            id = id,
            name = name,
            prompt = prompt,
            scheduledTimeMillis = scheduledTime,
            recurrenceType = selectedRecurrence,
            timeOfDay = timeOfDay,
            dayOfWeek = dayOfWeek,
            dayOfMonth = dayOfMonth,
            repeatIntervalMinutes = repeatIntervalMinutes,
            status = TaskStatus.PENDING
        )
        tasks.add(task)
        notifyChanged()

        val repeatInfo = "\nRecurrence:  ${recurrenceSummary(task)}"

        return ToolExecutionResult(
            output = """
                |✅ Task scheduled successfully.
                |ID:          $id
                |Name:        $name
                |Scheduled:   ${dateFormat.format(Date(scheduledTime))}$repeatInfo
                |Status:      PENDING
            """.trimMargin()
        )
    }

    private fun listTasks(): ToolExecutionResult {
        if (tasks.isEmpty()) {
            return ToolExecutionResult(output = "No scheduled tasks.")
        }

        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val sb = StringBuilder()
        sb.appendLine("📋 ${tasks.size} task(s):\n")
        tasks.forEachIndexed { index, t ->
            sb.appendLine("─── ${index + 1} ───")
            sb.appendLine("ID:          ${t.id}")
            sb.appendLine("Name:        ${t.name}")
            sb.appendLine("Scheduled:   ${dateFormat.format(Date(t.scheduledTimeMillis))}")
            sb.appendLine("Status:      ${t.status}")
            sb.appendLine("Recurrence:  ${recurrenceSummary(t)}")
            if (t.lastResult != null) {
                sb.appendLine("Last Result: ${t.lastResult}")
            }
            sb.appendLine()
        }
        return ToolExecutionResult(output = sb.toString().trimEnd())
    }

    private fun cancelTask(taskId: String?): ToolExecutionResult {
        if (taskId.isNullOrBlank()) {
            return ToolExecutionResult(output = "'taskId' is required for cancel action.", isError = true)
        }
        val index = tasks.indexOfFirst { it.id == taskId }
        if (index == -1) {
            return ToolExecutionResult(output = "Task not found: $taskId", isError = true)
        }
        tasks[index] = tasks[index].copy(status = TaskStatus.CANCELLED)
        notifyChanged()
        return ToolExecutionResult(output = "🚫 Task '$taskId' cancelled.")
    }

    private fun taskStatus(taskId: String?): ToolExecutionResult {
        if (taskId.isNullOrBlank()) {
            return ToolExecutionResult(output = "'taskId' is required for status action.", isError = true)
        }
        val task = tasks.find { it.id == taskId }
            ?: return ToolExecutionResult(output = "Task not found: $taskId", isError = true)

        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val sb = StringBuilder()
        sb.appendLine("ID:          ${task.id}")
        sb.appendLine("Name:        ${task.name}")
        sb.appendLine("Prompt:      ${task.prompt}")
        sb.appendLine("Scheduled:   ${dateFormat.format(Date(task.scheduledTimeMillis))}")
        sb.appendLine("Status:      ${task.status}")
        sb.appendLine("Recurrence:  ${recurrenceSummary(task)}")
        if (task.lastResult != null) {
            sb.appendLine("Last Result: ${task.lastResult}")
        }
        return ToolExecutionResult(output = sb.toString().trimEnd())
    }

    // ── Lifecycle helpers (called by external task runner) ────────────────

    /** Returns all tasks that are due for execution. */
    fun getReadyTasks(): List<ScheduledTask> =
        tasks.filter {
            it.status == TaskStatus.PENDING && System.currentTimeMillis() >= it.scheduledTimeMillis
        }

    /** Transitions a task to [TaskStatus.RUNNING]. */
    fun markRunning(taskId: String, executionDetails: String? = null) {
        val index = tasks.indexOfFirst { it.id == taskId }
        if (index != -1) {
            tasks[index] = tasks[index].copy(
                status = TaskStatus.RUNNING,
                lastResult = executionDetails ?: tasks[index].lastResult
            )
            notifyChanged()
        }
    }

    /** Transitions a task to [TaskStatus.COMPLETED] and records the result. */
    fun markCompleted(taskId: String, result: String) {
        val index = tasks.indexOfFirst { it.id == taskId }
        if (index != -1) {
            tasks[index] = tasks[index].copy(status = TaskStatus.COMPLETED, lastResult = result)
            notifyChanged()
        }
    }

    /** Transitions a task to [TaskStatus.FAILED] and records the error. */
    fun markFailed(taskId: String, error: String) {
        val index = tasks.indexOfFirst { it.id == taskId }
        if (index != -1) {
            tasks[index] = tasks[index].copy(status = TaskStatus.FAILED, lastResult = error)
            notifyChanged()
        }
    }

    /**
     * If the task specified by [taskId] has a non-null [ScheduledTask.repeatIntervalMinutes],
     * creates a new PENDING copy with an updated [ScheduledTask.scheduledTimeMillis] and
     * removes the completed/failed original to prevent unbounded list growth.
     */
    fun rescheduleRepeating(taskId: String) {
        val task = tasks.find { it.id == taskId } ?: return
        val nextSchedule = computeNextScheduledTime(task) ?: return

        val newTask = task.copy(
            id = UUID.randomUUID().toString(),
            scheduledTimeMillis = nextSchedule,
            status = TaskStatus.PENDING,
            lastResult = null
        )
        tasks.add(newTask)
        // Remove the old completed/failed instance to prevent unbounded accumulation.
        tasks.removeAll { it.id == taskId }
        notifyChanged()
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

    /**
     * Schedules a task directly (bypassing the agent). Called from the Scheduler Dashboard FAB.
     */
    fun scheduleTaskDirectly(
        name: String,
        prompt: String,
        delayMinutes: Int,
        repeatIntervalMinutes: Int?
    ) {
        val id = UUID.randomUUID().toString()
        val scheduledTime = System.currentTimeMillis() + (delayMinutes * 60_000L)
        tasks.add(
            ScheduledTask(
                id = id,
                name = name,
                prompt = prompt,
                scheduledTimeMillis = scheduledTime,
                repeatIntervalMinutes = repeatIntervalMinutes,
                status = TaskStatus.PENDING
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
            return ToolExecutionResult(
                output = "Unknown tool: $name",
                isError = true
            )
        }
        val action = arguments["action"]
            ?: return ToolExecutionResult(output = "'action' is required.", isError = true)

        val delayMinutes = (arguments["delayMinutes"]?.toIntOrNull() ?: 0).coerceAtLeast(0)
        val repeatInterval = arguments["repeatIntervalMinutes"]?.toIntOrNull()
        if (repeatInterval != null && repeatInterval <= 0) {
            return ToolExecutionResult(
                output = "'repeatIntervalMinutes' must be greater than 0.",
                isError = true
            )
        }
        val recurrenceType = parseRecurrence(arguments["recurrence"])
        val dayOfWeek = arguments["dayOfWeek"]?.toIntOrNull()
        val dayOfMonth = arguments["dayOfMonth"]?.toIntOrNull()

        return execute(
            action = action,
            name = arguments["name"],
            prompt = arguments["prompt"],
            delayMinutes = delayMinutes,
            repeatIntervalMinutes = repeatInterval,
            recurrenceType = recurrenceType,
            timeOfDay = arguments["timeOfDay"],
            dayOfWeek = dayOfWeek,
            dayOfMonth = dayOfMonth,
            taskId = arguments["taskId"]
        )
    }

    private fun recurrenceSummary(task: ScheduledTask): String {
        return when (task.recurrenceType) {
            RecurrenceType.ONCE -> "one-time"
            RecurrenceType.INTERVAL_MINUTES ->
                "every ${task.repeatIntervalMinutes?.toString() ?: "unknown interval"} minute(s)"
            RecurrenceType.DAILY -> "daily at ${task.timeOfDay ?: "--:--"}"
            RecurrenceType.WEEKLY -> {
                "weekly (${dayName(task.dayOfWeek)}) at ${task.timeOfDay ?: "--:--"}"
            }
            RecurrenceType.MONTHLY -> "monthly (day ${task.dayOfMonth ?: 1}) at ${task.timeOfDay ?: "--:--"}"
        }
    }

    private fun parseRecurrence(raw: String?): RecurrenceType {
        return when (raw?.trim()?.lowercase(Locale.ROOT)) {
            "interval", "interval_minutes", "every_minutes" -> RecurrenceType.INTERVAL_MINUTES
            "daily" -> RecurrenceType.DAILY
            "weekly" -> RecurrenceType.WEEKLY
            "monthly" -> RecurrenceType.MONTHLY
            else -> RecurrenceType.ONCE
        }
    }

    private fun dayName(dayOfWeek: Int?): String {
        return when (dayOfWeek) {
            1 -> "Mon"
            2 -> "Tue"
            3 -> "Wed"
            4 -> "Thu"
            5 -> "Fri"
            6 -> "Sat"
            7 -> "Sun"
            else -> "unknown day"
        }
    }

    private fun parseTimeOfDay(timeOfDay: String?): Pair<Int, Int>? {
        if (timeOfDay.isNullOrBlank()) return null
        val parts = timeOfDay.split(":")
        if (parts.size != 2) return null
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour to minute
    }

    private fun computeInitialScheduledTime(
        delayMinutes: Int,
        recurrenceType: RecurrenceType,
        timeOfDay: String?,
        dayOfWeek: Int?,
        dayOfMonth: Int?
    ): Long {
        if (delayMinutes > 0) return System.currentTimeMillis() + (delayMinutes * 60_000L)
        val now = Calendar.getInstance()
        return when (recurrenceType) {
            RecurrenceType.ONCE, RecurrenceType.INTERVAL_MINUTES -> now.timeInMillis
            RecurrenceType.DAILY -> nextDaily(now, timeOfDay).timeInMillis
            RecurrenceType.WEEKLY -> nextWeekly(now, timeOfDay, dayOfWeek).timeInMillis
            RecurrenceType.MONTHLY -> nextMonthly(now, timeOfDay, dayOfMonth).timeInMillis
        }
    }

    private fun computeNextScheduledTime(task: ScheduledTask): Long? {
        val now = Calendar.getInstance()
        return when (task.recurrenceType) {
            RecurrenceType.ONCE -> null
            RecurrenceType.INTERVAL_MINUTES -> {
                val interval = task.repeatIntervalMinutes ?: return null
                System.currentTimeMillis() + interval * 60_000L
            }
            RecurrenceType.DAILY -> nextDaily(now, task.timeOfDay).timeInMillis
            RecurrenceType.WEEKLY -> nextWeekly(now, task.timeOfDay, task.dayOfWeek).timeInMillis
            RecurrenceType.MONTHLY -> nextMonthly(now, task.timeOfDay, task.dayOfMonth).timeInMillis
        }
    }

    private fun nextDaily(now: Calendar, timeOfDay: String?): Calendar {
        val (hour, minute) = parseTimeOfDay(timeOfDay) ?: (now.get(Calendar.HOUR_OF_DAY) to now.get(Calendar.MINUTE))
        return (now.clone() as Calendar).apply {
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            if (!after(now)) add(Calendar.DAY_OF_YEAR, 1)
        }
    }

    private fun nextWeekly(now: Calendar, timeOfDay: String?, dayOfWeek: Int?): Calendar {
        val targetDay = toCalendarDayOfWeek(dayOfWeek) ?: now.get(Calendar.DAY_OF_WEEK)
        val (hour, minute) = parseTimeOfDay(timeOfDay) ?: (now.get(Calendar.HOUR_OF_DAY) to now.get(Calendar.MINUTE))
        return (now.clone() as Calendar).apply {
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            var safetyCounter = 0
            while ((get(Calendar.DAY_OF_WEEK) != targetDay || !after(now)) && safetyCounter < 8) {
                add(Calendar.DAY_OF_YEAR, 1)
                safetyCounter++
            }
        }
    }

    private fun nextMonthly(now: Calendar, timeOfDay: String?, dayOfMonth: Int?): Calendar {
        val targetDay = (dayOfMonth ?: 1).coerceIn(1, 31)
        val (hour, minute) = parseTimeOfDay(timeOfDay) ?: (now.get(Calendar.HOUR_OF_DAY) to now.get(Calendar.MINUTE))
        return (now.clone() as Calendar).apply {
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            fun applyCappedDay() {
                set(
                    Calendar.DAY_OF_MONTH,
                    targetDay.coerceAtMost(getActualMaximum(Calendar.DAY_OF_MONTH))
                )
            }
            applyCappedDay()
            if (!after(now)) {
                add(Calendar.MONTH, 1)
                applyCappedDay()
            }
        }
    }

    private fun toCalendarDayOfWeek(userDay: Int?): Int? {
        return when (userDay) {
            1 -> Calendar.MONDAY
            2 -> Calendar.TUESDAY
            3 -> Calendar.WEDNESDAY
            4 -> Calendar.THURSDAY
            5 -> Calendar.FRIDAY
            6 -> Calendar.SATURDAY
            7 -> Calendar.SUNDAY
            else -> null
        }
    }
}
