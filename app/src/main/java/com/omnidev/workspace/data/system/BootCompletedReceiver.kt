package com.omnidev.workspace.data.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.omnidev.workspace.data.sync.OmniSyncService

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        try {
            OmniSyncService.start(context)
        } catch (error: RuntimeException) {
            Log.w("BootCompletedReceiver", "Android prevented background service startup; open OmniDev to resume scheduling.", error)
        }
    }
}
