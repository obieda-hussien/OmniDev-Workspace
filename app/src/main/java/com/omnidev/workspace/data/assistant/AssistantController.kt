package com.omnidev.workspace.data.assistant

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.omnidev.workspace.ui.assistant.ScreenSelection
import com.omnidev.workspace.ui.chat.ChatViewModel
import com.omnidev.workspace.ui.chat.PendingAttachment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class AssistantScreenState(
    val input: String = "",
    val files: List<PendingAttachment> = emptyList(),
    val screenshot: Bitmap? = null,
    val attachment: Bitmap? = null,
    val selecting: Boolean = false,
    val saving: Boolean = false,
    val message: String? = null,
    val listening: Boolean = false,
    val visible: Boolean = false
)

/** Screen pixels stay in memory until the user explicitly submits their chosen image. */
class AssistantController(private val context: Context, val chat: ChatViewModel) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(AssistantScreenState())
    val state = mutable.asStateFlow()
    val flavor get() = AssistantFlavorPolicy(com.omnidev.workspace.core.policy.TierPolicyHolder.current)
    private var generation = 0

    fun show() { mutable.update { it.copy(visible = true) } }
    fun hide() { mutable.update { it.copy(visible = false, selecting = false, listening = false) } }
    /** Cancels the active run but preserves its persisted messages in ordinary chat history. */
    fun close() {
        generation++
        val unsent = mutable.value.files
        scope.launch(Dispatchers.IO) { unsent.forEach { File(it.uri.path.orEmpty()).delete() } }
        chat.uiState.value.pendingConfirmation?.onDeny?.invoke()
        chat.clearConfirmation()
        chat.cancelCurrentRun()
        chat.newSession()
        mutable.value = AssistantScreenState()
    }
    fun removeFile(uri: Uri) {
        val staged = mutable.value.files.any { it.uri == uri }
        mutable.update { it.copy(files = it.files.filterNot { file -> file.uri == uri }) }
        if (staged) scope.launch(Dispatchers.IO) { File(uri.path.orEmpty()).delete() }
    }
    fun addFiles(uris: List<Uri>) {
        val epoch = generation
        mutable.update { it.copy(saving = true) }
        scope.launch {
            val created = mutableListOf<File>()
            try {
                val available = 5 - mutable.value.files.size - if (mutable.value.attachment != null) 1 else 0
                require(uris.size <= available) { "Attach up to five files per message." }
                var total = mutable.value.files.sumOf { File(it.uri.path.orEmpty()).length() }
                val files = withContext(Dispatchers.IO) {
                    uris.map { uri ->
                        val name = if (uri.scheme == "file") File(uri.path.orEmpty()).name else
                            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                                if (it.moveToFirst()) it.getString(0) else null
                            } ?: "Attachment"
                        val directory = File(context.filesDir, "assistant_workspace/attachments").apply { mkdirs() }
                        val target = File(directory, "${UUID.randomUUID()}_${name.replace(Regex("[^\\p{L}\\p{N}._-]"), "_").take(120)}")
                        created += target
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            target.outputStream().use { output ->
                                val buffer = ByteArray(8192); var size = 0L
                                while (true) {
                                    val count = input.read(buffer); if (count < 0) break
                                    size += count; total += count
                                    require(size <= 10L * 1024 * 1024 && total <= 15L * 1024 * 1024) { "Files must be at most 10 MB each and 15 MB combined. Use a file path for larger media." }
                                    output.write(buffer, 0, count)
                                }
                            }
                        } ?: error("Could not open $name")
                        PendingAttachment(Uri.fromFile(target), name)
                    }
                }
                if (epoch == generation) mutable.update { it.copy(files = it.files + files, message = null) }
                else created.forEach { it.delete() }
            } catch (error: kotlinx.coroutines.CancellationException) { created.forEach { it.delete() }; throw error }
            catch (error: Exception) { created.forEach { it.delete() }; if (epoch == generation) message(error.message ?: "Could not attach files.") }
            finally { if (epoch == generation) mutable.update { it.copy(saving = false) } }
        }
    }
    fun input(value: String) { mutable.update { it.copy(input = value) } }
    fun message(value: String?) { mutable.update { it.copy(message = value) } }
    fun listening(value: Boolean) { mutable.update { it.copy(listening = value) } }
    fun screenshot(bitmap: Bitmap?) {
        if (!mutable.value.visible) return
        mutable.update { it.copy(screenshot = bitmap,
            message = if (bitmap == null) "Screen access is unavailable. Allow screenshots in Android's assistant settings, then invoke Omni again. Protected screens cannot be captured." else null) }
    }
    fun useScreen(select: Boolean) {
        if (mutable.value.screenshot == null) {
            message("No screen image available. Choose Omni as your default digital assistant, allow screen access, then hold Home again.")
            return
        }
        mutable.update { it.copy(selecting = select,
            attachment = if (select) it.attachment else it.screenshot, message = null) }
    }
    fun cancelSelection() { mutable.update { it.copy(selecting = false) } }
    fun select(crop: ScreenSelection.Crop) {
        val bitmap = mutable.value.screenshot ?: return
        val selected = Bitmap.createBitmap(bitmap, crop.left, crop.top, crop.width, crop.height)
        mutable.update { it.copy(attachment = selected, selecting = false, message = null) }
    }
    fun removeImage() { mutable.update { it.copy(attachment = null) } }

    /** Explicit expand also transfers an unsent screen question into the full composer. */
    fun openConversation(open: () -> Unit) {
        val current = mutable.value
        if (current.saving) return
        val epoch = generation
        mutable.update { it.copy(saving = true) }
        scope.launch {
            var file: File? = null
            try {
                current.attachment?.let { file = persistImage(it) }
                if (epoch != generation) { file?.delete(); return@launch }
                val fullChat = com.omnidev.workspace.WorkspaceChatRuntime.get(context)
                chat.uiState.value.currentSessionId?.let(fullChat::loadSession)
                if (current.input.isNotBlank()) fullChat.onInputChanged(current.input)
                val attachments = current.files + listOfNotNull(file?.let { PendingAttachment(Uri.fromFile(it), "Selected screen.png") })
                fullChat.addAttachments(attachments.map { it.uri }, attachments.map { it.displayName })
                mutable.update { it.copy(input = "", attachment = null, files = emptyList()) }
                open()
            } catch (error: kotlinx.coroutines.CancellationException) {
                file?.delete(); throw error
            } catch (error: Exception) {
                file?.delete(); message("Could not open the conversation. Please try again.")
            } finally {
                if (epoch == generation) mutable.update { it.copy(saving = false) }
            }
        }
    }

    private suspend fun persistImage(bitmap: Bitmap): File {
        val directory = File(context.filesDir, "assistant_workspace/attachments")
        val target = File(directory, "screen_${UUID.randomUUID()}.png")
        return try {
            withContext(Dispatchers.IO) {
                directory.mkdirs()
                target.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                target
            }
        } catch (error: Exception) { target.delete(); throw error }
    }

    fun send(prompt: String = mutable.value.input) {
        val current = mutable.value
        if (current.saving || chat.uiState.value.isProcessing || prompt.isBlank()) return
        val epoch = generation
        mutable.update { it.copy(saving = true, message = null) }
        scope.launch {
            var file: File? = null
            try {
                val image = current.attachment?.let { bitmap ->
                    file = persistImage(bitmap)
                    PendingAttachment(Uri.fromFile(file!!), "Selected screen.png")
                }
                val attachments = current.files + listOfNotNull(image)
                require(attachments.size <= 5 && attachments.sumOf { File(it.uri.path.orEmpty()).length() } <= 15L * 1024 * 1024 && attachments.all { File(it.uri.path.orEmpty()).length() <= 10L * 1024 * 1024 }) { "Attach up to five files, at most 10 MB each and 15 MB combined." }
                if (epoch != generation || !chat.sendAssistantMessage(prompt, attachments, AssistantRuntime.targetPackage?.let { "Foreground app package: $it." }.orEmpty())) {
                    file?.delete()
                } else {
                    mutable.update { it.copy(input = "", attachment = null, files = emptyList()) }
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                file?.delete()
                throw error
            } catch (error: Exception) {
                file?.delete()
                message(error.message ?: "Could not attach the screen image. Please try again.")
            } finally {
                if (epoch == generation) mutable.update { it.copy(saving = false) }
            }
        }
    }
    fun destroy() { close(); scope.cancel() }
}
