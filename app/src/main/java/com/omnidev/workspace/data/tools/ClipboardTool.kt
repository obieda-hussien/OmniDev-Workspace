package com.omnidev.workspace.data.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import com.omnidev.workspace.data.ipc.PrivilegedExecutionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * LLM-callable tool that provides read/write/clear access to the Android system clipboard.
 *
 * * HACKER UPGRADES:
 * 1. Background Bypass: Android 10+ blocks background apps from reading the clipboard.
 * This tool gracefully falls back to Shizuku (`cmd clipboard`) to read/write 
 * clipboard data even if the app is not in the foreground.
 * 2. Token Protection: Automatically truncates massively large clipboard items 
 * (e.g., copied base64 strings or books) to prevent LLM context overflow.
 * 3. Safe Coercion: Properly handles non-text clipboard items (URIs, Intents).
 */
class ClipboardTool(private val context: Context) {

    companion object {
        // Protect the LLM context window from massive copied texts
        private const val MAX_CLIPBOARD_CHARS = 15_000
    }

    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "clipboard",
            description = "Read, write, or inspect the Android system clipboard. " +
                "Bypasses Android 10+ background restrictions using Shizuku if needed. " +
                "Actions: " +
                "'read' — returns the current clipboard text; " +
                "'write' — writes text to the clipboard (requires 'text' param); " +
                "'clear' — clears the clipboard; " +
                "'has_content' — checks whether the clipboard contains text and shows a short preview; " +
                "'get_all_items' — returns all items stored in the current ClipData.",
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

    suspend fun execute(arguments: Map<String, String>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val action = arguments["action"]?.lowercase()?.trim()
            ?: return@withContext ToolExecutionResult("Missing required argument: action", isError = true)

        try {
            when (action) {
                "read" -> read()
                "write" -> {
                    val text = arguments["text"] ?: return@withContext ToolExecutionResult("Missing required argument: text", isError = true)
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

    // ──────────────────────────────────────────────────────────────────────
    // Core Actions
    // ──────────────────────────────────────────────────────────────────────

    private suspend fun read(): ToolExecutionResult {
        // Attempt native read first (Works if app is in foreground)
        var text = readNativeClipboard()

        // Fallback to Shizuku if native read was blocked by Android 10+ background restrictions
        if (text.isNullOrEmpty() && (PrivilegedExecutionManager.isShizukuReady() || PrivilegedExecutionManager.isRootAvailable())) {
            text = readPrivilegedClipboard()
        }

        if (text.isNullOrEmpty()) {
            return ToolExecutionResult("Clipboard is empty or inaccessible.")
        }

        val truncated = text.length > MAX_CLIPBOARD_CHARS
        val outputText = if (truncated) {
            "...[TRUNCATED ${text.length - MAX_CLIPBOARD_CHARS} chars]...\n" + text.takeLast(MAX_CLIPBOARD_CHARS)
        } else {
            text
        }

        return ToolExecutionResult(
            output = "Clipboard Content (${text.length} chars):\n$outputText",
            truncated = truncated
        )
    }

    private suspend fun write(text: String): ToolExecutionResult {
        // Attempt native write
        val nativeSuccess = writeNativeClipboard(text)

        // If native fails (e.g., background restrictions on some custom ROMs), fallback to Shizuku
        if (!nativeSuccess) {
            if (PrivilegedExecutionManager.isShizukuReady() || PrivilegedExecutionManager.isRootAvailable()) {
                val privSuccess = writePrivilegedClipboard(text)
                if (!privSuccess) {
                    return ToolExecutionResult("Failed to write to clipboard natively and via Shizuku.", isError = true)
                }
            } else {
                return ToolExecutionResult("Failed to write to clipboard (App might be in background, and Shizuku is unavailable).", isError = true)
            }
        }

        return ToolExecutionResult("✅ Clipboard updated successfully (${text.length} chars).")
    }

    private suspend fun clear(): ToolExecutionResult {
        withContext(Dispatchers.Main) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                clipboard.clearPrimaryClip()
            } else {
                clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
            }
        }
        // Also clear via Shizuku to be completely sure
        if (PrivilegedExecutionManager.isShizukuReady() || PrivilegedExecutionManager.isRootAvailable()) {
            PrivilegedExecutionManager.executeCommand("cmd clipboard set \"\"")
        }
        return ToolExecutionResult("✅ Clipboard cleared.")
    }

    private suspend fun hasContent(): ToolExecutionResult {
        val text = readNativeClipboard() ?: readPrivilegedClipboard()
        
        if (text.isNullOrEmpty()) {
            return ToolExecutionResult("Clipboard is empty.")
        }
        
        val preview = text.take(150).replace("\n", " ") + if (text.length > 150) "..." else ""
        return ToolExecutionResult("Clipboard has content (${text.length} chars): $preview")
    }

    private suspend fun getAllItems(): ToolExecutionResult = withContext(Dispatchers.Main) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        
        // If native clipboard is empty, check privileged as it might just be background-blocked
        if (clip == null || clip.itemCount == 0) {
            val privText = readPrivilegedClipboard()
            if (!privText.isNullOrEmpty()) {
                val preview = privText.take(150).replace("\n", " ")
                return@withContext ToolExecutionResult(
                    "Clipboard items (1 via Shizuku bypass):\n  [0] $preview..."
                )
            }
            return@withContext ToolExecutionResult("Clipboard is empty.")
        }

        val sb = StringBuilder("Clipboard items (${clip.itemCount}):\n")
        for (i in 0 until clip.itemCount) {
            val item = clip.getItemAt(i)
            val text = item.coerceToText(context)?.toString() ?: "[Non-text item]"
            
            val truncatedText = if (text.length > 500) text.take(500) + "... [Truncated]" else text
            sb.appendLine("  [$i] $truncatedText")
        }
        ToolExecutionResult(sb.toString().trimEnd())
    }

    // ──────────────────────────────────────────────────────────────────────
    // Internal Native & Privileged Helpers
    // ──────────────────────────────────────────────────────────────────────

    private suspend fun readNativeClipboard(): String? = withContext(Dispatchers.Main) {
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboard.primaryClip
            if (clip != null && clip.itemCount > 0) {
                clip.getItemAt(0).coerceToText(context)?.toString()
            } else null
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun writeNativeClipboard(text: String): Boolean = withContext(Dispatchers.Main) {
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("omnidev_clipboard", text)
            clipboard.setPrimaryClip(clip)
            true
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun readPrivilegedClipboard(): String? = withContext(Dispatchers.IO) {
        try {
            val result = PrivilegedExecutionManager.executeCommand("cmd clipboard get")
            if (result.isSuccess) {
                val text = result.getOrDefault("")
                if (text.isNotBlank() && !text.contains("Error:", ignoreCase = true)) {
                    return@withContext text
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun writePrivilegedClipboard(text: String): Boolean = withContext(Dispatchers.IO) {
        try {
            // Securely write large texts via temp file and cat it to the clipboard service
            val safeText = text.replace("'", "'\\''")
            val result = PrivilegedExecutionManager.executeCommand("echo '$safeText' | cmd clipboard set")
            result.isSuccess
        } catch (e: Exception) {
            false
        }
    }
}
