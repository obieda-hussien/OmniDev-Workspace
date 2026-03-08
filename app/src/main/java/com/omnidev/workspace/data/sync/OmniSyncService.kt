package com.omnidev.workspace.data.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.omnidev.workspace.MainActivity
import com.omnidev.workspace.R
import com.omnidev.workspace.data.tools.TaskSchedulerTool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Background data-sync service running as a foreground `dataSync` service.
 *
 * Periodically:
 *  1. Checks [TaskSchedulerTool] for tasks whose `scheduledTimeMillis` has arrived
 *     and marks them as [TaskSchedulerTool.TaskStatus.RUNNING].
 *  2. Emits sync heartbeat via [syncState] so the UI can display sync status.
 *
 * The sync interval is configurable via [SYNC_INTERVAL_MS] (default: 60 s).
 * Started on boot by [com.omnidev.workspace.data.system.BootReceiver] and can
 * be started/stopped manually via [start] / [stop] companion methods.
 */
class OmniSyncService : Service() {

    companion object {
        private const val TAG = "OmniSyncService"
        private const val CHANNEL_ID = "omni_sync_channel"
        private const val NOTIFICATION_ID = 4002
        private const val SYNC_INTERVAL_MS = 60_000L

        private val _syncState = MutableStateFlow(SyncState.IDLE)
        val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

        private val _lastSyncTimeMs = MutableStateFlow(0L)
        val lastSyncTimeMs: StateFlow<Long> = _lastSyncTimeMs.asStateFlow()

        /** Convenience method to start the service from any context. */
        fun start(context: Context) {
            val intent = Intent(context, OmniSyncService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** Convenience method to stop the service. */
        fun stop(context: Context) {
            context.stopService(Intent(context, OmniSyncService::class.java))
        }
    }

    enum class SyncState { IDLE, SYNCING, ERROR }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var syncJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification = buildNotification("OmniDev Sync active")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        Log.i(TAG, "OmniSyncService created — starting periodic sync loop")
        startSyncLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "OmniSyncService destroyed")
        syncJob?.cancel()
        scope.cancel()
        _syncState.value = SyncState.IDLE
        super.onDestroy()
    }

    // ── Sync loop ────────────────────────────────────────────────────────

    private fun startSyncLoop() {
        syncJob?.cancel()
        syncJob = scope.launch {
            while (isActive) {
                try {
                    _syncState.value = SyncState.SYNCING
                    performSync()
                    _syncState.value = SyncState.IDLE
                    _lastSyncTimeMs.value = System.currentTimeMillis()
                } catch (e: Exception) {
                    Log.e(TAG, "Sync cycle failed", e)
                    _syncState.value = SyncState.ERROR
                }
                delay(SYNC_INTERVAL_MS)
            }
        }
    }

    /**
     * Core sync logic:
     * 1. Check for scheduled tasks that are due.
     * 2. Mark ready tasks as RUNNING (the external executor drives actual execution).
     */
    private suspend fun performSync() {
        val readyTasks = TaskSchedulerTool.getReadyTasks()
        if (readyTasks.isNotEmpty()) {
            Log.d(TAG, "Found ${readyTasks.size} ready task(s), marking as RUNNING")
            for (task in readyTasks) {
                TaskSchedulerTool.markRunning(task.id)
            }
        }
    }

    // ── Notification ─────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "OmniDev Sync",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background data synchronization for OmniDev"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("OmniDev Sync")
            .setContentText(contentText)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(pendingIntent)
            .build()
    }
}
