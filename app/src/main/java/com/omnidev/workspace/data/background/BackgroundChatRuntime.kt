package com.omnidev.workspace.data.background

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.omnidev.workspace.MainActivity
import com.omnidev.workspace.R
import com.omnidev.workspace.data.sync.OmniSyncService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

/**
 * Process-wide background supervisor for ordinary in-app chat runs.
 *
 * Layering:
 *  1. UI run keeps its existing coroutine but this runtime mirrors it into a durable notification.
 *  2. OmniSyncService is started while work is active so Android gives the process foreground-service
 *     priority even after the Activity is closed.
 *  3. If the process is recreated and the UI owner no longer exists, the persisted run is recovered
 *     through [BackgroundChatRecoveryExecutor].
 *  4. [BackgroundChatWatchdogWorker] is an extra OEM/process-kill fallback.
 */
object BackgroundChatRuntime {
    private const val RUNNING_CHANNEL = "omni_chat_background_running"
    private const val FINISHED_CHANNEL = "omni_chat_background_finished"
    private const val GROUP_KEY = "omni_background_chat_runs"
    private const val MAX_RECOVERY_ATTEMPTS = 8
    private const val LIVE_POLL_MS = 650L
    private const val UI_OWNER_GRACE_MS = 2_500L

    private val runtimeLock = Any()
    private var appContext: Context? = null
    private var scope: CoroutineScope? = null
    private val jobs = ConcurrentHashMap<String, Job>()
    private val lastNotificationFingerprint = ConcurrentHashMap<String, String>()

    fun ensureStarted(context: Context) {
        val app = context.applicationContext
        synchronized(runtimeLock) {
            appContext = app
            if (scope == null) scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            createChannels(app)
            BackgroundChatTaskStore.prune(app)
        }
        BackgroundChatTaskStore.active(app).forEach { launch(it) }
    }

    /** Called from a user-initiated send/approval path. */
    fun kick(token: String) {
        val context = appContext ?: return
        val run = BackgroundChatTaskStore.get(context, token) ?: return
        if (run.isTerminal) return
        runCatching { OmniSyncService.start(context) }
        BackgroundChatWatchdogWorker.schedule(context, token)
        launch(run)
    }

    fun recoverAll(context: Context) {
        ensureStarted(context)
        BackgroundChatTaskStore.active(context.applicationContext).forEach { launch(it) }
    }

    fun cancel(token: String) {
        val context = appContext ?: return
        val run = BackgroundChatTaskStore.get(context, token) ?: return
        if (!BackgroundChatCoordinator.cancelLiveRun(run)) jobs[token]?.cancel(CancellationException("Stopped by user"))
        finish(
            run,
            state = BackgroundChatRun.STATE_CANCELLED,
            title = "Task stopped",
            text = "The background run was stopped. Tap to open the conversation.",
            error = null
        )
    }

    private fun launch(initial: BackgroundChatRun) {
        if (initial.isTerminal || jobs.containsKey(initial.token)) return
        val runtimeScope = scope ?: return
        val job = runtimeScope.launch {
            val context = appContext ?: return@launch
            val wakeLock = acquireWakeLock(context, initial.token)
            try {
                supervise(initial)
            } finally {
                if (wakeLock?.isHeld == true) runCatching { wakeLock.release() }
                jobs.remove(initial.token)
                lastNotificationFingerprint.remove(initial.token)
                BackgroundChatCoordinator.releaseViewModelIfIdle()
                if (BackgroundChatTaskStore.active(context).isEmpty()) {
                    BackgroundChatWatchdogWorker.cancelAll(context)
                }
            }
        }
        val previous = jobs.putIfAbsent(initial.token, job)
        if (previous != null) job.cancel()
    }

    private suspend fun supervise(initial: BackgroundChatRun) {
        val context = appContext ?: return
        var run = BackgroundChatTaskStore.get(context, initial.token) ?: return

        if (run.ownerUi && BackgroundChatCoordinator.hasLiveOwner(run)) {
            run = monitorLiveUi(run) ?: return
        } else if (run.ownerUi) {
            // A fresh process has no ChatViewModel. Give normal Activity recreation a short window;
            // if no owner appears, take durable ownership and recover from Room.
            var waited = 0L
            while (waited < UI_OWNER_GRACE_MS && isScopeActive()) {
                if (BackgroundChatCoordinator.hasLiveOwner(run)) {
                    monitorLiveUi(run)
                    return
                }
                delay(250L)
                waited += 250L
            }
            run = BackgroundChatTaskStore.mutate(context, run.token) {
                it.copy(ownerUi = false, state = BackgroundChatRun.STATE_RECOVERING,
                    lastStatus = "Recovering after process restart…")
            } ?: return
        }

        recover(run)
    }

