package com.omnidev.workspace.ui.companion

import com.omnidev.workspace.data.model.MessageRole
import com.omnidev.workspace.ui.chat.AgentConsoleEntry
import com.omnidev.workspace.ui.chat.ChatUiState

/** Reuse real run events; the companion never guesses status from generated text. */
internal fun companionActivity(chat: ChatUiState, listening: Boolean = false): CompanionActivity = when {
    listening -> CompanionActivity.LISTENING
    chat.errorMessage != null -> CompanionActivity.ERROR
    chat.pendingConfirmation != null || chat.messages.lastOrNull()?.executionRequest?.status == "pending" -> CompanionActivity.WAITING
    chat.isProcessing -> when (val event = chat.consoleEntries.lastOrNull()) {
        is AgentConsoleEntry.ErrorEntry -> CompanionActivity.ERROR
        is AgentConsoleEntry.ResultEntry -> if (event.isError) CompanionActivity.ERROR else CompanionActivity.THINKING
        is AgentConsoleEntry.ToolEntry -> CompanionActivity.TOOL
        else -> CompanionActivity.THINKING
    }
    chat.agentStatus?.startsWith("Stopped") == true -> CompanionActivity.IDLE
    chat.messages.lastOrNull()?.role == MessageRole.ASSISTANT -> CompanionActivity.SUCCESS
    else -> CompanionActivity.IDLE
}
