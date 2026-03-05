package com.omnidev.workspace.data.tools

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * System notification listener that captures incoming notifications for the AI agent.
 *
 * Notifications are stored in [NotificationCaptureTool]'s in-memory buffer for
 * the `read_notifications` tool to query.
 *
 * This service requires the user to grant "Notification Access" in system settings.
 */
class AgentNotificationService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        try {
            val extras = sbn.notification?.extras ?: return
            val title = extras.getCharSequence("android.title")?.toString() ?: ""
            val text = extras.getCharSequence("android.text")?.toString() ?: ""
            val pkg = sbn.packageName ?: ""
            val time = sbn.postTime

            NotificationCaptureTool.onNotificationReceived(
                packageName = pkg,
                title = title,
                text = text,
                timestamp = time
            )
        } catch (_: Exception) {
            // Silently ignore malformed notifications
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // No-op: we only capture posted notifications
    }
}
