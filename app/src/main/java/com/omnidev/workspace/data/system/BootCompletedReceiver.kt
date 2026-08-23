package com.omnidev.workspace.data.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.omnidev.workspace.data.db.OmniDevDatabase
import com.omnidev.workspace.data.tools.TaskSchedulerTool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d("BootCompletedReceiver", "Boot completed, recovering scheduled tasks")
            val db = OmniDevDatabase.getInstance(context)
            val taskDao = db.scheduledTaskDao()

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val pendingTasks = taskDao.getTasksByStatus("PENDING")
                    val currentTime = System.currentTimeMillis()
                    for (task in pendingTasks) {
                        val delay = task.nextExecutionTime - currentTime
                        TaskSchedulerTool.scheduleTaskDirectly(
                            name = task.id,
                            prompt = task.prompt,
                            delayMinutes = if (delay > 0) (delay / 60000).toInt() else 0,
                            repeatIntervalMinutes = if (task.isRecurring) (task.repeatIntervalMs / 60000).toInt() else null
                        )
                    }
                } catch (e: Exception) {
                    Log.e("BootCompletedReceiver", "Failed to recover tasks", e)
                }
            }
        }
    }
}
