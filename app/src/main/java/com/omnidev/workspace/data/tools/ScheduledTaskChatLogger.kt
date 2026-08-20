package com.omnidev.workspace.data.tools

import com.omnidev.workspace.data.analytics.DynamicPricingManager
import com.omnidev.workspace.data.db.dao.ChatMessageDao
import com.omnidev.workspace.data.db.dao.ChatSessionDao
import com.omnidev.workspace.data.db.entities.ChatMessageEntity
import com.omnidev.workspace.data.db.entities.ChatSessionEntity
import com.omnidev.workspace.ui.chat.AgentConsoleEntry
import com.omnidev.workspace.ui.chat.AgentConsoleSerializer
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ScheduledTaskChatLogger(
    private val chatSessionDao: ChatSessionDao,
    private val chatMessageDao: ChatMessageDao,
    private val pricingManager: DynamicPricingManager? = null
) {
    suspend fun createInitialSession(taskTitle: String, taskPrompt: String): Long = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val session = ChatSessionEntity(
            title = "⏰ [Scheduled Task] $taskTitle",
            createdAt = now,
            lastUpdated = now
        )
        val sessionId = chatSessionDao.insert(session)

        val userMessage = ChatMessageEntity(
            sessionId = sessionId,
            role = "USER",
            content = "Triggered scheduled task:\n$taskPrompt",
            timestamp = now,
            messageId = UUID.randomUUID().toString()
        )
        chatMessageDao.insert(userMessage)

        return@withContext sessionId
    }

    suspend fun completeSession(
        sessionId: Long,
        finalResponse: String,
        consoleEntries: List<AgentConsoleEntry>,
        modelId: String? = null,
        promptTokens: Int = 0,
        completionTokens: Int = 0,
        directCostUSD: Double? = null
    ) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val serializedConsole = AgentConsoleSerializer.serialize(consoleEntries)
        val calculatedCost = pricingManager?.calculateCost(modelId, promptTokens, completionTokens, directCostUSD) ?: 0.0

        val assistantMessage = ChatMessageEntity(
            sessionId = sessionId,
            role = "ASSISTANT",
            content = finalResponse,
            timestamp = now,
            consoleEntriesJson = serializedConsole,
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            totalTokens = promptTokens + completionTokens,
            costUSD = calculatedCost,
            modelId = modelId,
            messageId = UUID.randomUUID().toString()
        )
        chatMessageDao.insert(assistantMessage)
    }

    companion object {
        suspend fun logScheduledTaskStart(
            chatSessionDao: ChatSessionDao,
            chatMessageDao: ChatMessageDao,
            taskTitle: String,
            userPrompt: String
        ): Long {
            return ScheduledTaskChatLogger(chatSessionDao, chatMessageDao).createInitialSession(taskTitle, userPrompt)
        }

        suspend fun logScheduledTaskCompletion(
            chatMessageDao: ChatMessageDao,
            sessionId: Long,
            finalResponse: String,
            consoleEntries: List<AgentConsoleEntry>
        ) {
            ScheduledTaskChatLogger(null!!, chatMessageDao).completeSession(
                sessionId = sessionId,
                finalResponse = finalResponse,
                consoleEntries = consoleEntries
            )
        }
    }
}
