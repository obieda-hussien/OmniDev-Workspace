package com.omnidev.workspace.data.media

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.media.MediaMetadata
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
 * OmniMediaSessionService — The Agent's global remote control for device media.
 * * Monitors all active media sessions (Spotify, YouTube, Music players) in real-time
 * and provides an interface for the AI to observe and control playback.
 */
class OmniMediaSessionService : Service() {

    companion object {
        private const val TAG = "OmniMediaSession"
        private const val CHANNEL_ID = "omni_media_channel"
        private const val NOTIFICATION_ID = 4003

        private val _activeSession = MutableStateFlow<MediaSessionInfo?>(null)
        val activeSession: StateFlow<MediaSessionInfo?> = _activeSession.asStateFlow()

        fun start(context: Context) {
            val intent = Intent(context, OmniMediaSessionService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OmniMediaSessionService::class.java))
        }

        // ── Static Control API for AI Tools ────────────────────────────

        fun controlMedia(context: Context, action: String): String {
            return try {
                val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
                    ?: return "❌ MediaSessionManager unavailable."

                val component = ComponentName(context, AgentNotificationService::class.java)
                val sessions = msm.getActiveSessions(component)

                if (sessions.isEmpty()) return "❌ No active media sessions found."

                val controller = sessions[0]
                val controls = controller.transportControls

                when (action.lowercase().trim()) {
                    "play" -> { controls.play(); "▶️ Playing ${controller.packageName}" }
                    "pause" -> { controls.pause(); "⏸️ Paused ${controller.packageName}" }
                    "stop" -> { controls.stop(); "⏹️ Stopped ${controller.packageName}" }
                    "next", "skip" -> { controls.skipToNext(); "⏭️ Skipped to next" }
                    "previous", "prev" -> { controls.skipToPrevious(); "⏮️ Skipped to previous" }
                    "toggle" -> {
                        if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) {
                            controls.pause()
                            "⏸️ Toggled Pause"
                        } else {
                            controls.play()
                            "▶️ Toggled Play"
                        }
                    }
                    else -> "❓ Unknown action: $action"
                }
            } catch (e: SecurityException) {
                "⚠️ Notification Access Required."
            } catch (e: Exception) {
                "❌ Error: ${e.message}"
            }
        }

        fun getActiveMediaInfo(context: Context): String {
            return try {
                val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
                    ?: return "MediaSessionManager unavailable."

                val component = ComponentName(context, AgentNotificationService::class.java)
                val sessions = msm.getActiveSessions(component)

                if (sessions.isEmpty()) return "No media playing."

                buildString {
                    appendLine("🎵 Active Sessions (${sessions.size}):")
                    sessions.forEachIndexed { i, controller ->
                        val meta = controller.metadata
                        val state = controller.playbackState
                        val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "Unknown"
                        val artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "Unknown"
                        val playback = when (state?.state) {
                            PlaybackState.STATE_PLAYING -> "▶️ Playing"
                            PlaybackState.STATE_PAUSED -> "⏸️ Paused"
                            else -> "⬜ Idle"
                        }
                        appendLine("${i + 1}. [${controller.packageName}] $title - $artist ($playback)")
                    }
                }
            } catch (e: Exception) { "Error: ${e.message}" }
        }
    }

    private lateinit var mediaSessionManager: MediaSessionManager
    private var currentController: MediaController? = null

    // Real-time metadata callback
    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) { updateActiveSession() }
        override fun onPlaybackStateChanged(state: PlaybackState?) { updateActiveSession() }
        override fun onSessionDestroyed() { updateActiveSession() }
    }

    // Listener for when apps start/stop playing music
    private val sessionsChangedListener = MediaSessionManager.OnActiveSessionsChangedListener { 
        Log.d(TAG, "Active sessions changed")
        updateActiveSession()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        mediaSessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        
        createNotificationChannel()
        startForegroundServiceWithType()

        try {
            val component = ComponentName(this, AgentNotificationService::class.java)
            mediaSessionManager.addOnActiveSessionsChangedListener(sessionsChangedListener, component)
            updateActiveSession()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register sessions listener", e)
        }
    }

    private fun startForegroundServiceWithType() {
        val notification = buildNotification("Monitoring media playback...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        mediaSessionManager.removeOnActiveSessionsChangedListener(sessionsChangedListener)
        currentController?.unregisterCallback(controllerCallback)
        _activeSession.value = null
        super.onDestroy()
    }

    private fun updateActiveSession() {
        try {
            val component = ComponentName(this, AgentNotificationService::class.java)
            val sessions = mediaSessionManager.getActiveSessions(component)
            val first = sessions.firstOrNull()

            // Handle Callback registration for the new primary controller
            if (first != currentController) {
                currentController?.unregisterCallback(controllerCallback)
                currentController = first
                currentController?.registerCallback(controllerCallback)
            }

            _activeSession.value = first?.let { controller ->
                val meta = controller.metadata
                val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)
                val artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                
                // Update Foreground Notification dynamically
                val status = if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) "Playing" else "Paused"
                updateNotification("${title ?: "Unknown"} - ${artist ?: "Unknown"} ($status)")

                MediaSessionInfo(
                    packageName = controller.packageName,
                    title = title,
                    artist = artist,
                    isPlaying = controller.playbackState?.state == PlaybackState.STATE_PLAYING
                )
            } ?: run {
                updateNotification("No active media")
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Update session failed", e)
        }
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Media Control", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("OmniDev Media")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(pi)
            .build()
    }

    data class MediaSessionInfo(
        val packageName: String,
        val title: String?,
        val artist: String?,
        val isPlaying: Boolean
    )
}
