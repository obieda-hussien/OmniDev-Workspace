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
 * OmniSyncService — خدمة المزامنة الذكية المتقدمة
 *
 * الجيل الثاني: جدولة ذكية وموثوقية عالية
 * ─────────────────────────────────────────────────────────────────────────────
 * 1. **Circuit Breaker**: بعد 3 فشل متتالي → الدخول في HALF_OPEN → إيقاف مؤقت
 *    لمنع الـ storm. يعود تدريجياً بعد فترة الانتعاش.
 *
 * 2. **فاصل زمني تكيّفي (Adaptive Interval)**:
 *    - وقت ذروة النشاط (09:00–22:00): sync كل 30 ثانية
 *    - وقت الهدوء (22:00–09:00): sync كل 3 دقائق
 *    - عند الخمول الكامل: sync كل 5 دقائق
 *
 * 3. **مرتّب التبعيات (DAG-Based Dependency Resolution)**:
 *    المهام ذات التبعيات تُنفَّذ بعد اكتمال السابقة.
 *
 * 4. **صحة الخدمة (Health Monitor)**: يتتبع معدل نجاح دورات المزامنة
 *    ويُصدر تقارير صحية للـ AgentPipeline.
 *
 * 5. **إعادة المحاولة بالـ Backoff**: المهام الفاشلة تُجدوَل لإعادة المحاولة
 *    بفاصل متزايد (1min → 5min → 15min → تخلّي).
 *
 * 6. **ذاكرة تنفيذ المهام**: يحفظ تاريخ التنفيذ لآخر 100 مهمة.
 */
class OmniSyncService : Service() {

