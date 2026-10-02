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
    private var generation = 0

    fun show() { mutable.update { it.copy(visible = true, message = null) } }
    fun hide() {
        generation++
        mutable.update { it.copy(visible = false, screenshot = null, attachment = null,
            selecting = false, listening = false, saving = false) }
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
                if (current.input.isNotBlank()) chat.onInputChanged(current.input)
                file?.let { chat.addAttachments(listOf(Uri.fromFile(it)), listOf("Selected screen.png")) }
                mutable.update { it.copy(input = "", attachment = null) }
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
        val directory = File(context.filesDir, "assistant_images")
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
                if (epoch != generation || !chat.sendAssistantMessage(prompt, image)) {
                    file?.delete()
                } else {
                    mutable.update { it.copy(input = "", attachment = null) }
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                file?.delete()
                throw error
            } catch (error: Exception) {
                file?.delete()
                message("Could not attach the screen image. Please try again.")
            } finally {
                if (epoch == generation) mutable.update { it.copy(saving = false) }
            }
        }
    }
    fun destroy() { hide(); scope.cancel() }
}
