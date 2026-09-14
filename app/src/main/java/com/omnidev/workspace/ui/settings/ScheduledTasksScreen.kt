package com.omnidev.workspace.ui.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Pending
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.omnidev.workspace.data.tools.TaskSchedulerTool
import com.omnidev.workspace.data.tools.TaskSchedulerTool.RecurrenceType
import com.omnidev.workspace.data.tools.TaskSchedulerTool.ScheduledTask
import com.omnidev.workspace.data.tools.TaskSchedulerTool.TaskPriority
import com.omnidev.workspace.data.tools.TaskSchedulerTool.TaskStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val DATE_FMT = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())

// ── Filter / Sort models ─────────────────────────────────────────────────────

private enum class TabFilter(val label: String) {
    ALL("All"), ACTIVE("Active"), COMPLETED("Completed"), FAILED("Failed")
}
private enum class SortMode(val label: String) {
    TIME("By Time"), PRIORITY("By Priority")
}

// ── Screen ───────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduledTasksScreen(onNavigateBack: () -> Unit = {}) {
    val actionScope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    fun changeTask(action: () -> Unit) {
        actionScope.launch {
            try { withContext(Dispatchers.IO) { action() } }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) { snackbar.showSnackbar(error.message ?: "Unable to save task") }
        }
    }
    val allTasks by TaskSchedulerTool.tasksFlow.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var activeTab    by remember { mutableStateOf(TabFilter.ALL) }
    var sortMode     by remember { mutableStateOf(SortMode.TIME) }
    var showSortMenu by remember { mutableStateOf(false) }

    val displayedTasks = remember(allTasks, activeTab, sortMode) {
        val filtered = when (activeTab) {
            TabFilter.ALL       -> allTasks
            TabFilter.ACTIVE    -> allTasks.filter {
                it.status in setOf(TaskStatus.PENDING, TaskStatus.RUNNING,
                                   TaskStatus.PAUSED, TaskStatus.WAITING_DEPENDENCY)
            }
            TabFilter.COMPLETED -> allTasks.filter {
                it.status in setOf(TaskStatus.COMPLETED, TaskStatus.CANCELLED)
            }
            TabFilter.FAILED    -> allTasks.filter { it.status == TaskStatus.FAILED }
        }
        when (sortMode) {
            SortMode.TIME     -> filtered.sortedBy { it.scheduledTimeMillis }
            SortMode.PRIORITY -> filtered.sortedWith(
                compareBy({ it.priority.order }, { it.scheduledTimeMillis })
            )
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Scheduled Tasks") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Sort toggle
                    Box {
                        IconButton(onClick = { showSortMenu = true }) {
                            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort")
                        }
                        DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                            SortMode.entries.forEach { mode ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            mode.label,
                                            fontWeight = if (mode == sortMode) FontWeight.Bold else FontWeight.Normal
                                        )
                                    },
                                    onClick = { sortMode = mode; showSortMenu = false }
                                )
                            }
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Schedule Task")
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // ── Filter tabs ───────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TabFilter.entries.forEach { tab ->
                    val count = when (tab) {
                        TabFilter.ALL       -> allTasks.size
                        TabFilter.ACTIVE    -> allTasks.count {
                            it.status in setOf(TaskStatus.PENDING, TaskStatus.RUNNING,
                                               TaskStatus.PAUSED, TaskStatus.WAITING_DEPENDENCY)
                        }
                        TabFilter.COMPLETED -> allTasks.count {
                            it.status in setOf(TaskStatus.COMPLETED, TaskStatus.CANCELLED)
                        }
                        TabFilter.FAILED    -> allTasks.count { it.status == TaskStatus.FAILED }
                    }
                    FilterChip(
                        selected = activeTab == tab,
                        onClick  = { activeTab = tab },
                        label    = { Text("${tab.label} ($count)") }
                    )
                }
            }

            if (displayedTasks.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Schedule, contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            if (allTasks.isEmpty()) "No scheduled tasks"
                            else "No tasks in this filter",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            if (allTasks.isEmpty()) "Tap + to schedule a background AI task" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item { Spacer(Modifier.height(4.dp)) }
                    items(displayedTasks, key = { it.id }) { task ->
                        TaskCard(
                            task     = task,
                            onCancel = { changeTask { TaskSchedulerTool.cancelTaskById(task.id) } },
                            onDelete = { changeTask { TaskSchedulerTool.deleteTask(task.id) } },
                            onPause  = { changeTask { TaskSchedulerTool.pauseTaskById(task.id) } },
                            onResume = { changeTask { TaskSchedulerTool.resumeTaskById(task.id) } },
                            onRetry  = { changeTask { TaskSchedulerTool.retryTaskById(task.id) } },
                            onRunNow = { changeTask { TaskSchedulerTool.runNowById(task.id) } }
                        )
                    }
                    item { Spacer(Modifier.height(80.dp)) }
                }
            }
        }
    }

    if (showAddDialog) {
        AddTaskDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { params ->
                actionScope.launch {
                try { withContext(Dispatchers.IO) {
                TaskSchedulerTool.scheduleTaskDirectly(
                    name                 = params.name,
                    prompt               = params.prompt,
                    delayMinutes         = params.delayMinutes,
                    repeatIntervalMinutes = params.repeatIntervalMinutes,
                    priority             = params.priority,
                    tags                 = params.tags,
                    maxRetries           = params.maxRetries,
                    timeoutMinutes       = params.timeoutMinutes,
                    maxRuns              = params.maxRuns,
                    cronExpression       = params.cronExpression,
                    recurrenceType       = params.recurrenceType,
                    timeOfDay            = params.timeOfDay,
                    dayOfWeek            = params.dayOfWeek,
                    dayOfMonth           = params.dayOfMonth,
                    notifyOnComplete     = params.notifyOnComplete,
                    notifyOnFail         = params.notifyOnFail
                )
                }
                showAddDialog = false
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (error: Exception) { snackbar.showSnackbar(error.message ?: "Unable to save task") }
                }
            }
        )
    }
}

