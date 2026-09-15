package com.omnidev.workspace.data.tools

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Private explicit callback endpoint used only by PendingIntents created by
 * [TermuxRunCommandBridge]. It is declared `exported=false` in capable flavors.
 */
class TermuxResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        TermuxRunCommandBridge.init(context.applicationContext)
        TermuxRunCommandBridge.deliverResult(intent)
    }
}
