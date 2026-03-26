package com.omnidev.workspace.data.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.omnidev.workspace.MainActivity
import com.omnidev.workspace.R
import com.omnidev.workspace.data.tools.AgentNotificationService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Controls media playback across all apps on the device.
 *
 * Uses [MediaSessionManager] to discover active media sessions and issue
 * transport commands (play, pause, skip, stop) — the agent can orchestrate
 * music / podcast / video playback without opening the target app.
 *
 * Requires the `NotificationListenerService` permission (shared with
 * [AgentNotificationService]) for [MediaSessionManager.getActiveSessions].
 *
 * Runs as a foreground `mediaPlayback` service.
 */
class OmniMediaSessionService : Service() {

    companion object {
        private const val TAG = "OmniMediaSession"
        private const val CHANNEL_ID = "omni_media_channel"
        private const val NOTIFICATION_ID = 4003

        private val _activeSession = MutableStateFlow<MediaSessionInfo?>(null)
        val activeSession: StateFlow<MediaSessionInfo?> = _activeSession.asStateFlow()

        /** Convenience method to start the service. */
        fun start(context: Context) {
            val intent = Intent(context, OmniMediaSessionService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** Convenience method to stop the service. */
        fun stop(context: Context) {
            context.stopService(Intent(context, OmniMediaSessionService::class.java))
        }

        // ── Static media control API (callable by the agent) ────────────

        /**
         * Sends a transport command to the currently active media session.
         *
         * @param context Application context for MediaSessionManager access.
         * @param action  One of: `play`, `pause`, `stop`, `next`, `previous`.
         * @return Human-readable result description.
         */
        fun controlMedia(context: Context, action: String): String {
            return try {
                val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE)
                        as? MediaSessionManager
                    ?: return "MediaSessionManager not available on this device."

                val component = ComponentName(context, AgentNotificationService::class.java)
                val sessions = msm.getActiveSessions(component)

                if (sessions.isEmpty()) {
                    return "No active media sessions found. Make sure music or media is playing."
                }

                val controller = sessions[0]
                val controls = controller.transportControls

                when (action.lowercase()) {
                    "play" -> {
                        controls.play()
                        "▶️ Play command sent to ${controller.packageName}"
                    }
                    "pause" -> {
                        controls.pause()
                        "⏸️ Pause command sent to ${controller.packageName}"
                    }
                    "stop" -> {
                        controls.stop()
                        "⏹️ Stop command sent to ${controller.packageName}"
                    }
                    "next", "skip" -> {
                        controls.skipToNext()
                        "⏭️ Skip to next sent to ${controller.packageName}"
                    }
                    "previous", "prev" -> {
                        controls.skipToPrevious()
                        "⏮️ Skip to previous sent to ${controller.packageName}"
                    }
                    "toggle" -> {
                        val state = controller.playbackState?.state
                        if (state == PlaybackState.STATE_PLAYING) {
                            controls.pause()
                            "⏸️ Toggled: Pause sent to ${controller.packageName}"
                        } else {
                            controls.play()
                            "▶️ Toggled: Play sent to ${controller.packageName}"
                        }
                    }
                    else -> "Unknown media action '$action'. Use: play, pause, stop, next, previous, toggle."
                }
            } catch (e: SecurityException) {
                "Media control requires Notification Access permission. Enable it in Settings → Notifications → Device & app notifications."
            } catch (e: Exception) {
                "Media control error: ${e.message}"
            }
        }

        /**
         * Returns information about all active media sessions.
         */
        fun getActiveMediaInfo(context: Context): String {
            return try {
                val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE)
                        as? MediaSessionManager
                    ?: return "MediaSessionManager not available."

                val component = ComponentName(context, AgentNotificationService::class.java)
                val sessions = msm.getActiveSessions(component)

                if (sessions.isEmpty()) {
                    return "No active media sessions."
                }

                buildString {
                    appendLine("Active Media Sessions (${sessions.size}):")
                    for ((i, controller) in sessions.withIndex()) {
                        val metadata = controller.metadata
                        val state = controller.playbackState

                        val title = metadata?.getString(
                            android.media.MediaMetadata.METADATA_KEY_TITLE
                        ) ?: "Unknown"
                        val artist = metadata?.getString(
                            android.media.MediaMetadata.METADATA_KEY_ARTIST
                        ) ?: "Unknown"
                        val album = metadata?.getString(
                            android.media.MediaMetadata.METADATA_KEY_ALBUM
                        ) ?: ""

                        val playbackStr = when (state?.state) {
                            PlaybackState.STATE_PLAYING -> "▶️ Playing"
                            PlaybackState.STATE_PAUSED -> "⏸️ Paused"
                            PlaybackState.STATE_STOPPED -> "⏹️ Stopped"
                            PlaybackState.STATE_BUFFERING -> "⏳ Buffering"
                            else -> "⬜ ${state?.state ?: "unknown"}"
                        }

                        appendLine("  ${i + 1}. ${controller.packageName}")
                        appendLine("     Title: $title")
                        appendLine("     Artist: $artist")
                        if (album.isNotBlank()) appendLine("     Album: $album")
                        appendLine("     Status: $playbackStr")
                    }
                }
            } catch (e: SecurityException) {
                "Notification Access permission required for media session info."
            } catch (e: Exception) {
                "Error reading media sessions: ${e.message}"
            }
        }
    }

    /** Lightweight snapshot of the active media session. */
    data class MediaSessionInfo(
        val packageName: String,
        val title: String?,
        val artist: String?,
        val isPlaying: Boolean
    )

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification = buildNotification("Media session controller active")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        Log.i(TAG, "OmniMediaSessionService created")
        updateActiveSession()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        updateActiveSession()
        return START_STICKY
    }

    override fun onDestroy() {
        _activeSession.value = null
        Log.i(TAG, "OmniMediaSessionService destroyed")
        super.onDestroy()
    }

    private fun updateActiveSession() {
        try {
            val msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
                ?: return
            val component = ComponentName(this, AgentNotificationService::class.java)
            val sessions = msm.getActiveSessions(component)
            val first = sessions.firstOrNull()

            _activeSession.value = first?.let { controller ->
                val metadata = controller.metadata
                MediaSessionInfo(
                    packageName = controller.packageName,
                    title = metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE),
                    artist = metadata?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST),
                    isPlaying = controller.playbackState?.state == PlaybackState.STATE_PLAYING
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update active session", e)
        }
    }

    // ── Notification ─────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "OmniDev Media Control",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Media session control for OmniDev AI"
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
            .setContentTitle("OmniDev Media")
            .setContentText(contentText)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(pendingIntent)
            .build()
    }
}
