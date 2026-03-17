package com.omnidev.workspace.data.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * LLM-callable tool that provides read/write/clear access to the Android system clipboard.
 *
 * All clipboard operations are dispatched on the main thread, as required by
 * [ClipboardManager].
 *
 * @param context Android context used to obtain [ClipboardManager].
 */
class ClipboardTool(private val context: Context) {

    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "clipboard",
            description = "Read, write, or inspect the Android system clipboard. " +
                "Actions: " +
                "'read' — returns the current clipboard text content; " +
                "'write' — writes text to the clipboard (requires 'text' param); " +
                "'clear' — clears the clipboard; " +
                "'has_content' — checks whether the clipboard contains any text and shows a short preview; " +
                "'get_all_items' — returns every item stored in the current ClipData (clips may contain multiple items).",
            parameters = listOf(
                ToolParameter(
                    name = "action",
                    type = "string",
                    description = "One of: read, write, clear, has_content, get_all_items",
                    required = true
                ),
                ToolParameter(
                    name = "text",
                    type = "string",
                    description = "Text to write to the clipboard. Required only for the 'write' action.",
                    required = false
                )
            )
        )
    )

    suspend fun execute(arguments: Map<String, String>): ToolExecutionResult {
        val action = arguments["action"]
            ?: return ToolExecutionResult("Missing required argument: action", isError = true)

        return try {
            when (action.lowercase()) {
                "read" -> read()
                "write" -> {
                    val text = arguments["text"]
                        ?: return ToolExecutionResult("Missing required argument: text", isError = true)
                    write(text)
                }
                "clear" -> clear()
                "has_content" -> hasContent()
                "get_all_items" -> getAllItems()
                else -> ToolExecutionResult("Unknown clipboard action '$action'. Valid actions: read, write, clear, has_content, get_all_items", isError = true)
            }
        } catch (e: Exception) {
            ToolExecutionResult("Clipboard operation failed: ${e.message}", isError = true)
        }
    }

    private suspend fun read(): ToolExecutionResult = withContext(Dispatchers.Main) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        if (clip == null || clip.itemCount == 0) {
            return@withContext ToolExecutionResult("Clipboard is empty.")
        }
        val text = clip.getItemAt(0).coerceToText(context).toString()
        ToolExecutionResult(text)
    }

    private suspend fun write(text: String): ToolExecutionResult = withContext(Dispatchers.Main) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("omnidev_clipboard", text)
        clipboard.setPrimaryClip(clip)
        ToolExecutionResult("✅ Clipboard updated (${text.length} chars).")
    }

    private suspend fun clear(): ToolExecutionResult = withContext(Dispatchers.Main) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.clearPrimaryClip()
        ToolExecutionResult("✅ Clipboard cleared.")
    }

    private suspend fun hasContent(): ToolExecutionResult = withContext(Dispatchers.Main) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        if (clip == null || clip.itemCount == 0) {
            return@withContext ToolExecutionResult("Clipboard is empty.")
        }
        val preview = clip.getItemAt(0).coerceToText(context).toString().take(100)
        ToolExecutionResult("Clipboard has content: $preview")
    }

    private suspend fun getAllItems(): ToolExecutionResult = withContext(Dispatchers.Main) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        if (clip == null || clip.itemCount == 0) {
            return@withContext ToolExecutionResult("Clipboard is empty.")
        }
        val sb = StringBuilder("Clipboard items (${clip.itemCount}):\n")
        for (i in 0 until clip.itemCount) {
            val item = clip.getItemAt(i)
            val text = item.coerceToText(context).toString()
            sb.appendLine("  [$i] $text")
        }
        ToolExecutionResult(sb.toString().trimEnd())
    }
}
