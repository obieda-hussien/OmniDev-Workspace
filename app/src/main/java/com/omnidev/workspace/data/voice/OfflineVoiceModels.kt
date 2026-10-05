package com.omnidev.workspace.data.voice

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import org.vosk.LibVosk
import org.vosk.Model

/** User-triggered, hash-pinned installs. Downloads survive backgrounding and resume after interruption. */
class OfflineVoiceModels(context: Context) {
    private val context = context.applicationContext
    enum class Preset(val language: String, val fileName: String, val sha256: String, val sizeLabel: String) {
        ENGLISH("en", "vosk-model-small-en-us-0.15.zip", "30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498", "English · 40 MB download / 71 MB installed"),
        ARABIC("ar", "vosk-model-ar-mgb2-0.4.zip", "357469ae1bb4d7a3810c9cd6b86d33bc135898dfc134e6df8bc2ddd28c5fe77a", "Arabic · 318 MB download / 665 MiB installed")
    }
    private val files = VoiceModelFiles(this.context.noBackupFilesDir)
    private val prefs = this.context.getSharedPreferences("offline-voice-settings", Context.MODE_PRIVATE)
    fun installedLanguages() = VoiceModelFiles.LANGUAGES.filter { files.installed(it) != null }
    fun ready() = runCatching { installedLanguages().isNotEmpty() }.getOrDefault(false)
    fun language(): String {
        val installed = installedLanguages()
        return prefs.getString("language", null)?.takeIf { it in installed } ?: installed.firstOrNull() ?: "en"
    }
    fun select(preset: Preset) {
        check(files.installed(preset.language) != null) { "Install this language first." }
        check(prefs.edit().putString("language", preset.language).commit()) { "Could not save the selected language." }
    }
    suspend fun install(preset: Preset, progress: (Long) -> Unit, phase: (String) -> Unit = {}) = withContext(Dispatchers.IO) {
        check(WakePreferences(context).userCanConfigure()) { "Unlock the phone to start a download." }
        modelLock.withLock {
            files.recover()
            if (files.installed(preset.language) != null) { select(preset); return@withLock }
            val archive = files.archive(preset.language)
            val required = (if (preset == Preset.ARABIC) 318L + 665L else 40L + 71L) * 1024 * 1024 - archive.length() + 32L * 1024 * 1024
            check(context.noBackupFilesDir.usableSpace >= required.coerceAtLeast(0)) {
                "Not enough storage. Free about ${required.coerceAtLeast(0) / (1024 * 1024)} MB before downloading this language. Existing models are retained."
            }
            val stage = files.stage(preset.language)
            check(!stage.exists() || stage.deleteRecursively())
            check(stage.mkdirs())
            try {
                phase("Downloading ${preset.name.lowercase()} model…")
                VoiceModelDownload.fetch("https://alphacephei.com/vosk/models/${preset.fileName}", archive, progress)
                phase("Verifying download…")
                val digest = MessageDigest.getInstance("SHA-256")
                archive.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer); if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }
                if (digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } != preset.sha256) {
                    archive.delete(); error("Download verification failed. Tap Download to fetch a fresh copy.")
                }
                phase("Installing model…")
                ZipFile(archive).use { zip ->
                    var total = 0L; var entries = 0
                    val prefix = preset.fileName.removeSuffix(".zip") + "/"
                    for (entry in zip.entries().asSequence()) {
                        currentCoroutineContext().ensureActive()
                        require(++entries <= 500 && entry.name.startsWith(prefix)) { "Unexpected model archive." }
                        val relative = entry.name.removePrefix(prefix)
                        require(!relative.startsWith('/') && '\\' !in relative && relative.split('/').none { it == ".." } && relative.length <= 240)
                        if (entry.isDirectory) continue
                        val target = File(stage, relative)
                        require(target.canonicalPath.startsWith(stage.canonicalPath + File.separator))
                        check(target.parentFile?.isDirectory == true || target.parentFile?.mkdirs() == true)
                        zip.getInputStream(entry).use { input -> target.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024); var size = 0L
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer); if (count < 0) break
                                size += count; total += count
                                require(size <= 512L * 1024 * 1024 && total <= 1024L * 1024 * 1024)
                                output.write(buffer, 0, count)
                            }
                        } }
                    }
                }
                File(stage, "omni-language").writeText(preset.language)
                require(files.valid(stage)) { "The model archive is incomplete." }
                // Opening Vosk before commit catches unusable downloads without touching installed models.
                voiceNativeCall {
                    LibVosk.vosk_set_log_level(-1)
                    Model(stage.absolutePath).use { }
                }
                currentCoroutineContext().ensureActive()
                files.commit(preset.language)
                select(preset)
                archive.delete()
            } finally { stage.deleteRecursively() }
        }
    }
    suspend fun delete(preset: Preset? = null) = withContext(Dispatchers.IO) { modelLock.withLock {
        val language = preset?.language ?: language()
        files.delete(language)
    } }
    suspend fun <T> withModel(block: suspend (Model) -> T): T = withContext(Dispatchers.IO) { modelLock.withLock {
        val directory = files.installed(language()) ?: error("Install an offline voice model from Voice activation first.")
        voiceNativeCall {
            LibVosk.vosk_set_log_level(-1)
            Model(directory.absolutePath).use { block(it) }
        }
    } }
    companion object { private val modelLock = Mutex() }
}