    /** Returns a service-owned run only when the live owner disappeared and recovery is needed. */
    private suspend fun monitorLiveUi(initial: BackgroundChatRun): BackgroundChatRun? {
        val context = appContext ?: return null
        var run = initial
        var missedSnapshots = 0
        while (isScopeActive()) {
            val snapshot = BackgroundChatCoordinator.liveSnapshot(run)
            if (snapshot == null) {
                missedSnapshots++
                if (missedSnapshots >= 5) {
                    return BackgroundChatTaskStore.mutate(context, run.token) {
                        it.copy(ownerUi = false, state = BackgroundChatRun.STATE_RECOVERING,
                            lastStatus = "Reconnecting to interrupted task…")
                    }
                }
                delay(LIVE_POLL_MS)
                continue
            }
            missedSnapshots = 0
            if (snapshot.isProcessing) {
                run = BackgroundChatTaskStore.mutate(context, run.token) {
                    it.copy(
                        state = BackgroundChatRun.STATE_RUNNING,
                        ownerUi = true,
                        lastStatus = snapshot.status.take(220),
                        lastError = null
                    )
                } ?: return null
                postRunning(run, snapshot.status, snapshot.partialText)
                delay(LIVE_POLL_MS)
                continue
            }

            val finalText = snapshot.finalText.ifBlank { snapshot.status }
            val stopped = snapshot.error?.contains("stopped by user", ignoreCase = true) == true
            val failed = !stopped && (
                snapshot.error != null ||
                    finalText.startsWith("Chat failed:", ignoreCase = true) ||
                    finalText.startsWith("Run status:", ignoreCase = true)
                )
            when {
                stopped -> finish(run, BackgroundChatRun.STATE_CANCELLED, "Task stopped",
                    finalText.ifBlank { "The run was stopped." }, snapshot.error)
                failed -> finish(run, BackgroundChatRun.STATE_FAILED, "Task interrupted",
                    finalText.ifBlank { snapshot.error ?: "Background run interrupted." }, snapshot.error)
                else -> finish(run, BackgroundChatRun.STATE_COMPLETED, "Task completed",
                    finalText.ifBlank { "Omni finished the background task." }, null)
            }
            return null
        }
        return null
    }