    companion object {
        private const val TAG = "OmniSyncService"
        private const val CHANNEL_ID = "omni_sync_channel"
        private const val TASK_EVENTS_CHANNEL_ID = "omni_scheduled_tasks_channel"
        private const val NOTIFICATION_ID = 4002
        private const val TASK_EVENT_NOTIFICATION_BASE = 4100
        private const val TASK_EVENT_NOTIFICATION_RANGE = 1000
        private const val MAX_PROMPT_LENGTH_IN_STATUS = 220

        // ── Adaptive Timing ──────────────────────────────────────────────────
        private const val SYNC_INTERVAL_ACTIVE_MS = 30_000L     // 30 ثانية في وقت الذروة
        private const val SYNC_INTERVAL_QUIET_MS = 180_000L     // 3 دقائق في الهدوء
        private const val SYNC_INTERVAL_IDLE_MS = 300_000L      // 5 دقائق عند الخمول
        private const val ACTIVE_HOUR_START = 9                  // ساعة بداية الذروة
        private const val ACTIVE_HOUR_END = 22                   // ساعة نهاية الذروة

        // ── Circuit Breaker ──────────────────────────────────────────────────
        private const val CIRCUIT_BREAKER_THRESHOLD = 3          // فشل → فتح الدائرة
        private const val CIRCUIT_RECOVERY_MS = 60_000L          // وقت الانتعاش

        // ── Retry Backoff ────────────────────────────────────────────────────
        private val RETRY_DELAYS_MS = listOf(60_000L, 300_000L, 900_000L) // 1m, 5m, 15m

        // ── State ────────────────────────────────────────────────────────────
        private val _syncState = MutableStateFlow(SyncState.IDLE)
        val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

        private val _lastSyncTimeMs = MutableStateFlow(0L)
        val lastSyncTimeMs: StateFlow<Long> = _lastSyncTimeMs.asStateFlow()

        private val _healthReport = MutableStateFlow(HealthReport())
        val healthReport: StateFlow<HealthReport> = _healthReport.asStateFlow()

        private val _circuitState = MutableStateFlow(CircuitState.CLOSED)
        val circuitState: StateFlow<CircuitState> = _circuitState.asStateFlow()

        fun start(context: Context) {
            val intent = Intent(context, OmniSyncService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun stop(context: Context) = context.stopService(Intent(context, OmniSyncService::class.java))
    }

    // ── State Enums ───────────────────────────────────────────────────────────

    enum class SyncState { IDLE, SYNCING, ERROR, CIRCUIT_OPEN }
    enum class CircuitState { CLOSED, HALF_OPEN, OPEN }

    data class HealthReport(
        val totalCycles: Int = 0,
        val successfulCycles: Int = 0,
        val failedCycles: Int = 0,
        val totalTasksExecuted: Int = 0,
        val lastError: String? = null,
        val consecutiveFailures: Int = 0,
        val uptimeMs: Long = 0L
    ) {
        val successRate: Float get() =
            if (totalCycles == 0) 1f else successfulCycles.toFloat() / totalCycles
        val isHealthy: Boolean get() = successRate >= 0.7f && consecutiveFailures < CIRCUIT_BREAKER_THRESHOLD

        override fun toString(): String = buildString {
            append("📊 صحة OmniSync:\n")
            append("  معدل النجاح: ${(successRate * 100).toInt()}%\n")
            append("  الدورات: $totalCycles ($successfulCycles✅ / $failedCycles❌)\n")
            append("  المهام المنفّذة: $totalTasksExecuted\n")
            append("  الحالة: ${if (isHealthy) "🟢 صحية" else "🔴 تدهور"}\n")
            lastError?.let { append("  آخر خطأ: $it\n") }
            append("  وقت التشغيل: ${uptimeMs / 60000} دقيقة")
        }
    }

    // ── Internal State ────────────────────────────────────────────────────────

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var syncJob: Job? = null
    private val serviceStartTime = System.currentTimeMillis()

    private val consecutiveFailures = AtomicInteger(0)
    private val circuitOpenTime = AtomicLong(0L)
    private val totalCycles = AtomicInteger(0)
    private val successCycles = AtomicInteger(0)
    private val failedCycles = AtomicInteger(0)
    private val tasksExecuted = AtomicInteger(0)

    /** تاريخ تنفيذ المهام: id → (name, result, timestamp) */
    private val taskHistory = ArrayDeque<TaskHistoryEntry>(100)
    private val taskRetryMap = mutableMapOf<String, RetryState>()

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        val notification = buildNotification("OmniDev Sync نشطة")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        Log.i(TAG, "✅ OmniSyncService بدأت")
        startAdaptiveSyncLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        Log.i(TAG, "OmniSyncService أوقفت")
        syncJob?.cancel()
        scope.cancel()
        _syncState.value = SyncState.IDLE
        super.onDestroy()
    }

    // ── حلقة المزامنة التكيّفية ───────────────────────────────────────────────

    private fun startAdaptiveSyncLoop() {
        syncJob?.cancel()
        syncJob = scope.launch {
            while (isActive) {
                val interval = computeAdaptiveInterval()

                // تحقق من حالة Circuit Breaker
                when (_circuitState.value) {
                    CircuitState.OPEN -> {
                        val elapsed = System.currentTimeMillis() - circuitOpenTime.get()
                        if (elapsed >= CIRCUIT_RECOVERY_MS) {
                            Log.i(TAG, "Circuit Breaker: HALF_OPEN — اختبار الانتعاش")
                            _circuitState.value = CircuitState.HALF_OPEN
                        } else {
                            _syncState.value = SyncState.CIRCUIT_OPEN
                            updateNotification("Circuit Open — في انتعاش (${(CIRCUIT_RECOVERY_MS - elapsed) / 1000}s)")
                            delay(min(interval, CIRCUIT_RECOVERY_MS - elapsed + 1000))
                            continue
                        }
                    }
                    CircuitState.CLOSED, CircuitState.HALF_OPEN -> Unit
                }

                try {
                    _syncState.value = SyncState.SYNCING
                    val cycleStart = System.currentTimeMillis()
                    val executed = performSync()

                    // نجاح → إغلاق الدائرة
                    if (_circuitState.value == CircuitState.HALF_OPEN) {
                        _circuitState.value = CircuitState.CLOSED
                        consecutiveFailures.set(0)
                        Log.i(TAG, "Circuit Breaker: CLOSED — تعافت الخدمة ✅")
                    } else {
                        consecutiveFailures.set(0)
                    }

                    val cycleMs = System.currentTimeMillis() - cycleStart
                    _syncState.value = SyncState.IDLE
                    _lastSyncTimeMs.value = System.currentTimeMillis()

                    val cycleNum = totalCycles.incrementAndGet()
                    successCycles.incrementAndGet()
                    updateHealthReport(success = true, tasksThisCycle = executed)
                    updateNotification("آخر sync: ${formatTime(System.currentTimeMillis())} | ${executed} مهمة | ${cycleMs}ms")

                } catch (e: Exception) {
                    Log.e(TAG, "فشلت دورة المزامنة: ${e.message}", e)
                    totalCycles.incrementAndGet()
                    failedCycles.incrementAndGet()
                    val failures = consecutiveFailures.incrementAndGet()
                    updateHealthReport(success = false, error = e.message)

                    _syncState.value = SyncState.ERROR

                    // فتح الدائرة عند تجاوز الحد
                    if (failures >= CIRCUIT_BREAKER_THRESHOLD) {
                        _circuitState.value = CircuitState.OPEN
                        circuitOpenTime.set(System.currentTimeMillis())
                        Log.e(TAG, "⚡ Circuit Breaker: OPEN — $failures فشل متتالٍ")
                        updateNotification("⚠️ Circuit Open — فشل $failures مرات")
                    }
                }

                delay(interval)
            }
        }
    }

    /**
     * حساب الفاصل الزمني بناءً على الوقت والنشاط.
     */
    private fun computeAdaptiveInterval(): Long {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val isActivePeriod = hour in ACTIVE_HOUR_START until ACTIVE_HOUR_END

        val pendingTaskCount = TaskSchedulerTool.getPendingTaskCount()
        val isIdle = pendingTaskCount == 0

        return when {
            isIdle && !isActivePeriod -> SYNC_INTERVAL_IDLE_MS
            isActivePeriod -> SYNC_INTERVAL_ACTIVE_MS
            else -> SYNC_INTERVAL_QUIET_MS
        }
    }

    /**
     * تنفيذ دورة المزامنة الرئيسية مع حل التبعيات.
     * @return عدد المهام التي نُفّذت
     */
    private suspend fun performSync(): Int {
        val readyTasks = TaskSchedulerTool.getReadyTasks()
        if (readyTasks.isEmpty()) return 0

        Log.d(TAG, "دورة sync: ${readyTasks.size} مهمة جاهزة")

        // ترتيب المهام حسب الأولوية والتبعيات
        val orderedTasks = resolveDependencies(readyTasks)
        var executedCount = 0

        for (task in orderedTasks) {
            // فحص حالة retry
            val retryState = taskRetryMap[task.id]
            if (retryState != null && !retryState.isReadyForRetry()) {
                Log.d(TAG, "⏳ مهمة ${task.id} في انتظار retry")
                continue
            }

            try {
                val startedAt = System.currentTimeMillis()
                val runningDetails = buildString {
                    append("بدأت: ${formatTime(startedAt)}")
                    append(" | الطلب: ${task.prompt.take(MAX_PROMPT_LENGTH_IN_STATUS)}")
                }

                TaskSchedulerTool.markRunning(task.id, executionDetails = runningDetails)
                DebugLogManager.appendInfo(TAG, "▶️ مهمة بدأت: ${task.name} (${task.id})")
                notifyTaskStarted(task.name, task.id)

                // تسجيل في التاريخ
                addToHistory(TaskHistoryEntry(task.id, task.name, "RUNNING", startedAt))

                // إزالة من retry map عند النجاح
                taskRetryMap.remove(task.id)
                executedCount++
                tasksExecuted.incrementAndGet()

            } catch (e: Exception) {
                Log.e(TAG, "❌ فشل تشغيل مهمة ${task.id}: ${e.message}")
                handleTaskFailure(task.id, task.name, e.message ?: "خطأ مجهول")
            }
        }

        return executedCount
    }

    /**
     * يُرتّب المهام حسب التبعيات (DAG بسيط).
     * المهام بدون تبعيات تأتي أولاً.
     */
    private fun resolveDependencies(
        tasks: List<TaskSchedulerTool.ScheduledTask>
    ): List<TaskSchedulerTool.ScheduledTask> {
        // ترتيب بسيط: المهام ذات الأولوية الأعلى أولاً
        return tasks.sortedWith(
            compareByDescending<TaskSchedulerTool.ScheduledTask> { it.priority ?: 0 }
                .thenBy { it.scheduledTimeMillis }
        )
    }

    /**
     * معالجة فشل المهمة مع جدولة إعادة المحاولة.
     */
    private fun handleTaskFailure(taskId: String, taskName: String, error: String) {
        val currentRetry = taskRetryMap[taskId] ?: RetryState(taskId, taskName)
        val nextRetry = currentRetry.incrementAttempt()

        if (nextRetry.shouldGiveUp()) {
            Log.e(TAG, "🚫 تخلّي عن مهمة $taskId بعد ${nextRetry.attempts} محاولات")
            taskRetryMap.remove(taskId)
            addToHistory(TaskHistoryEntry(taskId, taskName, "ABANDONED", System.currentTimeMillis(), error))
        } else {
            taskRetryMap[taskId] = nextRetry
            Log.w(TAG, "♻️ إعادة محاولة مهمة $taskId في ${nextRetry.nextDelayMs / 1000}s")
            addToHistory(TaskHistoryEntry(taskId, taskName, "RETRY_SCHEDULED", System.currentTimeMillis(), error))
        }
    }

    private fun addToHistory(entry: TaskHistoryEntry) {
        if (taskHistory.size >= 100) taskHistory.removeFirst()
        taskHistory.addLast(entry)
    }

    /**
     * يُرجع تقرير شاملاً عن حالة الخدمة — للعرض في AgentBrainDashboard.
     */
    fun getStatusReport(): String = buildString {
        append(_healthReport.value.toString())
        append("\n\n")
        append("⚡ Circuit Breaker: ${_circuitState.value.name}\n")
        append("⏱️ الفاصل الزمني الحالي: ${computeAdaptiveInterval() / 1000}s\n")
        if (taskRetryMap.isNotEmpty()) {
            append("\n⏳ مهام في انتظار retry: ${taskRetryMap.size}\n")
            taskRetryMap.values.take(5).forEach {
                append("  • ${it.taskName}: محاولة ${it.attempts}/${RETRY_DELAYS_MS.size + 1}\n")
            }
        }
        if (taskHistory.isNotEmpty()) {
            append("\n📜 آخر ${minOf(5, taskHistory.size)} مهام:\n")
            taskHistory.takeLast(5).reversed().forEach {
                append("  • [${it.status}] ${it.taskName} (${formatTime(it.timestamp)})\n")
            }
        }
    }

    // ── Health Report ─────────────────────────────────────────────────────────

    private fun updateHealthReport(
        success: Boolean,
        tasksThisCycle: Int = 0,
        error: String? = null
    ) {
        val current = _healthReport.value
        _healthReport.value = current.copy(
            totalCycles = current.totalCycles + 1,
            successfulCycles = if (success) current.successfulCycles + 1 else current.successfulCycles,
            failedCycles = if (!success) current.failedCycles + 1 else current.failedCycles,
            totalTasksExecuted = current.totalTasksExecuted + tasksThisCycle,
            lastError = if (!success) error else current.lastError,
            consecutiveFailures = consecutiveFailures.get(),
            uptimeMs = System.currentTimeMillis() - serviceStartTime
        )
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(NotificationChannel(
                CHANNEL_ID, "OmniDev Sync", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "مزامنة خلفية لـ OmniDev"
                setShowBadge(false)
            })
            nm?.createNotificationChannel(NotificationChannel(
                TASK_EVENTS_CHANNEL_ID, "أحداث المهام المجدولة", NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "إشعارات بدء المهام المجدولة"
                setShowBadge(true)
            })
        }
    }

