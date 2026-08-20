package com.omnidev.workspace.data.tools

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import com.omnidev.workspace.MainActivity

class AgentNotificationService : NotificationListenerService() {

    companion object {
        private const val TAG = "AgentNotifService"
        private const val SCHEDULED_CHANNEL_ID = "omni_scheduled_tasks_channel"

        fun showScheduledTaskNotification(
            context: Context,
            taskId: String,
            sessionId: String,
            taskTitle: String,
            statusText: String,
            isOngoing: Boolean
        ) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    SCHEDULED_CHANNEL_ID,
                    "OmniDev Scheduled Tasks",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Shows notifications and live progress for background AI tasks"
                }
                notificationManager.createNotificationChannel(channel)
            }

            val intent = Intent(context, MainActivity::class.java).apply {
                action = "ACTION_OPEN_SCHEDULED_CHAT"
                putExtra("EXTRA_SESSION_ID", sessionId)
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }

            val pendingIntent = PendingIntent.getActivity(
                context,
                sessionId.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, SCHEDULED_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("⏰ $taskTitle")
                .setContentText(statusText)
                .setOngoing(isOngoing)
                .setAutoCancel(!isOngoing)
                .setContentIntent(pendingIntent)
                .build()

            notificationManager.notify(taskId.hashCode(), notification)
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "Notification Listener Connected! AI Agent is now listening.")
        try {
            val activeNotifs = activeNotifications
            if (activeNotifs != null && activeNotifs.isNotEmpty()) {
                Log.d(TAG, "Catching up on ${activeNotifs.size} existing notifications...")
                for (sbn in activeNotifs) {
                    NotificationCaptureTool.onNotificationPosted(this, sbn)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch active notifications on connect", e)
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.w(TAG, "Notification Listener Disconnected. AI Agent is now deaf.")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        try {
            NotificationCaptureTool.onNotificationPosted(this, sbn)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process posted notification from ${sbn.packageName}", e)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {}
}
