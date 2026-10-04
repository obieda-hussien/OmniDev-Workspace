package com.omnidev.workspace.data.routines

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

/** A user-owned teaching session can be stopped from another app at any time. */
object TeachingNotification {
    private const val ID = 6208
    fun show(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel("routine-teaching", "Teach Omni", NotificationManager.IMPORTANCE_LOW))
        fun intent(action: String) = PendingIntent.getBroadcast(context, action.hashCode(),
            Intent(context, TeachingControlReceiver::class.java).setAction(action), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, "routine-teaching")
            .setSmallIcon(android.R.drawable.ic_menu_save).setContentTitle("Omni is learning your steps")
            .setContentText("Use Decision for a step that needs thinking. Stop saves a draft.")
            .setOngoing(true).addAction(0, "Decision", intent("decision"))
            .addAction(0, "Stop & save", intent("stop")).addAction(0, "Discard", intent("discard")).build()
        runCatching { manager.notify(ID, notification) }
    }
    fun hide(context: Context) = (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(ID)
}
class TeachingControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val hub = RoutineLearningHub.get(context)
        when (intent.action) {
            "decision" -> hub.decision()
            "stop" -> hub.stopTeaching()
            "discard" -> hub.stopTeaching(false)
        }
    }
}