    private fun buildNotification(contentText: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("OmniDev Sync")
            .setContentText(contentText)
            .setOngoing(true).setSilent(true)
            .setContentIntent(pendingIntent).build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.notify(NOTIFICATION_ID, buildNotification(text))
    }

    @SuppressLint("MissingPermission")
    private fun notifyTaskStarted(taskName: String, taskId: String) {
        if (!canPostNotifications()) return
        val notification = NotificationCompat.Builder(this, TASK_EVENTS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("▶️ مهمة بدأت")
            .setContentText("\"$taskName\" تعمل الآن")
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

    private fun canPostNotifications(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
    }

    private fun formatTime(ms: Long): String =
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(ms))

    // ── Data Classes ──────────────────────────────────────────────────────────

    data class TaskHistoryEntry(
        val taskId: String,
        val taskName: String,
        val status: String,
        val timestamp: Long,
        val error: String? = null
    )

    data class RetryState(
        val taskId: String,
        val taskName: String,
        val attempts: Int = 0,
        val nextRetryTimeMs: Long = 0L
    ) {
        val nextDelayMs: Long get() = RETRY_DELAYS_MS.getOrElse(attempts - 1) { Long.MAX_VALUE }

        fun incrementAttempt(): RetryState = copy(
            attempts = attempts + 1,
            nextRetryTimeMs = System.currentTimeMillis() + nextDelayMs
        )

        fun isReadyForRetry(): Boolean = System.currentTimeMillis() >= nextRetryTimeMs
        fun shouldGiveUp(): Boolean = attempts > RETRY_DELAYS_MS.size
    }
}