    private suspend fun recover(initial: BackgroundChatRun) {
        val context = appContext ?: return
        var run = initial
        val executor = BackgroundChatRecoveryExecutor(context)

        while (isScopeActive() && !run.isTerminal) {
            run = BackgroundChatTaskStore.mutate(context, run.token) {
                it.copy(
                    ownerUi = false,
                    state = BackgroundChatRun.STATE_RECOVERING,
                    attempts = it.attempts + 1,
                    lastStatus = if (it.attempts == 0) "Recovering interrupted task…" else "Retrying recovery…",
                    lastError = null
                )
            } ?: return

            postRunning(run, run.lastStatus, "Restoring the conversation checkpoint…")
            val outcome = try {
                executor.execute(run) { progress ->
                    val latest = BackgroundChatTaskStore.mutate(context, run.token) {
                        it.copy(lastStatus = progress.status.take(220), lastError = null)
                    } ?: run
                    postRunning(latest, progress.status, progress.partialText)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                BackgroundChatRecoveryExecutor.Outcome(
                    success = false,
                    result = "",
                    error = failure.message ?: "Recovery failed",
                    transientFailure = true
                )
            }

            if (outcome.success) {
                finish(run, BackgroundChatRun.STATE_COMPLETED, "Task completed",
                    outcome.result.ifBlank { "Omni finished the background task." }, null)
                return
            }

            val retryable = outcome.transientFailure && run.attempts < MAX_RECOVERY_ATTEMPTS
            if (!retryable) {
                finish(run, BackgroundChatRun.STATE_FAILED, "Task needs attention",
                    outcome.result.ifBlank { outcome.error ?: "Background execution failed." }, outcome.error)
                return
            }

            val delayMs = min(120_000L, 5_000L * (1L shl (run.attempts - 1).coerceIn(0, 5)))
            run = BackgroundChatTaskStore.mutate(context, run.token) {
                it.copy(
                    state = BackgroundChatRun.STATE_WAITING_NETWORK,
                    lastStatus = if (networkAvailable(context))
                        "Temporary failure • retrying in ${delayMs / 1_000}s"
                    else "Waiting for network…",
                    lastError = outcome.error?.take(500)
                )
            } ?: return
            postRunning(run, run.lastStatus, outcome.error.orEmpty())

            var remaining = delayMs
            while (remaining > 0 && isScopeActive()) {
                delay(min(5_000L, remaining))
                remaining -= 5_000L
                if (!networkAvailable(context)) {
                    val latest = BackgroundChatTaskStore.mutate(context, run.token) {
                        it.copy(lastStatus = "Waiting for network…")
                    } ?: run
                    postRunning(latest, "Waiting for network…", "The task will continue automatically when connectivity returns.")
                }
            }
            run = BackgroundChatTaskStore.get(context, run.token) ?: return
        }
    }

    @SuppressLint("MissingPermission")
    private fun postRunning(run: BackgroundChatRun, status: String, partial: String) {
        val context = appContext ?: return
        if (!canPostNotifications(context)) return
        val safePartial = partial.trim().takeLast(900)
        val fingerprint = "$status|$safePartial"
        if (lastNotificationFingerprint.put(run.token, fingerprint) == fingerprint) return

        val elapsed = ((System.currentTimeMillis() - run.startedAtMs) / 1_000L).coerceAtLeast(0L)
        val body = buildString {
            append(status)
            if (safePartial.isNotBlank()) append("\n\n").append(safePartial)
        }
        val notification = NotificationCompat.Builder(context, RUNNING_CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Omni is working • ${run.mode.lowercase().replaceFirstChar { it.uppercase() }}")
            .setContentText(status)
            .setSubText("${elapsed}s • background execution")
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openChatIntent(context, run))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setProgress(0, 0, true)
            .setGroup(GROUP_KEY)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(run.notificationId, notification) }
    }

    @SuppressLint("MissingPermission")
    private fun finish(
        run: BackgroundChatRun,
        state: String,
        title: String,
        text: String,
        error: String?
    ) {
        val context = appContext ?: return
        val terminal = BackgroundChatTaskStore.markTerminal(context, run.token, state, title, error) ?: run
        BackgroundChatWatchdogWorker.cancel(context, run.token)
        val nm = NotificationManagerCompat.from(context)
        runCatching { nm.cancel(run.notificationId) }
        if (canPostNotifications(context)) {
            val success = state == BackgroundChatRun.STATE_COMPLETED
            val notification = NotificationCompat.Builder(context, FINISHED_CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(if (success) "✅ $title" else title)
                .setContentText(text.replace('\n', ' ').take(140))
                .setStyle(NotificationCompat.BigTextStyle().bigText(text.take(2_500)))
                .setContentIntent(openChatIntent(context, terminal))
                .setAutoCancel(true)
                .setOnlyAlertOnce(false)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setGroup(GROUP_KEY)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
            runCatching {
                nm.notify(run.notificationId + 20_000, notification)
            }
        }
        BackgroundChatTaskStore.prune(context)
        BackgroundChatCoordinator.releaseViewModelIfIdle()
    }

    private fun openChatIntent(context: Context, run: BackgroundChatRun): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("deep_link_session_id", run.sessionId)
            putExtra("background_chat_run_token", run.token)
        }
        return PendingIntent.getActivity(
            context,
            run.notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                RUNNING_CHANNEL,
                "Background chat tasks",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Live progress for chat, Agent, and Swarm runs continuing in the background"
                setShowBadge(false)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                FINISHED_CHANNEL,
                "Background task results",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Completion and failure results for background chat tasks"
            }
        )
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock(context: Context, token: String): PowerManager.WakeLock? = runCatching {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OmniDev:BackgroundChat:${token.hashCode()}").apply {
            setReferenceCounted(false)
            acquire()
        }
    }.getOrNull()

    private fun networkAvailable(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun canPostNotifications(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun isScopeActive(): Boolean = scope?.let { it.coroutineContext[Job]?.isActive } == true

    /** Test/debug escape hatch; production normally lives for the process lifetime. */
    fun shutdown() {
        synchronized(runtimeLock) {
            scope?.cancel()
            scope = null
            jobs.clear()
            lastNotificationFingerprint.clear()
        }
    }
}
