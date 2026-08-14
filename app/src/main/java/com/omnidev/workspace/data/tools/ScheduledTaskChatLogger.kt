package com.omnidev.workspace.data.tools

import com.omnidev.workspace.data.db.dao.ChatSessionDao
import com.omnidev.workspace.data.db.dao.ChatMessageDao
import com.omnidev.workspace.data.db.entities.ChatSessionEntity
import com.omnidev.workspace.data.db.entities.ChatMessageEntity
import com.omnidev.workspace.ui.chat.AgentConsoleEntry
import com.omnidev.workspace.ui.chat.AgentConsoleSerializer

object ScheduledTaskChatLogger {

    suspend fun logScheduledTaskStart(
        chatSessionDao: ChatSessionDao,
        chatMessageDao: ChatMessageDao,
        taskTitle: String,
        userPrompt: String
    ): Long {
        val sessionTitle = "⏰ [Scheduled Task] $taskTitle"
        val sessionId = chatSessionDao.insert(
            ChatSessionEntity(title = sessionTitle)
        )
        chatMessageDao.insert(
            ChatMessageEntity(
                sessionId = sessionId,
                role = "USER",
                content = userPrompt
            )
        )
        return sessionId
    }

    suspend fun logScheduledTaskCompletion(
        chatMessageDao: ChatMessageDao,
        sessionId: Long,
        finalResponse: String,
        consoleEntries: List<AgentConsoleEntry>
    ) {
        val json = AgentConsoleSerializer.serialize(consoleEntries)
        chatMessageDao.insert(
            ChatMessageEntity(
                sessionId = sessionId,
                role = "ASSISTANT",
                content = finalResponse,
                consoleEntriesJson = json
            )
        )
    }
}
