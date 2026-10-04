package com.omnidev.workspace.data.voice

import java.io.File

/** Durable per-language installs. Recovery never discards the last working model. */
internal class VoiceModelFiles(private val root: File) {
    private val models = File(root, "offline-voice-models")
    fun directory(language: String): File { require(language in LANGUAGES); return File(models, language) }
    fun stage(language: String) = File(models, "$language.stage")
    fun backup(language: String) = File(models, "$language.backup")
    fun archive(language: String) = File(models, "$language.download.zip")
    fun valid(file: File) = File(file, "am/final.mdl").let { it.isFile && it.length() > 0 } &&
        File(file, "conf/model.conf").let { it.isFile && it.length() > 0 } &&
        runCatching { File(file, "omni-language").readText() in LANGUAGES }.getOrDefault(false)

    fun recover() = synchronized(LOCK) {
        check(models.isDirectory || models.mkdirs()) { "Could not create voice model storage." }
        for (language in LANGUAGES) {
            val target = directory(language); val old = backup(language)
            if (!valid(target) && valid(old)) {
                check(!target.exists() || target.deleteRecursively())
                check(old.renameTo(target)) { "Could not recover the installed voice model." }
            } else if (valid(target) && old.exists()) old.deleteRecursively()
        }
        val legacy = File(root, "offline-voice-model")
        if (valid(legacy)) {
            val language = File(legacy, "omni-language").readText()
            val target = directory(language)
            // A legacy install is retained if migration fails; readers can still use it.
            if (!target.exists()) legacy.renameTo(target)
        }
    }
    fun installed(language: String): File? = synchronized(LOCK) {
        recover()
        directory(language).takeIf(::valid) ?: File(root, "offline-voice-model").takeIf {
            valid(it) && File(it, "omni-language").readText() == language
        }
    }
    fun commit(language: String) = synchronized(LOCK) {
        val incoming = stage(language); val target = directory(language); val old = backup(language)
        check(valid(incoming) && File(incoming, "omni-language").readText() == language)
        check(!old.exists() || old.deleteRecursively())
        if (target.exists()) check(target.renameTo(old)) { "Could not preserve the current voice model." }
        if (!incoming.renameTo(target)) {
            if (old.exists()) check(old.renameTo(target)) { "Voice model recovery is pending. Reopen settings." }
            error("Could not install the voice model.")
        }
        old.deleteRecursively()
    }
    fun delete(language: String) = synchronized(LOCK) {
        recover()
        val target = directory(language)
        check(!target.exists() || target.deleteRecursively()) { "Could not remove the voice model." }
        val legacy = File(root, "offline-voice-model")
        if (valid(legacy) && File(legacy, "omni-language").readText() == language) {
            check(legacy.deleteRecursively()) { "Could not remove the legacy voice model." }
        }
        check(!backup(language).exists() || backup(language).deleteRecursively())
        archive(language).delete(); stage(language).deleteRecursively()
    }
    companion object { val LANGUAGES = listOf("en", "ar"); private val LOCK = Any() }
}
