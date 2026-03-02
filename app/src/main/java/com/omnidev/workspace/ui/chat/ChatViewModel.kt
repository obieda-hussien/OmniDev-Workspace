package com.omnidev.workspace.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omnidev.workspace.data.model.ChatMessage
import com.omnidev.workspace.data.model.MessageRole
import com.omnidev.workspace.data.repository.SettingsRepository
import com.omnidev.workspace.domain.engine.AgentEvent
import com.omnidev.workspace.domain.engine.AgentPipeline
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * UI state for the Omni-Chat interface.
 */
data class ChatUiState(
    /** The conversation message history. */
    val messages: List<ChatMessage> = emptyList(),
    /** The current text input. */
    val inputText: String = "",
    /** The active Target Context scope path. */
    val targetContext: String? = null,
    /** Whether the agent is currently executing. */
    val isProcessing: Boolean = false,
    /** Current agent status text for streaming display. */
    val agentStatus: String? = null,
    /** Error message to display. */
    val errorMessage: String? = null
)

/**
 * ViewModel for the Omni-Chat interface, managing conversation state
 * and agent pipeline execution.
 */
class ChatViewModel(
    private val settingsRepository: SettingsRepository,
    private val agentPipeline: AgentPipeline
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    init {
        loadTargetContext()
    }

    private fun loadTargetContext() {
        viewModelScope.launch {
            settingsRepository.observeTargetContext().collect { path ->
                _uiState.update { it.copy(targetContext = path) }
            }
        }
    }

    /**
     * Updates the text input field.
     */
    fun onInputChanged(text: String) {
        _uiState.update { it.copy(inputText = text) }
    }

    /**
     * Sets the Target Context scope path.
     */
    fun setTargetContext(path: String?) {
        viewModelScope.launch {
            settingsRepository.setTargetContext(path)
        }
    }

    /**
     * Sends the current input message and triggers the agent pipeline.
     */
    fun sendMessage() {
        val input = _uiState.value.inputText.trim()
        if (input.isEmpty()) return

        val scopePath = _uiState.value.targetContext
        if (scopePath == null) {
            _uiState.update { it.copy(errorMessage = "Please set a Target Context before sending messages.") }
            return
        }

        val userMessage = ChatMessage(
            role = MessageRole.USER,
            content = input
        )

        _uiState.update {
            it.copy(
                messages = it.messages + userMessage,
                inputText = "",
                isProcessing = true,
                agentStatus = "Starting agent...",
                errorMessage = null
            )
        }

        viewModelScope.launch {
            val modelId = settingsRepository
                .observeModelIdForRole(com.omnidev.workspace.data.model.ModelRole.AGENT)
                .first()

            val deepThinking = settingsRepository.observeDeepThinking().first()

            agentPipeline.execute(
                userMessage = input,
                conversationHistory = _uiState.value.messages.dropLast(1),
                modelId = modelId,
                scopePath = scopePath,
                enableDeepThinking = deepThinking
            ).collect { event ->
                when (event) {
                    is AgentEvent.Started ->
                        _uiState.update { it.copy(agentStatus = "Agent started...") }

                    is AgentEvent.Thinking ->
                        _uiState.update { it.copy(agentStatus = "Thinking (iteration ${event.iteration})...") }

                    is AgentEvent.ThinkingBlock ->
                        _uiState.update { it.copy(agentStatus = "Deep thinking...") }

                    is AgentEvent.ToolExecution ->
                        _uiState.update {
                            it.copy(agentStatus = "Executing ${event.toolName}...")
                        }

                    is AgentEvent.ToolResult ->
                        _uiState.update {
                            it.copy(
                                agentStatus = if (event.isError) "Tool error: ${event.toolName}"
                                else "Tool completed: ${event.toolName}"
                            )
                        }

                    is AgentEvent.FinalAnswer -> {
                        val assistantMessage = ChatMessage(
                            role = MessageRole.ASSISTANT,
                            content = event.content
                        )
                        _uiState.update {
                            it.copy(
                                messages = it.messages + assistantMessage,
                                isProcessing = false,
                                agentStatus = null
                            )
                        }
                    }

                    is AgentEvent.Error -> {
                        _uiState.update {
                            it.copy(
                                isProcessing = false,
                                agentStatus = null,
                                errorMessage = event.message
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * Clears the error message.
     */
    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }
}
