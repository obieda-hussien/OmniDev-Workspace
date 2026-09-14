package com.omnidev.workspace.data.tools

import com.omnidev.workspace.data.db.dao.ChatMessageDao
import com.omnidev.workspace.data.db.dao.ChatSessionDao
import com.omnidev.workspace.data.db.entities.ChatMessageEntity
import com.omnidev.workspace.ui.chat.AgentConsoleEntry
import com.omnidev.workspace.ui.chat.AgentConsoleSerializer

/** Durable background-run transcript; recurring runs reuse the same conversation. */
class ScheduledTaskChatLogger(
    private val sessionDao: ChatSessionDao,
    private val messageDao: ChatMessageDao
) {
    suspend fun sessionIdFor(task: TaskSchedulerTool.ScheduledTask): Long =
        sessionDao.sessionForBackgroundRun("scheduled_task:${task.id}", task.name)

    suspend fun start(task: TaskSchedulerTool.ScheduledTask, sessionId: Long): Long {
        messageDao.insert(ChatMessageEntity(sessionId = sessionId, role = "USER", content = task.prompt,
            messageId = java.util.UUID.randomUUID().toString()))
        return messageDao.insert(ChatMessageEntity(sessionId = sessionId, role = "ASSISTANT",
            content = "Task running…", messageId = java.util.UUID.randomUUID().toString()))
    }

    suspend fun progress(messageId: Long, content: String, entries: List<AgentConsoleEntry>) {
        messageDao.updateProgress(messageId, content.take(100_000), AgentConsoleSerializer.serialize(entries.takeLast(500)))
    }
}
