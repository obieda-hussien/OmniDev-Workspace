package com.omnidev.workspace.data.tools

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.util.Log
import java.util.Calendar

/**
 * BroadcastReceiver بسيط للمنبّهات المباشرة عبر AlarmManager.
 * (يُسجَّل في AndroidManifest.xml)
 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val title = intent.getStringExtra("title") ?: "منبّه"
        Log.i("AlarmReceiver", "🔔 المنبّه نشّط: \$title")

        // إطلاق نشاط المنبّه إن وُجد
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
            // fallback: notification فقط
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val ch = android.app.NotificationChannel("alarm_ch", "المنبّهات", android.app.NotificationManager.IMPORTANCE_HIGH)
                nm.createNotificationChannel(ch)
            }
            val notif = android.app.Notification.Builder(context, "alarm_ch")
                .setContentTitle("⏰ \$title")
                .setContentText("حان الوقت!")
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setAutoCancel(true)
                .build()
            nm.notify(title.hashCode(), notif)
        }
    }
}
