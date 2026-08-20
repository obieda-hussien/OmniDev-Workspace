package com.omnidev.workspace.data.tools

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * AI-callable task manager (like Taskly / Todoist).
 *
 * The agent can create, read, update, complete, and delete tasks on behalf of the user.
 * Tasks are persisted in an in-memory list during the app session and exposed as a
 * reactive [tasksFlow] that UI screens can collect.
 *
 * Exposed tool name: `task_manager`
 * Actions: create | list | update | complete | delete | search | stats
 */
object TaskManagerTool {

    // ── Data model ────────────────────────────────────────────────────────

    enum class Priority { HIGH, MEDIUM, LOW }
    enum class TaskStatus { TODO, IN_PROGRESS, DONE, CANCELLED }

    data class Task(
        val id: Long,
        val title: String,
        val description: String,
        val priority: Priority,
        val status: TaskStatus,
        val tags: List<String>,
        val dueDate: String?,          // ISO date string "YYYY-MM-DD" or null
        val createdAt: Long,
        val updatedAt: Long,
        val completedAt: Long? = null
    )

    private val idCounter = AtomicLong(1)
    private val tasks = CopyOnWriteArrayList<Task>()

    private val _tasksFlow = MutableStateFlow<List<Task>>(emptyList())
    val tasksFlow: StateFlow<List<Task>> = _tasksFlow.asStateFlow()

    private fun notifyChanged() { _tasksFlow.value = tasks.toList() }

