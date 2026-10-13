package com.omnidev.workspace.data.tools

import android.util.AtomicFile
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** No shell, Python, privileged router, or scope-bypass implementation in Lite. */
class FileToolManager(godModeEnabled: Boolean = false) : ToolManager {
    var godModeEnabled: Boolean
        get() = false
        set(value) { }
    @Volatile var confirmationGate: (suspend (String, String?) -> Boolean)? = null
    override fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition("read_file", "Read an explicitly selected document or private workspace file.", path()),
        ToolDefinition("read_file_lines", "Read at most 500 authorized lines.", path() + listOf(ToolParameter("startLine", "integer", "First line", true), ToolParameter("endLine", "integer", "Last line", true))),
        ToolDefinition("create_file", "Create a text file after confirmation.", path() + listOf(ToolParameter("content", "string", "Contents", true))),
        ToolDefinition("patch_file_content", "Replace one exact occurrence after confirmation.", path() + listOf(ToolParameter("searchSnippet", "string", "Old text", true), ToolParameter("replaceSnippet", "string", "Replacement", true))),
        ToolDefinition("append_to_file", "Append text after confirmation.", path() + listOf(ToolParameter("content", "string", "Text", true))),
        ToolDefinition("delete_file", "Delete one authorized file after confirmation.", path()),
        ToolDefinition("multi_read", "Read up to 20 authorized files.", listOf(ToolParameter("filePaths", "string", "JSON array of paths", true)))
    )
    override suspend fun executeTool(name: String, arguments: Map<String, String>, scopePath: String?): ToolExecutionResult = withContext(Dispatchers.IO) {
        if (name !in getToolDefinitions().map { it.name }) return@withContext ToolExecutionResult("File operation is not included in Lite.", true)
        val context = com.omnidev.workspace.OmniDevApp.instance
        if (name != "multi_read") DelegatedDocumentTools(context, confirmationGate).execute(name, arguments)?.let { return@withContext it }
        val workspace = File(context.filesDir, "assistant_workspace").apply { mkdirs() }.canonicalFile
        val root = scopePath?.let { File(it).canonicalFile }?.takeIf { inside(it, workspace) } ?: workspace
        try {
            if (name == "multi_read") {
                val paths = org.json.JSONArray(arguments["filePaths"] ?: error("Missing filePaths"))
                require(paths.length() in 1..20)
                val results = (0 until paths.length()).map { executeTool("read_file", mapOf("filePath" to paths.getString(it)), scopePath) }
                return@withContext ToolExecutionResult(results.joinToString("\n\n") { it.output }, results.any { it.isError })
            }
            val input = arguments["filePath"] ?: error("Missing filePath")
            require(input.length <= 1024 && '\u0000' !in input)
            val file = (if (File(input).isAbsolute) File(input) else File(root, input)).canonicalFile
            require(file != root && inside(file, root)) { "Path is outside the authorized workspace." }
            if (name !in setOf("read_file", "read_file_lines") && confirmationGate?.invoke("$name: ${file.relativeTo(root).path}", arguments["content"] ?: arguments["replaceSnippet"]) != true)
                return@withContext ToolExecutionResult("File change denied. No modification was made.", true)
            when (name) {
                "create_file" -> {
                    require(!file.exists()) { "File already exists." }
                    require(file.parentFile.isDirectory || file.parentFile.mkdirs())
                    write(file, arguments["content"] ?: error("Missing content")); ToolExecutionResult("Created ${file.relativeTo(root).path}")
                }
                "delete_file" -> {
                    require(file.isFile) { "Only a single existing file can be deleted." }
                    check(file.delete()) { "Deletion failed." }; ToolExecutionResult("Deleted ${file.relativeTo(root).path}")
                }
                else -> {
                    val text = read(file)
                    when (name) {
                        "patch_file_content" -> {
                            val old = arguments["searchSnippet"] ?: error("Missing searchSnippet")
                            require(old.isNotEmpty() && text.indexOf(old) >= 0 && text.indexOf(old, text.indexOf(old) + old.length) < 0) { "Snippet must match exactly once." }
                            write(file, text.replaceFirst(old, arguments["replaceSnippet"] ?: error("Missing replacement"))); ToolExecutionResult("Patched ${file.relativeTo(root).path}")
                        }
                        "append_to_file" -> { write(file, text + (arguments["content"] ?: error("Missing content"))); ToolExecutionResult("Appended ${file.relativeTo(root).path}") }
                        "read_file_lines" -> {
                            val start = arguments["startLine"]?.toIntOrNull() ?: 1
                            val end = arguments["endLine"]?.toIntOrNull() ?: start + 499
                            require(start >= 1 && end >= start && end - start < 500)
                            ToolExecutionResult(text.lineSequence().drop(start - 1).take(end - start + 1).joinToString("\n"))
                        }
                        else -> ToolExecutionResult(text.take(36_000))
                    }
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (error: Exception) { ToolExecutionResult("File operation failed: ${error.message}", true) }
    }
    private fun read(file: File): String {
        require(file.isFile && file.length() <= MAX_BYTES) { "File is missing or exceeds 2 MiB." }
        return file.inputStream().use { stream ->
            val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) { val n = stream.read(buffer); if (n < 0) break; require(output.size() + n <= MAX_BYTES); output.write(buffer, 0, n) }
            output.toString(Charsets.UTF_8.name())
        }
    }
    private fun write(file: File, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8); require(bytes.size <= MAX_BYTES)
        val atomic = AtomicFile(file); val stream = atomic.startWrite()
        try { stream.write(bytes); atomic.finishWrite(stream) } catch (error: Exception) { atomic.failWrite(stream); throw error }
    }
    private fun inside(file: File, root: File) = file == root || file.path.startsWith(root.path + File.separator)
    private fun path() = listOf(ToolParameter("filePath", "string", "Relative path in the selected folder or workspace", true))
    companion object { private const val MAX_BYTES = 2 * 1024 * 1024 }
}
