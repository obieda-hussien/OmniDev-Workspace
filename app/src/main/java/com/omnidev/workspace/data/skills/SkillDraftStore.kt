package com.omnidev.workspace.data.skills

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.security.MessageDigest

/**
 * P15 quarantine. Generated skill instructions are never installed from a tool call.
 * Only a trusted user UI can promote the exact content hash it displayed.
 * Scripts, Python/Kotlin compilation and runtime tool registration are NOT enabled here.
 */
class SkillDraftStore(context: Context) {
    private val appContext = context.applicationContext
    private val manager = SkillManager(appContext)
    private val root = File(appContext.noBackupFilesDir, "agent-skill-drafts-v1")
    private val lock = Any()

    data class Draft(val name: String, val description: String, val markdown: String, val digest: String)

    fun stage(markdown: String): Result<Draft> = runCatching {
        synchronized(lock) {
            val parsed = SkillManager.parseDocument(markdown).getOrThrow()
            require(parsed.origin == SkillOrigin.USER)
            require(parsed.name !in SkillManager.BUILTIN_SKILLS) { "Cannot replace a built-in skill." }
            require(markdown.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Draft exceeds size limit." }
            if (!root.isDirectory && !root.mkdirs()) error("Skill draft storage unavailable.")
            val draft = Draft(parsed.name, parsed.description, markdown, sha(markdown))
            val target = file(parsed.name)
            var stream: java.io.FileOutputStream? = null
            try {
                stream = target.startWrite()
                stream.write(markdown.toByteArray(Charsets.UTF_8))
                target.finishWrite(stream)
            } catch (failure: Exception) {
                stream?.let(target::failWrite)
                throw failure
            }
            draft
        }
    }

    fun drafts(): List<Draft> = synchronized(lock) {
        root.listFiles().orEmpty().asSequence()
            .filter { it.isFile && it.name.endsWith(".md") }
            .mapNotNull { file -> runCatching { read(file.name.removeSuffix(".md")) }.getOrNull() }
            .sortedBy { it.name }.toList()
    }

    fun promoteReviewed(name: String, digest: String): Result<AgentSkill> = runCatching {
        synchronized(lock) {
            val candidate = read(name)
            require(candidate.digest == digest) { "Draft changed after review; inspect it again." }
            val installed = manager.installSkillMarkdown(candidate.markdown).getOrThrow()
            file(name).delete()
            installed
        }
    }

    fun discard(name: String): Boolean = synchronized(lock) {
        val target = file(name)
        val exists = target.baseFile.exists()
        target.delete()
        exists
    }

    private fun read(name: String): Draft {
        require(NAME.matches(name)) { "Invalid draft name" }
        val data = file(name).openRead().use { input ->
            val bytes = input.readBytes()
            require(bytes.size <= MAX_BYTES) { "Draft exceeds size limit." }
            bytes
        }
        val markdown = data.toString(Charsets.UTF_8)
        data.fill(0)
        val parsed = SkillManager.parseDocument(markdown).getOrThrow()
        require(parsed.name == name) { "Draft identity mismatch" }
        return Draft(name, parsed.description, markdown, sha(markdown))
    }

    private fun file(name: String): AtomicFile {
        require(NAME.matches(name)) { "Invalid draft name" }
        val target = File(root, "$name.md")
        require(target.canonicalPath.startsWith(root.canonicalPath + File.separator))
        return AtomicFile(target)
    }

    companion object {
        private const val MAX_BYTES = 128 * 1024
        private val NAME = Regex("[a-z0-9][a-z0-9-]{0,63}")
        private fun sha(text: String) = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