    private fun now() = System.currentTimeMillis()
    private fun fmtTime(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ms))

    // ── Tool definitions ─────────────────────────────────────────────────

    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "task_manager",
            description = "Manage the user's task list (like Taskly / Todoist). " +
                "Create, list, update, complete, delete, and search tasks. " +
                "Use this to help users stay organised and track work to be done.",
            parameters = listOf(
                ToolParameter(
                    name = "action",
                    type = "string",
                    description = "Action: 'create' | 'list' | 'update' | 'complete' | " +
                        "'delete' | 'search' | 'stats'",
                    required = true
                ),
                ToolParameter(
                    name = "id",
                    type = "string",
                    description = "Task ID (required for update, complete, delete)",
                    required = false
                ),
                ToolParameter(
                    name = "title",
                    type = "string",
                    description = "Task title (required for create; optional for update)",
                    required = false
                ),
                ToolParameter(
                    name = "description",
                    type = "string",
                    description = "Task description / notes",
                    required = false
                ),
                ToolParameter(
                    name = "priority",
                    type = "string",
                    description = "Priority: 'high' | 'medium' | 'low'. Default: medium",
                    required = false
                ),
                ToolParameter(
                    name = "status",
                    type = "string",
                    description = "Status for update: 'todo' | 'in_progress' | 'done' | 'cancelled'",
                    required = false
                ),
                ToolParameter(
                    name = "tags",
                    type = "string",
                    description = "Comma-separated tags, e.g. 'work,urgent,project-x'",
                    required = false
                ),
                ToolParameter(
                    name = "due_date",
                    type = "string",
                    description = "Due date in YYYY-MM-DD format",
                    required = false
                ),
                ToolParameter(
                    name = "query",
                    type = "string",
                    description = "Search query (for 'search' action)",
                    required = false
                ),
                ToolParameter(
                    name = "filter_status",
                    type = "string",
                    description = "Filter by status for 'list': 'todo' | 'in_progress' | 'done' | 'all'. Default: all",
                    required = false
                ),
                ToolParameter(
                    name = "filter_priority",
                    type = "string",
                    description = "Filter by priority for 'list': 'high' | 'medium' | 'low' | 'all'. Default: all",
                    required = false
                )
            )
        )
    )

    // ── Execution ─────────────────────────────────────────────────────────

    suspend fun executeTool(name: String, args: Map<String, String>): ToolExecutionResult {
        val action = args["action"]?.lowercase()
            ?: return ToolExecutionResult("Missing required argument: action", isError = true)

        return when (action) {
            "create" -> createTask(args)
            "list"   -> listTasks(args)
            "update" -> updateTask(args)
            "complete" -> completeTask(args)
            "delete" -> deleteTask(args)
            "search" -> searchTasks(args)
            "stats"  -> statsAction()
            else -> ToolExecutionResult("Unknown task_manager action '$action'. " +
                "Valid: create | list | update | complete | delete | search | stats", isError = true)
        }
    }

    // ── Actions ───────────────────────────────────────────────────────────

    private fun parseTags(raw: String?): List<String> =
        raw?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()

    private fun createTask(args: Map<String, String>): ToolExecutionResult {
        val title = args["title"]?.takeIf { it.isNotBlank() }
            ?: return ToolExecutionResult("Missing required argument: title", isError = true)
        val priority = parsePriority(args["priority"])
        val tags = parseTags(args["tags"])
        val task = Task(
            id = idCounter.getAndIncrement(),
            title = title,
            description = args["description"] ?: "",
            priority = priority,
            status = TaskStatus.TODO,
            tags = tags,
            dueDate = args["due_date"]?.takeIf { it.isNotBlank() },
            createdAt = now(),
            updatedAt = now()
        )
        tasks.add(task)
        notifyChanged()
        return ToolExecutionResult(
            "✅ Task #${task.id} created: \"${task.title}\" " +
                "[${task.priority.name.lowercase()} priority]" +
                if (task.dueDate != null) " — due ${task.dueDate}" else ""
        )
    }

    private fun listTasks(args: Map<String, String>): ToolExecutionResult {
        val statusFilter = args["filter_status"]?.lowercase() ?: "all"
        val priorityFilter = args["filter_priority"]?.lowercase() ?: "all"

        val filtered = tasks.filter { t ->
            val statusOk = statusFilter == "all" || t.status.name.lowercase() == statusFilter
            val prioOk = priorityFilter == "all" || t.priority.name.lowercase() == priorityFilter
            statusOk && prioOk
        }.sortedWith(compareBy(
            { when (it.priority) { Priority.HIGH -> 0; Priority.MEDIUM -> 1; Priority.LOW -> 2 } },
            { it.createdAt }
        ))

        if (filtered.isEmpty()) return ToolExecutionResult("No tasks found.")
        val sb = StringBuilder("Tasks (${filtered.size}):\n")
        for (t in filtered) {
            val statusIcon = when (t.status) {
                TaskStatus.TODO       -> "☐"
                TaskStatus.IN_PROGRESS -> "⟳"
                TaskStatus.DONE       -> "✅"
                TaskStatus.CANCELLED  -> "✗"
            }
            val prioIcon = when (t.priority) {
                Priority.HIGH   -> "🔴"
                Priority.MEDIUM -> "🟡"
                Priority.LOW    -> "🟢"
            }
            sb.appendLine("  $statusIcon $prioIcon #${t.id} — ${t.title}")
            if (t.description.isNotBlank()) sb.appendLine("       📝 ${t.description}")
            if (t.dueDate != null) sb.appendLine("       📅 Due: ${t.dueDate}")
            if (t.tags.isNotEmpty()) sb.appendLine("       🏷 ${t.tags.joinToString(", ")}")
        }
        return ToolExecutionResult(sb.toString().trimEnd())
    }

    private fun updateTask(args: Map<String, String>): ToolExecutionResult {
        val id = args["id"]?.toLongOrNull()
            ?: return ToolExecutionResult("Missing or invalid argument: id", isError = true)
        val idx = tasks.indexOfFirst { it.id == id }
        if (idx < 0) return ToolExecutionResult("Task #$id not found.", isError = true)
        val existing = tasks[idx]
        val updated = existing.copy(
            title = args["title"]?.takeIf { it.isNotBlank() } ?: existing.title,
            description = args["description"] ?: existing.description,
            priority = args["priority"]?.let { parsePriority(it) } ?: existing.priority,
            status = args["status"]?.let { parseStatus(it) } ?: existing.status,
            tags = args["tags"]?.let { parseTags(it) } ?: existing.tags,
            dueDate = if (args.containsKey("due_date")) args["due_date"]?.takeIf { it.isNotBlank() }
                else existing.dueDate,
            updatedAt = now()
        )
        tasks[idx] = updated
        notifyChanged()
        return ToolExecutionResult("✅ Task #$id updated.")
    }

    private fun completeTask(args: Map<String, String>): ToolExecutionResult {
        val id = args["id"]?.toLongOrNull()
            ?: return ToolExecutionResult("Missing or invalid argument: id", isError = true)
        val idx = tasks.indexOfFirst { it.id == id }
        if (idx < 0) return ToolExecutionResult("Task #$id not found.", isError = true)
        tasks[idx] = tasks[idx].copy(status = TaskStatus.DONE, completedAt = now(), updatedAt = now())
        notifyChanged()
        return ToolExecutionResult("✅ Task #$id marked as done.")
    }

    private fun deleteTask(args: Map<String, String>): ToolExecutionResult {
        val id = args["id"]?.toLongOrNull()
            ?: return ToolExecutionResult("Missing or invalid argument: id", isError = true)
        val removed = tasks.removeIf { it.id == id }
        return if (removed) {
            notifyChanged()
            ToolExecutionResult("🗑️ Task #$id deleted.")
        } else {
            ToolExecutionResult("Task #$id not found.", isError = true)
        }
    }

    private fun searchTasks(args: Map<String, String>): ToolExecutionResult {
        val query = args["query"]?.lowercase()?.takeIf { it.isNotBlank() }
            ?: return ToolExecutionResult("Missing argument: query", isError = true)
        val results = tasks.filter { t ->
            t.title.lowercase().contains(query) ||
                t.description.lowercase().contains(query) ||
                t.tags.any { tag -> tag.lowercase().contains(query) }
        }
        if (results.isEmpty()) return ToolExecutionResult("No tasks matching \"$query\".")
        val sb = StringBuilder("Search results for \"$query\" (${results.size}):\n")
        for (t in results) {
            sb.appendLine("  #${t.id} — ${t.title} [${t.status.name.lowercase()}, ${t.priority.name.lowercase()}]")
        }
        return ToolExecutionResult(sb.toString().trimEnd())
    }

    private fun statsAction(): ToolExecutionResult {
        val total = tasks.size
        val todo = tasks.count { it.status == TaskStatus.TODO }
        val inProgress = tasks.count { it.status == TaskStatus.IN_PROGRESS }
        val done = tasks.count { it.status == TaskStatus.DONE }
        val high = tasks.count { it.priority == Priority.HIGH && it.status != TaskStatus.DONE }
        return ToolExecutionResult(
            "📊 Task Stats:\n" +
                "  Total: $total  |  ☐ Todo: $todo  |  ⟳ In Progress: $inProgress  |  ✅ Done: $done\n" +
                "  🔴 High-priority open: $high"
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun parsePriority(raw: String?): Priority = when (raw?.lowercase()) {
        // Also accepts Arabic: System awareness note = high, System awareness note = low
        "high", "System awareness note", "urgent" -> Priority.HIGH
        "low", "System awareness note"           -> Priority.LOW
        else                    -> Priority.MEDIUM
    }

    private fun parseStatus(raw: String?): TaskStatus = when (raw?.lowercase()) {
        "in_progress", "inprogress", "progress" -> TaskStatus.IN_PROGRESS
        "done", "complete", "completed"          -> TaskStatus.DONE
        "cancelled", "canceled"                  -> TaskStatus.CANCELLED
        else                                     -> TaskStatus.TODO
    }
}
