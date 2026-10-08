package com.omnidev.workspace.ui.chat

import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import android.net.Uri
import com.omnidev.workspace.data.chatmedia.ChatMediaStore
import com.omnidev.workspace.data.model.AttachmentMeta
import com.omnidev.workspace.data.model.AttachmentMediaType
import com.omnidev.workspace.data.model.ChatMessage
import com.omnidev.workspace.data.model.MessageRole
import com.omnidev.workspace.data.repository.SettingsRepository
import com.omnidev.workspace.data.tools.ToolDefinition
import com.omnidev.workspace.data.tools.ToolExecutionResult
import com.omnidev.workspace.data.tools.ToolManager
import com.omnidev.workspace.domain.engine.AgentPipeline
import com.omnidev.workspace.domain.engine.OmniMode
import com.omnidev.workspace.domain.engine.SwarmOrchestrator
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Exercise the real replay entry points without making provider requests. */
class ChatReplayModeTest {
    private val tools = object : ToolManager {
        override fun getToolDefinitions() = emptyList<ToolDefinition>()
        override suspend fun executeTool(name: String, arguments: Map<String, String>, scopePath: String?) = ToolExecutionResult("unused")
    }

    @Test fun regenerationAndEditingUseEveryCurrentlySelectedMode() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        for (mode in listOf(OmniMode.CHAT, OmniMode.AGENT, OmniMode.SWARM)) for (edit in listOf(false, true)) {
            val reachedProvider = CountDownLatch(1)
            val route = AtomicReference<OmniMode>()
            suspend fun holdProvider(selected: OmniMode): Nothing {
                route.set(selected)
                reachedProvider.countDown()
                awaitCancellation()
            }
            lateinit var vm: ChatViewModel
            var owner: ViewModelStore? = null
            val file = File(ChatMediaStore.directory(instrumentation.targetContext), "replay-mode-fixture.txt").apply { writeText("fixture") }
            try {
                instrumentation.runOnMainSync {
                    vm = ChatViewModel(SettingsRepository(instrumentation.targetContext),
                        AgentPipeline(toolManager = tools, completionProvider = { holdProvider(OmniMode.AGENT) }),
                        completionProvider = { holdProvider(OmniMode.CHAT) },
                        swarmOrchestrator = SwarmOrchestrator(toolManager = tools, completionProvider = { holdProvider(OmniMode.SWARM) }))
                    owner = ViewModelStore().also { it.put("replay", vm) }
                    val oldMode = if (mode == OmniMode.CHAT) OmniMode.AGENT else OmniMode.CHAT
                    val attachment = AttachmentMeta(Uri.fromFile(file).toString(), "text/plain", file.name, file.length(), AttachmentMediaType.TEXT)
                    val user = ChatMessage(MessageRole.USER, "original", messageId = "user", userInput = "original", userMode = oldMode.name, userScopePath = "/old", attachments = listOf(attachment))
                    val answer = ChatMessage(MessageRole.ASSISTANT, "answer", messageId = "answer")
                    seed(vm, ChatUiState(messages = listOf(user, answer), activeMode = mode, targetContext = "/selected", currentSessionId = 42))
                    if (edit) vm.editLastUserMessage("user", "edited") else vm.regenerateLastResponse("answer")
                    assertTrue("$oldMode → $mode, edit=$edit", vm.uiState.value.isProcessing)
                    assertEquals(mode != OmniMode.CHAT, vm.uiState.value.canSteer)
                    assertNull(vm.uiState.value.errorMessage)
                }
                // Start labels are transient: ChatToolLoop also emits Thinking.
                // Observe the real provider route, regardless of IO/frame timing.
                assertTrue("$mode provider was not reached, edit=$edit: ${vm.uiState.value.errorMessage}",
                    reachedProvider.await(15, TimeUnit.SECONDS))
                assertEquals(mode, route.get())
                instrumentation.runOnMainSync {
                    val replayed = vm.uiState.value.messages.last { it.role == MessageRole.USER }
                    assertEquals(mode.name, replayed.userMode)
                    assertEquals(if (edit) "edited" else "original", replayed.userInput)
                    assertEquals(mode, vm.uiState.value.activeMode)
                    assertTrue(vm.uiState.value.isProcessing)
                    assertNull(vm.uiState.value.errorMessage)
                }
            } finally {
                instrumentation.runOnMainSync { owner?.let { vm.cancelCurrentRun(); it.clear() } }
                file.delete()
            }
        }
    }

    @Test fun currentAgentScopeIsValidatedBeforeReplacingTheOriginalChatTurn() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val vm = ChatViewModel(SettingsRepository(instrumentation.targetContext),
                AgentPipeline(toolManager = tools, completionProvider = { awaitCancellation() }))
            val owner = ViewModelStore().also { it.put("replay", vm) }
            try {
                val messages = listOf(ChatMessage(MessageRole.USER, "question", messageId = "user", userMode = "CHAT", userScopePath = "/old"),
                    ChatMessage(MessageRole.ASSISTANT, "answer", messageId = "answer"))
                seed(vm, ChatUiState(messages = messages, activeMode = OmniMode.AGENT, targetContext = null))
                vm.regenerateLastResponse("answer")
                assertFalse(vm.uiState.value.isProcessing)
                assertTrue(vm.uiState.value.errorMessage!!.contains("Target Context"))
                assertEquals(messages, vm.uiState.value.messages)
            } finally { owner.clear() }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun seed(vm: ChatViewModel, state: ChatUiState) {
        val field = ChatViewModel::class.java.getDeclaredField("_uiState").also { it.isAccessible = true }
        (field.get(vm) as MutableStateFlow<ChatUiState>).value = state
    }
}
