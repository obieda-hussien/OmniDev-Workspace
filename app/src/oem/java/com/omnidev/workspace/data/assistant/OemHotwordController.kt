package com.omnidev.workspace.data.assistant

import android.service.voice.VoiceInteractionService
import com.omnidev.workspace.data.voice.WakePreferences
import kotlinx.coroutines.flow.MutableStateFlow

/** Optional ROM system-API adapter. No hidden platform classes enter the normal APK's SDK graph. */
object OemHotwordController {
    val status = MutableStateFlow("Hardware wake adapter has not been initialized")
    private var host: VoiceInteractionService? = null
    private var adapter: Any? = null
    private var configuredPhrase: String? = null
    private var generation = 0
    fun ready(service: VoiceInteractionService) { host = service; refresh() }
    fun refresh(): Boolean {
        val service = host ?: return false
        val prefs = WakePreferences(service)
        if (adapter != null && configuredPhrase != prefs.phrase) { releaseAdapter(); configuredPhrase = null }
        if (adapter == null) {
            val epoch = ++generation
            configuredPhrase = prefs.phrase
            try {
                val type = Class.forName("com.omnidev.hardware.OmniHotwordAdapter")
                adapter = type.getMethod("create", VoiceInteractionService::class.java, String::class.java, String::class.java,
                    Runnable::class.java, java.util.function.Consumer::class.java).invoke(null, service, prefs.phrase, "en-US",
                    Runnable { if (epoch == generation && WakePreferences(service).allowedNow()) OmniVoiceInteractionService.wake(service) },
                    java.util.function.Consumer<String> { if (epoch == generation) status.value = it })
            } catch (error: Exception) { status.value = "ROM hardware adapter unavailable; use trained offline software listening"; return false }
        }
        return try {
            val current = adapter ?: return false
            if (!prefs.enabled) { current.javaClass.getMethod("stopRecognition").invoke(current); false }
            else current.javaClass.getMethod("startRecognition").invoke(current) == true
        } catch (error: Exception) { status.value = "Hardware recognition unavailable"; false }
    }
    private fun releaseAdapter() {
        generation++
        adapter?.let { runCatching { it.javaClass.getMethod("close").invoke(it) } }
        adapter = null
    }
    fun stop() { releaseAdapter(); host = null }
}
