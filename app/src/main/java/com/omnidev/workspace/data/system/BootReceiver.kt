package com.omnidev.workspace.data.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.omnidev.workspace.data.sync.OmniSyncService
import com.omnidev.workspace.data.voice.VoiceAssistantService

/**
 * Starts core background services after device boot or app update.
 *
 * Listens for:
 *  - [Intent.ACTION_BOOT_COMPLETED] — standard boot
 *  - `android.intent.action.QUICKBOOT_POWERON` — HTC / vendor fast-boot
 *  - [Intent.ACTION_MY_PACKAGE_REPLACED] — self-update
 *
 * On each event, it starts [VoiceAssistantService] (wake-word daemon) and
 * [OmniSyncService] (periodic memory / task sync) as foreground services.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        Log.i(TAG, "Received boot event: $action")

        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                startCoreServices(context)
            }
        }
    }

    private fun startCoreServices(context: Context) {
        // ── Voice Assistant (wake-word daemon) ──
        try {
            val voiceIntent = Intent(context, VoiceAssistantService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(voiceIntent)
            } else {
                context.startService(voiceIntent)
            }
            Log.i(TAG, "VoiceAssistantService started on boot")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start VoiceAssistantService on boot", e)
        }

        // ── Sync Service (periodic background sync) ──
        try {
            val syncIntent = Intent(context, OmniSyncService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(syncIntent)
            } else {
                context.startService(syncIntent)
            }
            Log.i(TAG, "OmniSyncService started on boot")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start OmniSyncService on boot", e)
        }
    }
}
