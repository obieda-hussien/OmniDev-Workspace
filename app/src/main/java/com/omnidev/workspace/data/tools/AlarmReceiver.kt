package com.omnidev.workspace.data.tools

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.AlarmClock
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.Calendar

/**
 * Simple BroadcastReceiver for immediate alarms triggered through AlarmManager.
 * Registered in AndroidManifest.xml.
 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val title = intent.getStringExtra("title") ?: "Alarm"
        Log.i("AlarmReceiver", "🔔 Alarm triggered: $title")

        // Launch the system alarm activity when available.
        try {
            val alarmIntent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                putExtra(AlarmClock.EXTRA_MESSAGE, title)
                putExtra(AlarmClock.EXTRA_HOUR, Calendar.getInstance().get(Calendar.HOUR_OF_DAY))
                putExtra(AlarmClock.EXTRA_MINUTES, Calendar.getInstance().get(Calendar.MINUTE))
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            }
            context.startActivity(alarmIntent)
        } catch (_: Exception) {
            // Fallback to a notification when the system alarm UI is unavailable.
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    "alarm_ch",
                    "Alarms",
                    NotificationManager.IMPORTANCE_HIGH
                )
                nm.createNotificationChannel(channel)
            }
            val notification = NotificationCompat.Builder(context, "alarm_ch")
                .setContentTitle("⏰ $title")
                .setContentText("It's time!")
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setAutoCancel(true)
                .build()
            nm.notify(title.hashCode(), notification)
        }
    }
}