// ── Task Card ────────────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TaskCard(
    task: ScheduledTask,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onPause:  () -> Unit,
    onResume: () -> Unit,
    onRetry:  () -> Unit,
    onRunNow: () -> Unit
) {
    val statusColor by animateColorAsState(
        targetValue = when (task.status) {
            TaskStatus.PENDING             -> MaterialTheme.colorScheme.primary
            TaskStatus.RUNNING             -> Color(0xFF4CAF50)
            TaskStatus.COMPLETED           -> Color(0xFF2196F3)
            TaskStatus.FAILED              -> MaterialTheme.colorScheme.error
            TaskStatus.CANCELLED           -> MaterialTheme.colorScheme.onSurfaceVariant
            TaskStatus.PAUSED              -> Color(0xFFFFA726)
            TaskStatus.WAITING_DEPENDENCY  -> Color(0xFF9C27B0)
        },
        label = "statusColor"
    )
    val statusIcon = when (task.status) {
        TaskStatus.PENDING            -> Icons.Default.Pending
        TaskStatus.RUNNING            -> Icons.Default.PlayArrow
        TaskStatus.COMPLETED          -> Icons.Default.CheckCircle
        TaskStatus.FAILED             -> Icons.Default.Error
        TaskStatus.CANCELLED          -> Icons.Default.Cancel
        TaskStatus.PAUSED             -> Icons.Default.Pause
        TaskStatus.WAITING_DEPENDENCY -> Icons.Default.HourglassTop
    }
    val priorityColor = when (task.priority) {
        TaskPriority.CRITICAL -> Color(0xFFD32F2F)
        TaskPriority.HIGH     -> Color(0xFFF57C00)
        TaskPriority.NORMAL   -> MaterialTheme.colorScheme.primary
        TaskPriority.LOW      -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {

            // ── Header row: icon + name + priority badge + status ──────
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(statusIcon, contentDescription = task.status.name,
                    tint = statusColor, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    text     = task.name,
                    style    = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                // Priority pill
                Surface(
                    color        = priorityColor.copy(alpha = 0.15f),
                    shape        = MaterialTheme.shapes.small
                ) {
                    Text(
                        text     = task.priority.label,
                        style    = MaterialTheme.typography.labelSmall,
                        color    = priorityColor,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                Spacer(Modifier.width(6.dp))
                Text(task.status.name, style = MaterialTheme.typography.labelSmall, color = statusColor)
            }

            Spacer(Modifier.height(6.dp))

            // ── Prompt ────────────────────────────────────────────────
            Text(
                text     = task.prompt,
                style    = MaterialTheme.typography.bodySmall,
                color    = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2
            )

            Spacer(Modifier.height(6.dp))

            // ── Metadata row: schedule time + recurrence ───────────────
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Schedule, contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(4.dp))
                Text(
                    text     = DATE_FMT.format(Date(task.scheduledTimeMillis)),
                    style    = MaterialTheme.typography.labelSmall,
                    color    = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                val recurrenceLabel = when (task.recurrenceType) {
                    RecurrenceType.ONCE             -> null
                    RecurrenceType.INTERVAL_MINUTES -> "↺ ${task.repeatIntervalMinutes}m"
                    RecurrenceType.DAILY            -> "↺ daily ${task.timeOfDay ?: ""}"
                    RecurrenceType.WEEKLY           -> "↺ weekly"
                    RecurrenceType.MONTHLY          -> "↺ monthly"
                    RecurrenceType.CRON             -> "↺ cron"
                }
                if (recurrenceLabel != null) {
                    Text(recurrenceLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary)
                }
            }

            // ── Extra metadata: runs, timeout, deps ───────────────────
            val hasExtras = task.runCount > 0 || task.timeoutMinutes != null ||
                task.maxRetries > 0 || task.dependsOn.isNotEmpty()
            if (hasExtras) {
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (task.runCount > 0 || task.maxRuns != null) {
                        Icon(Icons.Default.Refresh, null, Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            text  = if (task.maxRuns != null) "${task.runCount}/${task.maxRuns}" else "×${task.runCount}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (task.timeoutMinutes != null) {
                        Icon(Icons.Default.Timer, null, Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${task.timeoutMinutes}m timeout",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (task.maxRetries > 0) {
                        Text("retry ${task.retryCount}/${task.maxRetries}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (task.dependsOn.isNotEmpty()) {
                        Icon(Icons.Default.HourglassTop, null, Modifier.size(12.dp),
                            tint = Color(0xFF9C27B0))
                        Text("${task.dependsOn.size} dep(s)",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF9C27B0))
                    }
                }
            }

            // ── Tags ──────────────────────────────────────────────────
            if (task.tags.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(task.tags) { tag ->
                        AssistChip(
                            onClick = {},
                            label   = { Text(tag, style = MaterialTheme.typography.labelSmall) },
                            colors  = AssistChipDefaults.assistChipColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer
                            ),
                            modifier = Modifier.height(24.dp)
                        )
                    }
                }
            }

            // ── Last result ───────────────────────────────────────────
            if (!task.lastResult.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text     = task.lastResult,
                    style    = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color    = if (task.status == TaskStatus.FAILED) MaterialTheme.colorScheme.error
                               else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2
                )
            }

            Spacer(Modifier.height(8.dp))

            // ── Action buttons ────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Run Now — available for PENDING, PAUSED, WAITING_DEPENDENCY
                if (task.status in setOf(TaskStatus.PENDING, TaskStatus.PAUSED,
                                         TaskStatus.WAITING_DEPENDENCY)) {
                    TextButton(onClick = onRunNow) {
                        Icon(Icons.Default.Bolt, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Now")
                    }
                }
                // Pause / Resume
                if (task.status in setOf(TaskStatus.PENDING, TaskStatus.WAITING_DEPENDENCY)) {
                    TextButton(onClick = onPause) {
                        Icon(Icons.Default.Pause, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Pause")
                    }
                }
                if (task.status == TaskStatus.PAUSED) {
                    TextButton(onClick = onResume) {
                        Icon(Icons.Default.PlayArrow, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Resume")
                    }
                }
                // Retry — for FAILED or CANCELLED
                if (task.status in setOf(TaskStatus.FAILED, TaskStatus.CANCELLED)) {
                    TextButton(onClick = onRetry) {
                        Icon(Icons.Default.Refresh, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Retry")
                    }
                }
                // Cancel — for active tasks
                if (task.status in setOf(TaskStatus.PENDING, TaskStatus.RUNNING,
                                         TaskStatus.PAUSED, TaskStatus.WAITING_DEPENDENCY)) {
                    TextButton(onClick = onCancel) {
                        Icon(Icons.Default.Cancel, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Cancel")
                    }
                }
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

// ── Add Task Dialog ──────────────────────────────────────────────────────────

private data class NewTaskParams(
    val name: String,
    val prompt: String,
    val delayMinutes: Int,
    val repeatIntervalMinutes: Int?,
    val priority: TaskPriority,
    val tags: List<String>,
    val maxRetries: Int,
    val timeoutMinutes: Int?,
    val maxRuns: Int?,
    val cronExpression: String?,
    val recurrenceType: RecurrenceType,
    val timeOfDay: String?,
    val dayOfWeek: Int?,
    val dayOfMonth: Int?,
    val notifyOnComplete: Boolean,
    val notifyOnFail: Boolean
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun AddTaskDialog(
    onDismiss: () -> Unit,
    onConfirm: (NewTaskParams) -> Unit
) {
    var name          by remember { mutableStateOf("") }
    var prompt        by remember { mutableStateOf("") }
    var delayMins     by remember { mutableStateOf("0") }
    var repeatMins    by remember { mutableStateOf("") }
    var priorityVal   by remember { mutableStateOf(TaskPriority.NORMAL) }
    var tagsText      by remember { mutableStateOf("") }
    var maxRetriesStr by remember { mutableStateOf("0") }
    var timeoutStr    by remember { mutableStateOf("") }
    var maxRunsStr    by remember { mutableStateOf("") }
    var cronExpr      by remember { mutableStateOf("") }
    var recurrence    by remember { mutableStateOf(RecurrenceType.ONCE) }
    var timeOfDay     by remember { mutableStateOf("") }
    var dayOfWeek     by remember { mutableStateOf("") }
    var dayOfMonth    by remember { mutableStateOf("") }
    var notifyOk      by remember { mutableStateOf(false) }
    var notifyFail    by remember { mutableStateOf(true) }

    var nameError   by remember { mutableStateOf(false) }
    var promptError by remember { mutableStateOf(false) }
    var cronError   by remember { mutableStateOf(false) }

    var showPriorityMenu by remember { mutableStateOf(false) }
    var showRecurMenu    by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Schedule AI Task") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {

                // ── Core fields ───────────────────────────────────────
                item {
                    OutlinedTextField(
                        value = name, onValueChange = { name = it; nameError = false },
                        label = { Text("Task Name *") },
                        singleLine = true, isError = nameError,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    OutlinedTextField(
                        value = prompt, onValueChange = { prompt = it; promptError = false },
                        label = { Text("AI Prompt *") },
                        maxLines = 4, isError = promptError,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    OutlinedTextField(
                        value = delayMins,
                        onValueChange = { delayMins = it.filter(Char::isDigit) },
                        label = { Text("Delay (minutes, 0 = now)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // ── Priority ──────────────────────────────────────────
                item {
                    Box {
                        OutlinedTextField(
                            value = priorityVal.label,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Priority") },
                            trailingIcon = {
                                TextButton(onClick = { showPriorityMenu = true }) {
                                    Text("▾")
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        DropdownMenu(
                            expanded = showPriorityMenu,
                            onDismissRequest = { showPriorityMenu = false }
                        ) {
                            TaskPriority.entries.forEach { p ->
                                DropdownMenuItem(
                                    text = { Text(p.label) },
                                    onClick = { priorityVal = p; showPriorityMenu = false }
                                )
                            }
                        }
                    }
                }

                // ── Tags ──────────────────────────────────────────────
                item {
                    OutlinedTextField(
                        value = tagsText,
                        onValueChange = { tagsText = it },
                        label = { Text("Tags (comma-separated)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // ── Recurrence ────────────────────────────────────────
                item {
                    Box {
                        OutlinedTextField(
                            value = recurrence.name.lowercase().replaceFirstChar { it.uppercase() },
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Recurrence") },
                            trailingIcon = {
                                TextButton(onClick = { showRecurMenu = true }) { Text("▾") }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        DropdownMenu(
                            expanded = showRecurMenu,
                            onDismissRequest = { showRecurMenu = false }
                        ) {
                            RecurrenceType.entries.forEach { r ->
                                DropdownMenuItem(
                                    text = { Text(r.name.lowercase().replaceFirstChar { it.uppercase() }) },
                                    onClick = { recurrence = r; showRecurMenu = false }
                                )
                            }
                        }
                    }
                }

                // Recurrence-specific fields
                if (recurrence == RecurrenceType.INTERVAL_MINUTES) {
                    item {
                        OutlinedTextField(
                            value = repeatMins,
                            onValueChange = { repeatMins = it.filter(Char::isDigit) },
                            label = { Text("Repeat every N minutes") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                if (recurrence == RecurrenceType.CRON) {
                    item {
                        OutlinedTextField(
                            value = cronExpr,
                            onValueChange = { cronExpr = it; cronError = false },
                            label = { Text("CRON expression (min hr dom month dow)") },
                            placeholder = { Text("e.g. 0 9 * * 1-5") },
                            singleLine = true,
                            isError = cronError,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                if (recurrence in setOf(RecurrenceType.DAILY, RecurrenceType.WEEKLY, RecurrenceType.MONTHLY)) {
                    item {
                        OutlinedTextField(
                            value = timeOfDay,
                            onValueChange = { timeOfDay = it },
                            label = { Text("Time of day (HH:mm)") },
                            placeholder = { Text("e.g. 09:00") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                if (recurrence == RecurrenceType.WEEKLY) {
                    item {
                        OutlinedTextField(
                            value = dayOfWeek,
                            onValueChange = { dayOfWeek = it.filter(Char::isDigit) },
                            label = { Text("Day of week (1=Mon … 7=Sun)") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                if (recurrence == RecurrenceType.MONTHLY) {
                    item {
                        OutlinedTextField(
                            value = dayOfMonth,
                            onValueChange = { dayOfMonth = it.filter(Char::isDigit) },
                            label = { Text("Day of month (1–31)") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                if (recurrence != RecurrenceType.ONCE && recurrence != RecurrenceType.CRON) {
                    item {
                        OutlinedTextField(
                            value = maxRunsStr,
                            onValueChange = { maxRunsStr = it.filter(Char::isDigit) },
                            label = { Text("Max runs (blank = unlimited)") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                // ── Quality of Service ────────────────────────────────
                item {
                    OutlinedTextField(
                        value = maxRetriesStr,
                        onValueChange = { maxRetriesStr = it.filter(Char::isDigit) },
                        label = { Text("Max retries on failure (0 = none)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    OutlinedTextField(
                        value = timeoutStr,
                        onValueChange = { timeoutStr = it.filter(Char::isDigit) },
                        label = { Text("Timeout (minutes, blank = unlimited)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // ── Notification toggles ──────────────────────────────
                item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = notifyOk,
                            onClick  = { notifyOk = !notifyOk },
                            label    = { Text("Notify on complete") }
                        )
                        FilterChip(
                            selected = notifyFail,
                            onClick  = { notifyFail = !notifyFail },
                            label    = { Text("Notify on fail") }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                nameError   = name.isBlank()
                promptError = prompt.isBlank()
                cronError   = recurrence == RecurrenceType.CRON &&
                    cronExpr.isNotBlank() && !TaskSchedulerTool.isValidCron(cronExpr)

                if (!nameError && !promptError && !cronError) {
                    onConfirm(
                        NewTaskParams(
                            name                  = name.trim(),
                            prompt                = prompt.trim(),
                            delayMinutes          = delayMins.toIntOrNull()?.coerceAtLeast(0) ?: 0,
                            repeatIntervalMinutes = repeatMins.toIntOrNull()?.takeIf { it > 0 },
                            priority              = priorityVal,
                            tags                  = tagsText.split(",")
                                .map { it.trim() }.filter { it.isNotEmpty() },
                            maxRetries            = maxRetriesStr.toIntOrNull()?.coerceAtLeast(0) ?: 0,
                            timeoutMinutes        = timeoutStr.toIntOrNull()?.takeIf { it > 0 },
                            maxRuns               = maxRunsStr.toIntOrNull()?.takeIf { it > 0 },
                            cronExpression        = cronExpr.trim().takeIf { it.isNotEmpty() },
                            recurrenceType        = recurrence,
                            timeOfDay             = timeOfDay.trim().takeIf { it.isNotEmpty() },
                            dayOfWeek             = dayOfWeek.toIntOrNull(),
                            dayOfMonth            = dayOfMonth.toIntOrNull(),
                            notifyOnComplete      = notifyOk,
                            notifyOnFail          = notifyFail
                        )
                    )
                }
            }) {
                Text("Schedule")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
