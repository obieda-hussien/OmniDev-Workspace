package com.omnidev.workspace.data.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.omnidev.workspace.BuildConfig
import com.omnidev.workspace.data.background.BackgroundAgentService
import com.omnidev.workspace.data.sync.OmniSyncService

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        try {
            OmniSyncService.start(context)
        } catch (error: RuntimeException) {
            Log.w("BootCompletedReceiver", "Android prevented scheduler service startup; open OmniDev to resume scheduling.", error)
        }

        // Lite intentionally strips FOREGROUND_SERVICE_SPECIAL_USE for Play compliance.
        // Standard/Pro/OEM/Admin can recover an interrupted user-started chat run after
        // reboot/package replacement from the durable run ledger.
        if (BuildConfig.TIER != "LITE") {
            try {
                BackgroundAgentService.recover(context)
            } catch (error: RuntimeException) {
                Log.w("BootCompletedReceiver", "Background agent recovery was deferred until OmniDev is opened.", error)
            }
        }
    }
}
