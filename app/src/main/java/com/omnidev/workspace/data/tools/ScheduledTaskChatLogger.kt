package com.omnidev.workspace.data.tools

import android.content.Context
import android.util.Log

class ScheduledTaskChatLogger(private val context: Context) {
    fun logTaskExecution(taskId: String, prompt: String, result: String) {
        Log.d("ScheduledTaskChatLogger", "Task Execution Logged: [\$taskId] \$prompt -> \$result")
        // Logic to write to ChatMessageEntity would go here, utilizing a DAO.
    }
}
