package com.omnidev.workspace.data.chatmedia

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

/** User-initiated Start now executes immediately, without waiting for JobScheduler or quota. */
internal class MediaGenerationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val jobs = mutableMapOf<String, Job>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra("job")
        if (id == null) { if (jobs.isEmpty()) stopSelf(startId); return START_NOT_STICKY }
        if (jobs.containsKey(id)) return START_NOT_STICKY
        try {
            val channel = "omni-media-generation"
            if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(channel, "Media creation", NotificationManager.IMPORTANCE_LOW))
            val notification = NotificationCompat.Builder(this, channel)
                .setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("Omni is creating your media")
                .setContentText("Your request is running. Open its chat card to view progress or cancel.")
                .setOngoing(true).setOnlyAlertOnce(true).build()
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification,
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        } catch (_: Exception) {
            startFailed(this, id)
            if (jobs.isEmpty()) stopSelf(startId)
            return START_NOT_STICKY
        }
        active.add(id)
        val execution = scope.launch(start = CoroutineStart.LAZY) {
            try {
                withContext(Dispatchers.IO) {
                    withTimeout(20 * 60_000L) {
                        for (attempt in 0..40) {
                            if (MediaGenerationExecution(applicationContext, id, attempt).run() != MediaGenerationExecution.Outcome.RETRY)
                                break
                            delay(10_000)
                        }
                    }
                }
            } finally {
                active.remove(id); jobs.remove(id); executions.remove(id)
                if (jobs.isEmpty()) stopSelf()
            }
        }
        jobs[id] = execution
        executions[id] = execution
        execution.start()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 0x4D454449
        private val active = ConcurrentHashMap.newKeySet<String>()
        private val executions = ConcurrentHashMap<String, Job>()
        fun isActive(id: String) = id in active
        fun cancel(id: String) { executions[id]?.cancel() }

        /** A fresh queue clock prevents an old monitor snapshot from expiring a manual start. */
        suspend fun startNow(context: Context, id: String, startService: (Context, String) -> Unit = { app, jobId ->
            requireNotNull(ContextCompat.startForegroundService(app,
                Intent(app, MediaGenerationService::class.java).putExtra("job", jobId)))
        }) = withContext(Dispatchers.IO) {
            val store = MediaJobStore(context)
            val job = store.get(id) ?: error("Saved media request is missing")
            if (isActive(id)) return@withContext
            if (!MediaQueuePolicy.canStart(job)) return@withContext
            val starting = MediaQueuePolicy.restart(job, System.currentTimeMillis()).copy(phase = "starting")
            if (!store.compareAndUpdate(job, starting)) return@withContext
            // Cover the handoff before onStartCommand: old WorkManager terminal states are stale.
            active.add(id)
            try {
                startService(context.applicationContext, id)
            } catch (_: Exception) { startFailed(context, id) }
        }

        private fun startFailed(context: Context, id: String) {
            active.remove(id)
            val store = MediaJobStore(context)
            val current = store.get(id) ?: return
            // A scheduled worker may already own the provider request; never replace its result.
            if (current.state == "queued" && current.operation == null) store.compareAndUpdate(current,
                current.copy(state = "failed", phase = "failed", errorCode = "START_FAILED",
                    error = "Android could not start media creation directly. Keep Omni open and tap Start now again; check app background restrictions."))
        }
    }
}
