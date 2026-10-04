package com.omnidev.workspace.data.voice

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile
import javax.net.ssl.HttpsURLConnection
import org.vosk.LibVosk
import org.vosk.Model

/** User-triggered, hash-pinned model installation. Inference has no network fallback. */
class OfflineVoiceModels(private val context: Context) {
    enum class Preset(val language: String, val fileName: String, val sha256: String, val sizeLabel: String) {
        ENGLISH("en", "vosk-model-small-en-us-0.15.zip", "30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498", "English · 40 MB download / 71 MB installed"),
        ARABIC("ar", "vosk-model-ar-mgb2-0.4.zip", "357469ae1bb4d7a3810c9cd6b86d33bc135898dfc134e6df8bc2ddd28c5fe77a", "Arabic · 318 MB download / 665 MiB installed")
    }
    private val directory get() = File(context.noBackupFilesDir, "offline-voice-model")
    fun ready() = File(directory, "omni-language").isFile && File(directory, "am/final.mdl").isFile
    fun language() = runCatching { File(directory, "omni-language").readText().takeIf { it in setOf("en", "ar") } }.getOrNull() ?: "en"
    suspend fun install(preset: Preset, progress: (Long) -> Unit) = withContext(Dispatchers.IO) {
        check(WakePreferences(context).userCanConfigure())
        modelLock.withLock {
            val root = context.noBackupFilesDir
            val archive = File(root, "voice-download-${UUID.randomUUID()}.zip")
            val stage = File(root, "voice-stage-${UUID.randomUUID()}").apply { mkdirs() }
            val backup = File(root, "voice-backup-${UUID.randomUUID()}")
            try {
                val connection = URL("https://alphacephei.com/vosk/models/${preset.fileName}").openConnection() as HttpsURLConnection
                connection.connectTimeout = 20_000; connection.readTimeout = 20_000; connection.instanceFollowRedirects = false
                val digest = MessageDigest.getInstance("SHA-256")
                try {
                    require(connection.responseCode == 200) { "Model download unavailable. Try again later." }
                    connection.inputStream.use { input -> archive.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024); var total = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            check(WakePreferences(context).userCanConfigure())
                            val count = input.read(buffer); if (count < 0) break
                            total += count; require(total <= 400L * 1024 * 1024)
                            output.write(buffer, 0, count); digest.update(buffer, 0, count); progress(total)
                        }
                    } }
                } finally { connection.disconnect() }
                require(digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == preset.sha256) { "Model integrity verification failed." }
                ZipFile(archive).use { zip ->
                    var total = 0L; var entries = 0
                    val prefix = preset.fileName.removeSuffix(".zip") + "/"
                    for (entry in zip.entries().asSequence()) {
                        currentCoroutineContext().ensureActive()
                        require(++entries <= 500 && entry.name.startsWith(prefix))
                        val relative = entry.name.removePrefix(prefix)
                        require(!relative.startsWith('/') && '\\' !in relative && relative.split('/').none { it == ".." } && relative.length <= 240)
                        if (entry.isDirectory) continue
                        val target = File(stage, relative)
                        require(target.canonicalPath.startsWith(stage.canonicalPath + File.separator))
                        target.parentFile?.mkdirs()
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
                require(File(stage, "am/final.mdl").isFile && File(stage, "conf/model.conf").isFile)
                File(stage, "omni-language").writeText(preset.language)
                currentCoroutineContext().ensureActive()
                check(WakePreferences(context).userCanConfigure())
                if (directory.exists()) check(directory.renameTo(backup))
                if (!stage.renameTo(directory)) { if (backup.exists()) backup.renameTo(directory); error("Could not install the voice model.") }
                backup.deleteRecursively()
            } finally { archive.delete(); stage.deleteRecursively() }
        }
    }
    suspend fun delete() = withContext(Dispatchers.IO) { modelLock.withLock { directory.deleteRecursively() } }
    suspend fun <T> withModel(block: suspend (Model) -> T): T = withContext(Dispatchers.IO) { modelLock.withLock {
        check(ready()) { "Install an offline voice model from Hi Omni settings first." }
        LibVosk.vosk_set_log_level(-1)
        Model(directory.absolutePath).use { block(it) }
    } }
    companion object { private val modelLock = Mutex() }
}
