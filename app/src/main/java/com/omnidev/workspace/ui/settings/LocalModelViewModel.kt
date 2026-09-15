package com.omnidev.workspace.ui.settings

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.omnidev.workspace.data.localllm.LocalEngineHolder
import com.omnidev.workspace.data.repository.SettingsRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class LocalModelUiState(
    val initializing: Boolean = true, val busy: Boolean = false,
    val selectedUri: String? = null, val selectedName: String? = null, val loadedName: String? = null,
    val contextText: String = "", val threadsText: String = "", val temperature: Float = 0.7f,
    val availableRamMb: Long = 0, val message: String? = null, val error: String? = null
)

internal fun validateLocalSettings(context: String, threads: String, cores: Int): String? = when {
    context.isNotBlank() && context.toIntOrNull()?.let { it in 512..32768 } != true ->
        "Context must be between 512 and 32768 tokens, or blank for Auto."
    threads.isNotBlank() && threads.toIntOrNull()?.let { it in 1..minOf(cores, 8) } != true ->
        "Threads must be between 1 and ${minOf(cores, 8)}, or blank for Auto."
    else -> null
}

class LocalModelViewModel(private val context: Context, private val settings: SettingsRepository) : ViewModel() {
    private val engine = LocalEngineHolder.llamaCppEngine
    private val mutableState = MutableStateFlow(LocalModelUiState())
    val state = mutableState.asStateFlow()
    init {
        viewModelScope.launch { engine.modelName.collect { name -> mutableState.update { it.copy(loadedName = name) } } }
        operation {
            val uri = settings.observeLocalModelUri().first()
            val name = settings.observeLocalModelName().first()
            val ctx = settings.observeLocalModelContextSize().first()
            val threads = settings.observeLocalModelThreads().first()
            val temp = settings.observeLocalModelTemperature().first()
            val ram = withContext(Dispatchers.IO) {
                val info = ActivityManager.MemoryInfo()
                (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
                info.availMem / 1_048_576
            }
            mutableState.update { it.copy(selectedUri = uri, selectedName = name,
                contextText = ctx?.toString().orEmpty(), threadsText = threads?.toString().orEmpty(),
                temperature = temp ?: 0.7f, availableRamMb = ram) }
        }
    }
    fun editContext(value: String) { if (!state.value.busy) mutableState.update { it.copy(contextText = value, error = null) } }
    fun editThreads(value: String) { if (!state.value.busy) mutableState.update { it.copy(threadsText = value, error = null) } }
    fun editTemperature(value: Float) { if (!state.value.busy) mutableState.update { it.copy(temperature = value) } }
    fun select(uri: Uri) = operation {
        val name = withContext(Dispatchers.IO) {
            val displayName = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            } ?: uri.lastPathSegment.orEmpty()
            require(displayName.endsWith(".gguf", ignoreCase = true)) { "Choose a GGUF model file." }
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            displayName
        }
        settings.setLocalModelUri(uri.toString())
        settings.setLocalModelName(name)
        mutableState.update { it.copy(selectedUri = uri.toString(), selectedName = name, message = "File selected. Tap Load model when ready.") }
    }
    private suspend fun saveSettings() {
        val snapshot = state.value
        validateLocalSettings(snapshot.contextText, snapshot.threadsText, Runtime.getRuntime().availableProcessors())?.let {
            throw IllegalArgumentException(it)
        }
        settings.setLocalModelContextSize(snapshot.contextText.toIntOrNull())
        settings.setLocalModelThreads(snapshot.threadsText.toIntOrNull())
        settings.setLocalModelTemperature(snapshot.temperature)
    }
    fun save() = operation {
        saveSettings()
        mutableState.update { it.copy(message = "Settings saved. Context and threads apply on the next load.") }
    }
    fun load() = operation {
        val uri = state.value.selectedUri ?: throw IllegalStateException("Choose a model file first.")
        saveSettings()
        val snapshot = state.value
        engine.loadModel(context, Uri.parse(uri), snapshot.contextText.toIntOrNull(), snapshot.threadsText.toIntOrNull()).getOrThrow()
        mutableState.update { it.copy(message = "Model ready for local chat.") }
    }
    fun unload() = operation {
        withContext(Dispatchers.IO) { engine.unloadModel() }
        mutableState.update { it.copy(message = "Model unloaded. The selected file is kept for next time.") }
    }
    private fun operation(block: suspend () -> Unit) {
        if (state.value.busy) return
        mutableState.update { it.copy(busy = true, error = null, message = null) }
        viewModelScope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutableState.update { it.copy(error = error.message ?: "Operation failed") } }
            finally { mutableState.update { it.copy(busy = false, initializing = false) } }
        }
    }
    companion object {
        fun factory(context: Context, settings: SettingsRepository) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                LocalModelViewModel(context.applicationContext, settings) as T
        }
    }
}
