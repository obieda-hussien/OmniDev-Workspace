package com.omnidev.workspace.data.tools

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * System notification listener that captures incoming notifications for the AI agent.
 *
 * * FIXES & UPGRADES:
 * 1. Deep Integration: Delegates the raw StatusBarNotification to the upgraded 
 * [NotificationCaptureTool] to leverage deep text extraction and deduplication.
 * 2. Catch-up Mechanism: Fetches all currently visible notifications the moment 
 * the service connects, ensuring the agent doesn't miss pre-existing alerts.
 * 3. Lifecycle Logging: Tracks connection state to debug if the agent goes "deaf".
 *
 * This service requires the user to grant "Notification Access" in system settings.
 */
class AgentNotificationService : NotificationListenerService() {

    companion object {
        private const val TAG = "AgentNotifService"
    }

    /**
     * Fired when the user grants permission and the system binds to this service.
     */
    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "Notification Listener Connected! AI Agent is now listening.")
        
        // Catch-up: Grab all currently active notifications on the device
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

    /**
     * Fired if the system kills the listener or permission is revoked.
     */
    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.w(TAG, "Notification Listener Disconnected. AI Agent is now deaf.")
    }

    /**
     * Fired when a brand new notification pops up.
     */
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        try {
            // Delegate all the heavy lifting (BigText, App Name resolution, Deduplication)
            // directly to our upgraded tool.
            NotificationCaptureTool.onNotificationPosted(this, sbn)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process posted notification from ${sbn.packageName}", e)
        }
    }

    /**
     * Fired when the user (or system) swipes away a notification.
     */
    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // No-op: We deliberately keep dismissed notifications in our in-memory buffer 
        // so the agent can still read historical alerts (e.g., OTP codes that auto-dismiss).
    }
}
