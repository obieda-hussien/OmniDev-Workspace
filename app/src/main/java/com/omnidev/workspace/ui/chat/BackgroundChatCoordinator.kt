package com.omnidev.workspace.ui.chat

import android.content.Context
import android.net.Uri
import com.omnidev.workspace.BuildConfig
import com.omnidev.workspace.data.background.BackgroundAgentRunBus
import com.omnidev.workspace.data.background.BackgroundAgentService
import com.omnidev.workspace.data.db.OmniDevDatabase
import com.omnidev.workspace.data.model.AttachmentMediaType
import com.omnidev.workspace.data.model.AttachmentMeta
import com.omnidev.workspace.data.model.ChatMessage
import com.omnidev.workspace.data.model.MessageRole
import com.omnidev.workspace.data.repository.ChatRepository
import com.omnidev.workspace.domain.attachment.AttachmentProcessor
import com.omnidev.workspace.domain.engine.OmniMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * UI-to-service handoff for normal chat sends.
 *
 * It persists the user turn before starting the foreground service, so the service never depends
 * on transient Compose/ViewModel memory. Once persisted, closing the Activity cannot lose the task.
 */
object BackgroundChatCoordinator {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun send(context: Context, viewModel: ChatViewModel): Boolean {
        if (BuildConfig.TIER == "LITE") return false
        val state = viewModel.uiState.value
        val input = state.inputText.trim()
        if (input.isEmpty() || state.isProcessing || activeRun(state.currentSessionId) != null) return true

        val requestedMode = state.activeMode
        val resolvedMode = if (requestedMode == OmniMode.AUTO) viewModel.classifyTaskComplexity(input) else requestedMode
        val scopePath = state.targetContext
        val effectiveMode = when {
            resolvedMode == OmniMode.CHAT -> OmniMode.CHAT
            scopePath != null -> resolvedMode
            requestedMode == OmniMode.AUTO -> OmniMode.CHAT
            else -> return false
        }

        val pending = state.pendingAttachments.toList()
        val replyingTo = state.replyingTo
        val replyPrefix = replyingTo?.let { ref ->
            val senderLabel = if (ref.role == MessageRole.USER) "you" else "OmniDev"
            "[Replying to $senderLabel: \"${ref.content.take(150).replace("\n", " ")}\"]\n\n"
        }.orEmpty()
        val attachmentNote = if (pending.isNotEmpty()) {
            "\n\n[Attached files: ${pending.joinToString(", ") { it.displayName }}]"
        } else ""

        viewModel.onInputChanged("")
        pending.forEach { viewModel.removeAttachment(it.uri) }
        viewModel.clearReplyingTo()

        scope.launch {
            val appContext = context.applicationContext
            val db = OmniDevDatabase.getInstance(appContext)
            val repo = ChatRepository(db.chatSessionDao(), db.chatMessageDao())
            val sessionId = state.currentSessionId ?: repo.createSession(input.take(50).ifBlank { "New conversation" })
            val attachments = stageAttachmentMetadata(appContext, pending)
            val userMessage = ChatMessage(
                role = MessageRole.USER,
                content = replyPrefix + input + attachmentNote,
                replyToMessageId = replyingTo?.messageId,
                attachments = attachments
            )
            repo.saveMessage(sessionId, userMessage)
            repo.updateSessionRunStatus(sessionId, "Running")

            BackgroundAgentService.enqueue(
                context = appContext,
                sessionId = sessionId,
                userMessageId = userMessage.messageId,
                mode = effectiveMode,
                scopePath = scopePath.orEmpty(),
                disabledToolNames = state.chatSettings.disabledToolNames(),
                toolAccessMode = state.chatSettings.toolAccessMode.name
            )

            kotlinx.coroutines.withContext(Dispatchers.Main) {
                viewModel.loadSession(sessionId)
            }
        }
        return true
    }

    fun cancel(context: Context, sessionId: Long?): Boolean {
        val active = activeRun(sessionId) ?: return false
        BackgroundAgentService.cancel(context.applicationContext, active.runId)
        return true
    }

    fun activeRun(sessionId: Long?): BackgroundAgentRunBus.Snapshot? {
        if (sessionId == null) return null
        return BackgroundAgentRunBus.runs.value.values
            .filter { it.sessionId == sessionId && it.isActive }
            .maxByOrNull { it.updatedAtMs }
    }

    private suspend fun stageAttachmentMetadata(
        context: Context,
        pending: List<PendingAttachment>
    ): List<AttachmentMeta> {
        if (pending.isEmpty()) return emptyList()
        val processor = AttachmentProcessor(context.contentResolver)
        return pending.map { attachment ->
            val mime = processor.getMimeType(attachment.uri)
            AttachmentMeta(
                uri = attachment.uri.toString(),
                mimeType = mime,
                fileName = attachment.displayName,
                sizeBytes = querySize(context, attachment.uri),
                mediaType = when {
                    mime.startsWith("image/") -> AttachmentMediaType.IMAGE
                    mime.startsWith("video/") -> AttachmentMediaType.VIDEO
                    mime == "application/pdf" -> AttachmentMediaType.PDF
                    mime.startsWith("text/") || mime in setOf("application/json", "application/xml") -> AttachmentMediaType.TEXT
                    else -> AttachmentMediaType.UNKNOWN
                }
            )
        }
    }

    private fun querySize(context: Context, uri: Uri): Long {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    val index = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (cursor.moveToFirst() && index >= 0 && !cursor.isNull(index)) cursor.getLong(index) else 0L
                } ?: 0L
        }.getOrDefault(0L)
    }
}
