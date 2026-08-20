package com.omnidev.workspace.data.sync

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.omnidev.workspace.MainActivity
import com.omnidev.workspace.R
import com.omnidev.workspace.data.debug.DebugLogManager
import com.omnidev.workspace.data.tools.TaskSchedulerTool
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

/**
 * OmniSyncService — v2.0 "Task Execution Engine"
 *
 * KEY FIX: `performSync()` now ACTUALLY EXECUTES tasks via TaskSchedulerTool.executionCallback.
 * Previously tasks were only "marked running" but the prompt was never sent to AgentPipeline.
 *
 * Execution Flow:
 *  1. getReadyTasks() → finds due PENDING tasks
 *  2. For each task: markRunning() + call executionCallback(task)
 *  3. On success: markCompleted(taskId, result, summary)
 *  4. On failure: markFailed(taskId, error, summary)
 *  5. Notify user with rich summary notification
 *
 * Notification improvements:
 *  - Task STARTED notification (foreground)
 *  - Task COMPLETED notification with: start time, end time, duration, tools used, result
 *  - Task FAILED notification with error details
 *  - Progress notification showing currently running task
 */
class OmniSyncService : Service() {

    companion object {
        private const val TAG = "OmniSyncService"
        private const val CHANNEL_ID = "omni_sync_channel"
        private const val TASK_EVENTS_CHANNEL_ID = "omni_scheduled_tasks_channel"
        private const val TASK_COMPLETE_CHANNEL_ID = "omni_task_complete_channel"
        private const val NOTIFICATION_ID = 4002
        private const val TASK_EVENT_NOTIFICATION_BASE = 4100
        private const val TASK_COMPLETE_NOTIFICATION_BASE = 5000
        private const val TASK_EVENT_NOTIFICATION_RANGE = 500
        private const val MAX_PROMPT_LENGTH_IN_STATUS = 200

        private const val SYNC_INTERVAL_ACTIVE_MS  = 15_000L  // Every 15s when active tasks exist
        private const val SYNC_INTERVAL_NORMAL_MS  = 30_000L  // Every 30s normally
        private const val SYNC_INTERVAL_QUIET_MS   = 120_000L // Every 2min at night
        private const val SYNC_INTERVAL_IDLE_MS    = 300_000L // Every 5min when idle
        private const val ACTIVE_HOUR_START = 8
        private const val ACTIVE_HOUR_END   = 23

        private const val CIRCUIT_BREAKER_THRESHOLD = 3
        private const val CIRCUIT_RECOVERY_MS = 60_000L
        private val RETRY_DELAYS_MS = listOf(60_000L, 300_000L, 900_000L)

        private val _syncState = MutableStateFlow(SyncState.IDLE)
        val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

        private val _lastSyncTimeMs = MutableStateFlow(0L)
        val lastSyncTimeMs: StateFlow<Long> = _lastSyncTimeMs.asStateFlow()

        private val _healthReport = MutableStateFlow(HealthReport())
        val healthReport: StateFlow<HealthReport> = _healthReport.asStateFlow()

        private val _circuitState = MutableStateFlow(CircuitState.CLOSED)
        val circuitState: StateFlow<CircuitState> = _circuitState.asStateFlow()

        private val _currentlyRunningTask = MutableStateFlow<String?>(null)
        val currentlyRunningTask: StateFlow<String?> = _currentlyRunningTask.asStateFlow()

        fun start(context: Context) {
            val intent = Intent(context, OmniSyncService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun stop(context: Context) = context.stopService(Intent(context, OmniSyncService::class.java))

        /**
         * Post a rich completion notification for a task.
         * Called from TaskSchedulerTool.notificationCallback.
         */
        @SuppressLint("MissingPermission")
        fun postCompletionNotification(
            context: Context,
            task: TaskSchedulerTool.ScheduledTask,
            summary: TaskSchedulerTool.ExecutionSummary
        ) {
            if (!canPostNotifications(context)) return
            val nm = NotificationManagerCompat.from(context)
            val intent = PendingIntent.getActivity(
                context, task.id.hashCode(),
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val channelId = if (summary.isSuccess) TASK_COMPLETE_CHANNEL_ID else TASK_EVENTS_CHANNEL_ID
            val notification = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(summary.toNotificationTitle())
                .setContentText(buildSingleLineBody(summary))
                .setStyle(NotificationCompat.BigTextStyle().bigText(summary.toNotificationBody()))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(intent)
                .build()

            val notifId = TASK_COMPLETE_NOTIFICATION_BASE + kotlin.math.abs(task.id.hashCode() % TASK_EVENT_NOTIFICATION_RANGE)
            runCatching { nm.notify(notifId, notification) }
        }

        private fun buildSingleLineBody(summary: TaskSchedulerTool.ExecutionSummary): String {
            val dur = "${summary.durationSec}s"
            val tools = "${summary.toolsUsed} System awareness note"
            return if (summary.isSuccess) "✅ $dur | $tools | ${summary.result.take(80)}"
                   else "❌ ${summary.errorMessage?.take(100) ?: "Error"}"
        }

        private fun canPostNotifications(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
            return ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
        }
    }

    enum class SyncState { IDLE, SYNCING, EXECUTING_TASK, ERROR, CIRCUIT_OPEN }
    enum class CircuitState { CLOSED, HALF_OPEN, OPEN }

    data class HealthReport(
        val totalCycles: Int = 0,
        val successfulCycles: Int = 0,
        val failedCycles: Int = 0,
        val totalTasksExecuted: Int = 0,
        val tasksSucceeded: Int = 0,
        val tasksFailed: Int = 0,
        val lastError: String? = null,
        val consecutiveFailures: Int = 0,
        val uptimeMs: Long = 0L
    ) {
        val successRate: Float get() = if (totalCycles == 0) 1f else successfulCycles.toFloat() / totalCycles
        val taskSuccessRate: Float get() = if (totalTasksExecuted == 0) 1f else tasksSucceeded.toFloat() / totalTasksExecuted
        val isHealthy: Boolean get() = successRate >= 0.7f && consecutiveFailures < CIRCUIT_BREAKER_THRESHOLD
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var syncJob: Job? = null
    private val serviceStartTime = System.currentTimeMillis()

    private val consecutiveFailures = AtomicInteger(0)
    private val circuitOpenTime = AtomicLong(0L)
    private val totalCycles = AtomicInteger(0)
    private val successCycles = AtomicInteger(0)
    private val failedCycles = AtomicInteger(0)
    private val tasksExecuted = AtomicInteger(0)
    private val tasksSucceeded = AtomicInteger(0)
    private val tasksFailed = AtomicInteger(0)

    // ── Lifecycle ─────────────────────────────────────────────────────────

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        startForegroundSafe(buildNotification("OmniDev Sync System awareness note"))
        Log.i(TAG, "✅ OmniSyncService System awareness note")

        // Register notification callback in TaskSchedulerTool
        TaskSchedulerTool.notificationCallback = { task, summary ->
            postCompletionNotification(applicationContext, task, summary)
        }

        startAdaptiveSyncLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        Log.i(TAG, "OmniSyncService System awareness note")
        syncJob?.cancel()
        scope.cancel()
        _syncState.value = SyncState.IDLE
        _currentlyRunningTask.value = null
        TaskSchedulerTool.notificationCallback = null
        super.onDestroy()
    }

    private fun startForegroundSafe(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try { startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) }
            catch (e: Exception) { startForeground(NOTIFICATION_ID, notification) }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    // ── Adaptive Sync Loop ────────────────────────────────────────────────

    private fun startAdaptiveSyncLoop() {
        syncJob?.cancel()
        syncJob = scope.launch {
            while (isActive) {
                val interval = computeAdaptiveInterval()

                when (_circuitState.value) {
                    CircuitState.OPEN -> {
                        val elapsed = System.currentTimeMillis() - circuitOpenTime.get()
                        if (elapsed >= CIRCUIT_RECOVERY_MS) {
                            Log.i(TAG, "Circuit Breaker: HALF_OPEN")
                            _circuitState.value = CircuitState.HALF_OPEN
                        } else {
                            _syncState.value = SyncState.CIRCUIT_OPEN
                            updateNotification("Circuit Open — System awareness note System awareness note ${(CIRCUIT_RECOVERY_MS - elapsed) / 1000}s")
                            delay(min(interval, CIRCUIT_RECOVERY_MS - elapsed + 1000))
                            continue
                        }
                    }
                    else -> Unit
                }

                try {
                    _syncState.value = SyncState.SYNCING
                    val executed = performSync()

                    if (_circuitState.value == CircuitState.HALF_OPEN) {
                        _circuitState.value = CircuitState.CLOSED
                        consecutiveFailures.set(0)
                    } else {
                        consecutiveFailures.set(0)
                    }

                    _syncState.value = SyncState.IDLE
                    _lastSyncTimeMs.value = System.currentTimeMillis()
                    totalCycles.incrementAndGet()
                    successCycles.incrementAndGet()
                    updateHealthReport(success = true, tasksThisCycle = executed)

                    val statusText = if (executed > 0) {
                        "System awareness note sync: ${formatTime(System.currentTimeMillis())} | System awareness note $executed System awareness note"
                    } else {
                        "System awareness note sync: ${formatTime(System.currentTimeMillis())} | System awareness note System awareness note"
                    }
                    updateNotification(statusText)

                } catch (e: Exception) {
                    Log.e(TAG, "System awareness note System awareness note System awareness note: ${e.message}", e)
                    totalCycles.incrementAndGet()
                    failedCycles.incrementAndGet()
                    val failures = consecutiveFailures.incrementAndGet()
                    updateHealthReport(success = false, error = e.message)
                    _syncState.value = SyncState.ERROR

                    if (failures >= CIRCUIT_BREAKER_THRESHOLD) {
                        _circuitState.value = CircuitState.OPEN
                        circuitOpenTime.set(System.currentTimeMillis())
                        updateNotification("⚠️ Circuit Open — $failures System awareness note")
                    }
                }

                delay(interval)
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // performSync — THE FIXED VERSION
    // Tasks are now ACTUALLY EXECUTED via TaskSchedulerTool.executionCallback
    // ══════════════════════════════════════════════════════════════════════

    private suspend fun performSync(): Int {
        val readyTasks = TaskSchedulerTool.getReadyTasks()
        if (readyTasks.isEmpty()) return 0

        Log.d(TAG, "System awareness note sync: ${readyTasks.size} System awareness note System awareness note")
        var executedCount = 0

        for (task in readyTasks) {
            // Check timeout deadline before execution
            val timeoutDeadline = TaskSchedulerTool.getTimeoutDeadlineMillis(task.id)
            if (timeoutDeadline != null && System.currentTimeMillis() >= timeoutDeadline) {
                Log.w(TAG, "⏱ System awareness note System awareness note System awareness note: ${task.name}")
                val summary = TaskSchedulerTool.ExecutionSummary(
                    taskId = task.id, taskName = task.name,
                    startTimeMs = task.startedAtMillis ?: System.currentTimeMillis(),
                    endTimeMs = System.currentTimeMillis(),
                    toolsUsed = 0, toolNames = emptyList(),
                    result = "", isSuccess = false,
                    errorMessage = "System awareness note System awareness note (${task.timeoutMinutes} System awareness note)"
                )
                TaskSchedulerTool.markFailed(task.id, "Timeout after ${task.timeoutMinutes}m", summary)
                continue
            }

            try {
                val startTime = System.currentTimeMillis()
                val displayPrompt = task.prompt.take(MAX_PROMPT_LENGTH_IN_STATUS)

                // Mark as running
                TaskSchedulerTool.markRunning(task.id,
                    executionDetails = "System awareness note: ${formatTime(startTime)} | ${displayPrompt}")
                _syncState.value = SyncState.EXECUTING_TASK
                _currentlyRunningTask.value = task.name

                updateNotification("▶️ System awareness note: ${task.name.take(50)}")
                notifyTaskStarted(task.name, task.id, displayPrompt)
                DebugLogManager.appendInfo(TAG, "▶️ System awareness note System awareness note: ${task.name} (${task.id})")

                // ═══════════════════════════════════════════════════════════
                // ACTUAL EXECUTION via executionCallback
                // ═══════════════════════════════════════════════════════════
                val callback = TaskSchedulerTool.executionCallback
                if (callback != null) {
                    Log.d(TAG, "🤖 System awareness note System awareness note System awareness note AgentPipeline: ${task.name}")
                    try {
                        val summary = withTimeout(
                            ((task.timeoutMinutes ?: 30) * 60 * 1_000L).coerceAtLeast(60_000L)
                        ) {
                            callback(task)
                        }
                        if (summary.isSuccess) {
                            TaskSchedulerTool.markCompleted(task.id, summary.result, summary)
                            tasksSucceeded.incrementAndGet()
                            DebugLogManager.appendInfo(TAG, "✅ System awareness note System awareness note: ${task.name} (${summary.durationSec}s, ${summary.toolsUsed} System awareness note)")
                        } else {
                            TaskSchedulerTool.markFailed(task.id, summary.errorMessage ?: "Unknown error", summary)
                            tasksFailed.incrementAndGet()
                            DebugLogManager.appendError(TAG, RuntimeException("Task failed: ${summary.errorMessage}"))
                        }
                    } catch (e: TimeoutCancellationException) {
                        val timeoutSummary = TaskSchedulerTool.ExecutionSummary(
                            taskId = task.id, taskName = task.name,
                            startTimeMs = startTime, endTimeMs = System.currentTimeMillis(),
                            toolsUsed = 0, toolNames = emptyList(),
                            result = "", isSuccess = false,
                            errorMessage = "Timeout after ${task.timeoutMinutes ?: 30} minutes"
                        )
                        TaskSchedulerTool.markFailed(task.id, "Timeout", timeoutSummary)
                        tasksFailed.incrementAndGet()
                    }
                } else {
                    // Fallback when callback is not set (should not happen in production)
                    Log.w(TAG, "⚠️ executionCallback System awareness note System awareness note — System awareness note System awareness note System awareness note: ${task.name}")
                    val noCallbackSummary = TaskSchedulerTool.ExecutionSummary(
                        taskId = task.id, taskName = task.name,
                        startTimeMs = startTime, endTimeMs = System.currentTimeMillis(),
                        toolsUsed = 0, toolNames = emptyList(),
                        result = "Execution bridge not configured. Set TaskSchedulerTool.executionCallback in MainActivity.",
                        isSuccess = false,
                        errorMessage = "executionCallback not set — AgentPipeline not connected"
                    )
                    TaskSchedulerTool.markFailed(task.id, noCallbackSummary.result, noCallbackSummary)
                    tasksFailed.incrementAndGet()
                }

                executedCount++
                tasksExecuted.incrementAndGet()

                // Reschedule if repeating
                if (task.recurrenceType != TaskSchedulerTool.RecurrenceType.ONCE) {
                    TaskSchedulerTool.rescheduleRepeating(task.id)
                }

            } catch (e: CancellationException) {
                throw e // Always propagate CancellationException
            } catch (e: Exception) {
                Log.e(TAG, "❌ System awareness note System awareness note System awareness note System awareness note ${task.id}: ${e.message}")
                DebugLogManager.appendError(TAG, e)
                val errorSummary = TaskSchedulerTool.ExecutionSummary(
                    taskId = task.id, taskName = task.name,
                    startTimeMs = task.startedAtMillis ?: System.currentTimeMillis(),
                    endTimeMs = System.currentTimeMillis(),
                    toolsUsed = 0, toolNames = emptyList(),
                    result = "", isSuccess = false,
                    errorMessage = e.message ?: "Unknown error"
                )
                TaskSchedulerTool.markFailed(task.id, e.message ?: "Unknown error", errorSummary)
                tasksFailed.incrementAndGet()
            } finally {
                _currentlyRunningTask.value = null
                _syncState.value = SyncState.IDLE
            }
        }

        return executedCount
    }

    private fun computeAdaptiveInterval(): Long {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val isActivePeriod = hour in ACTIVE_HOUR_START until ACTIVE_HOUR_END
        val pendingCount = TaskSchedulerTool.getAllTasks().count { it.status.name == "PENDING" }
        val runningCount = TaskSchedulerTool.getAllTasks().count { it.status.name == "RUNNING" }

        return when {
            runningCount > 0 -> 5_000L           // Something is running — check every 5s
            pendingCount > 0 && isActivePeriod -> SYNC_INTERVAL_ACTIVE_MS
            pendingCount == 0 && !isActivePeriod -> SYNC_INTERVAL_IDLE_MS
            isActivePeriod -> SYNC_INTERVAL_NORMAL_MS
            else -> SYNC_INTERVAL_QUIET_MS
        }
    }

    // ── Health Report ─────────────────────────────────────────────────────

    private fun updateHealthReport(success: Boolean, tasksThisCycle: Int = 0, error: String? = null) {
        val current = _healthReport.value
        _healthReport.value = current.copy(
            totalCycles = current.totalCycles + 1,
            successfulCycles = if (success) current.successfulCycles + 1 else current.successfulCycles,
            failedCycles = if (!success) current.failedCycles + 1 else current.failedCycles,
            totalTasksExecuted = current.totalTasksExecuted + tasksThisCycle,
            tasksSucceeded = current.tasksSucceeded + tasksSucceeded.get(),
            tasksFailed = current.tasksFailed + tasksFailed.get(),
            lastError = if (!success) error else current.lastError,
            consecutiveFailures = consecutiveFailures.get(),
            uptimeMs = System.currentTimeMillis() - serviceStartTime
        )
    }

    fun getStatusReport(): String = buildString {
        val h = _healthReport.value
        append("📊 OmniSync:\n")
        append("  System awareness note: ${h.totalCycles} (${h.successfulCycles}✅ / ${h.failedCycles}❌)\n")
        append("  System awareness note System awareness note: ${h.totalTasksExecuted} (${h.tasksSucceeded}✅ / ${h.tasksFailed}❌)\n")
        append("  System awareness note: ${if(h.isHealthy) "🟢 System awareness note" else "🔴 System awareness note"}\n")
        append("  Circuit: ${_circuitState.value.name}\n")
        val running = _currentlyRunningTask.value
        if (running != null) append("  🤖 System awareness note System awareness note: $running\n")
        val bridgeSet = TaskSchedulerTool.executionCallback != null
        append("  Execution Bridge: ${if(bridgeSet) "✅ System awareness note" else "❌ System awareness note System awareness note"}\n")
        h.lastError?.let { append("  System awareness note System awareness note: $it\n") }
    }.trimEnd()

    // ── Notifications ─────────────────────────────────────────────────────

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(NotificationChannel(
                CHANNEL_ID, "OmniDev Sync", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "System awareness note System awareness note"; setShowBadge(false) })
            nm?.createNotificationChannel(NotificationChannel(
                TASK_EVENTS_CHANNEL_ID, "System awareness note System awareness note System awareness note", NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "System awareness note System awareness note System awareness note"; setShowBadge(true) })
            nm?.createNotificationChannel(NotificationChannel(
                TASK_COMPLETE_CHANNEL_ID, "System awareness note System awareness note System awareness note", NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note"
                setShowBadge(true)
                enableVibration(true)
            })
        }
    }

    private fun buildNotification(contentText: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val allTasks = TaskSchedulerTool.getAllTasks()
        val pending = allTasks.count { it.status.name == "PENDING" }
        val running = allTasks.count { it.status.name == "RUNNING" }
        val subText = if (running > 0) "▶️ $running System awareness note | ⏳ $pending System awareness note"
                      else if (pending > 0) "⏳ $pending System awareness note System awareness note"
                      else "System awareness note System awareness note System awareness note"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("OmniDev Sync")
            .setContentText(contentText)
            .setSubText(subText)
            .setOngoing(true).setSilent(true)
            .setContentIntent(pendingIntent).build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.notify(NOTIFICATION_ID, buildNotification(text))
    }

    @SuppressLint("MissingPermission")
    private fun notifyTaskStarted(taskName: String, taskId: String, promptPreview: String) {
        if (!canPostNotifications(applicationContext)) return
        val notification = NotificationCompat.Builder(this, TASK_EVENTS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("▶️ System awareness note System awareness note")
            .setContentText("\"$taskName\" System awareness note System awareness note")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("\"$taskName\" System awareness note System awareness note\n📋 ${promptPreview}"))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(PendingIntent.getActivity(
                this, taskId.hashCode(), Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )).build()
        runCatching {
            NotificationManagerCompat.from(this).notify(
                TASK_EVENT_NOTIFICATION_BASE + kotlin.math.abs(taskId.hashCode() % TASK_EVENT_NOTIFICATION_RANGE),
                notification
            )
        }
    }

    private fun canPostNotifications(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
    }

    private fun formatTime(ms: Long): String =
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(ms))
}
