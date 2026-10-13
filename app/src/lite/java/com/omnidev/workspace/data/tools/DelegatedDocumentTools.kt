package com.omnidev.workspace.data.tools

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.omnidev.workspace.data.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** Resolve paths by walking a user-selected SAF tree, never by converting it to a raw File. */
class DelegatedDocumentTools(private val context: Context, private val confirm: (suspend (String, String?) -> Boolean)?) {
    private val resolver = context.contentResolver
    private val supported = setOf("read_file", "read_file_lines", "create_file", "patch_file_content", "append_to_file", "delete_file")

    suspend fun execute(name: String, args: Map<String, String>): ToolExecutionResult? = withContext(Dispatchers.IO) {
        if (name !in supported) return@withContext null
        val settings = SettingsRepository(context)
        val selected = settings.observeTargetContextUri().first() ?: return@withContext null
        val tree = Uri.parse(selected)
        if (!DocumentsContract.isTreeUri(tree)) return@withContext ToolExecutionResult("Select a document folder first.", true)
        val grant = resolver.persistedUriPermissions.firstOrNull { it.uri == tree && it.isReadPermission }
            ?: return@withContext ToolExecutionResult("Folder authorization was revoked. Select it again.", true)
        val input = args["filePath"] ?: return@withContext ToolExecutionResult("Missing filePath", true)
        val selectedPath = settings.observeTargetContext().first()
        val relative = when {
            input.startsWith("content:") -> return@withContext ToolExecutionResult("Use a path relative to the selected folder.", true)
            input.startsWith("/") -> {
                val prefix = selectedPath?.trimEnd('/')?.plus("/")
                if (prefix == null || !input.startsWith(prefix)) return@withContext ToolExecutionResult("Path is outside the selected folder.", true)
                input.removePrefix(prefix)
            }
            else -> input
        }
        val segments = relative.split('/')
        if (segments.isEmpty() || segments.size > 32 || segments.any { it.isBlank() || it == "." || it == ".." || it.contains('\u0000') })
            return@withContext ToolExecutionResult("Invalid relative document path.", true)
        try {
            var parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            for (segment in segments.dropLast(1)) parent = child(tree, parent, segment)
                ?: return@withContext ToolExecutionResult("Parent folder does not exist.", true)
            var document = child(tree, parent, segments.last())
            val modifying = name !in setOf("read_file", "read_file_lines")
            if (modifying && !grant.isWritePermission) return@withContext ToolExecutionResult("Folder has read-only authorization.", true)
            if (modifying && confirm?.invoke("$name: $relative", args["content"] ?: args["replaceSnippet"]) != true)
                return@withContext ToolExecutionResult("File change denied. No modification was made.", true)
            when (name) {
                "create_file" -> {
                    if (document != null) return@withContext ToolExecutionResult("Document already exists; use patch_file_content.", true)
                    val content = args["content"] ?: return@withContext ToolExecutionResult("Missing content", true)
                    require(content.toByteArray().size <= MAX_BYTES) { "Content exceeds 2 MiB." }
                    document = DocumentsContract.createDocument(resolver, parent, "text/plain", segments.last())
                        ?: return@withContext ToolExecutionResult("Provider could not create the document.", true)
                    try { write(document, content) } catch (error: Exception) {
                        runCatching { DocumentsContract.deleteDocument(resolver, document) }; throw error
                    }
                    ToolExecutionResult("Created $relative through the selected document provider.")
                }
                "delete_file" -> ToolExecutionResult("Deleted: ${DocumentsContract.deleteDocument(resolver, requireNotNull(document))}")
                else -> {
                    val uri = requireNotNull(document) { "Document does not exist." }
                    val text = read(uri)
                    when (name) {
                        "patch_file_content" -> {
                            val search = args["searchSnippet"] ?: error("Missing searchSnippet")
                            val replacement = args["replaceSnippet"] ?: error("Missing replaceSnippet")
                            require(search.isNotEmpty() && text.indexOf(search) >= 0 && text.indexOf(search, text.indexOf(search) + search.length) < 0) { "Search snippet must match exactly once." }
                            write(uri, text.replaceFirst(search, replacement)); ToolExecutionResult("Patched $relative.")
                        }
                        "append_to_file" -> { write(uri, text + args["content"].orEmpty()); ToolExecutionResult("Appended to $relative.") }
                        "read_file_lines" -> {
                            val start = args["startLine"]?.toIntOrNull() ?: 1
                            val end = args["endLine"]?.toIntOrNull() ?: start + 499
                            require(start >= 1 && end >= start && end - start < 500)
                            ToolExecutionResult(text.lineSequence().drop(start - 1).take(end - start + 1).joinToString("\n"))
                        }
                        else -> ToolExecutionResult(text.take(36_000))
                    }
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (error: Exception) { ToolExecutionResult("Document operation failed: ${error.message}", true) }
    }

    private fun child(tree: Uri, parent: Uri, name: String): Uri? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parent))
        resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
            var count = 0
            while (cursor.moveToNext() && count++ < 10_000) if (cursor.getString(1) == name)
                return DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0))
        }
        return null
    }
    private fun read(uri: Uri): String = requireNotNull(resolver.openInputStream(uri)).use { input ->
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) { val n = input.read(buffer); if (n < 0) break; require(out.size() + n <= MAX_BYTES) { "Document exceeds 2 MiB." }; out.write(buffer, 0, n) }
        out.toString(Charsets.UTF_8.name())
    }
    private fun write(uri: Uri, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "Content exceeds 2 MiB." }
        requireNotNull(resolver.openOutputStream(uri, "wt")).use { it.write(bytes) }
    }
    companion object { private const val MAX_BYTES = 2 * 1024 * 1024 }
}

