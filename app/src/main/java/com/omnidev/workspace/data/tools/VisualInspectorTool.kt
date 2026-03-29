package com.omnidev.workspace.data.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.omnidev.workspace.data.accessibility.OmniAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * AI Eye: captures the device screen and returns it as a Base64-encoded JPEG string,
 * highly optimized for multi-modal LLM context windows.
 *
 * Hybrid Execution Strategy:
 * 1. Attempts in-memory capture via [OmniAccessibilityService] (Android 11+).
 * 2. Falls back to Shizuku-elevated `screencap`, safely copying the dump to the app's
 * cache directory to bypass SELinux read restrictions.
 *
 * **Safety**: All executions are gated through the pipeline's ConfirmationGate.
 */
object VisualInspectorTool {

    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "visual_inspector",
            description = "Captures a screenshot of the device screen and returns it as a Base64-encoded JPEG string. " +
                "Use this tool to visually inspect the UI state, debug layouts, read un-scrapable content, " +
                "or analyze visual elements on the screen. The image is scaled and compressed to fit AI context limits.",
            parameters = emptyList()
        )
    )

    suspend fun execute(context: Context): ToolExecutionResult = withContext(Dispatchers.IO) {
        try {
            var bitmap: Bitmap? = null

            // Step 1: Fast, in-memory capture via Accessibility Service (Zero Disk I/O)
            val a11yService = OmniAccessibilityService.instance
            if (a11yService != null) {
                bitmap = a11yService.takeSilentScreenshot()
            }

            // Step 2: Fallback to Shizuku shell (screencap) if Accessibility fails or SDK < 30
            if (bitmap == null) {
                val tmpPath = "/data/local/tmp/omnidev_ui_dump.png"
                val localCache = File(context.cacheDir, "vision_dump.png")

                // FIX: Capture via shell, then copy to app cache dir to bypass SELinux restrictions.
                // Using 'cat' avoids permission issues that 'cp' sometimes hits on strict ROMs.
                val cmd = "screencap -p $tmpPath && cat $tmpPath > '${localCache.absolutePath}' && chmod 666 '${localCache.absolutePath}' && rm $tmpPath"
                val captureResult = ShizukuCommandTool.execute(cmd)

                if (captureResult is ShizukuResult.Failure || !localCache.exists() || localCache.length() == 0L) {
                    return@withContext ToolExecutionResult(
                        "Screenshot capture failed via Shizuku fallback. Result: ${captureResult.toDisplayString()}", 
                        isError = true
                    )
                }

                // Decode the file saved in our readable cache directory
                bitmap = BitmapFactory.decodeFile(localCache.absolutePath)
                localCache.delete() // Clean up
            }

            if (bitmap == null) {
                return@withContext ToolExecutionResult("Failed to decode screenshot bitmap.", isError = true)
            }

            // Step 3: Optimization & Compression
            // A raw 1080x2400 PNG can be 4MB. Resizing to max 1280px and compressing to JPEG 80% 
            // drops the size to ~150-300KB, which is perfect for GPT-4o / Gemini Vision context limits.
            val optimizedBitmap = optimizeBitmapForLLM(bitmap)
            val outStream = ByteArrayOutputStream()
            optimizedBitmap.compress(Bitmap.CompressFormat.JPEG, 80, outStream)
            val bytes = outStream.toByteArray()
            
            // Encode to Base64
            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)

            // Step 4: Memory cleanup
            if (optimizedBitmap != bitmap) {
                bitmap.recycle()
            }
            optimizedBitmap.recycle()

            // Return structured observation
            ToolExecutionResult(
                output = "[SCREENSHOT_BASE64]\ndata:image/jpeg;base64,$base64\n[/SCREENSHOT_BASE64]\n" +
                    "Screenshot captured and optimized successfully (${bytes.size / 1024} KB)."
            )

        } catch (e: Exception) {
            ToolExecutionResult("Screenshot tool failed: ${e.message}", isError = true)
        }
    }

    /**
     * Scales down the bitmap if it exceeds the maximum dimension, preserving aspect ratio.
     * Multimodal LLMs don't need 4K resolution to understand UI elements.
     */
    private fun optimizeBitmapForLLM(original: Bitmap): Bitmap {
        val maxDimension = 1280f
        val width = original.width
        val height = original.height

        if (width <= maxDimension && height <= maxDimension) {
            return original
        }

        val ratio: Float = Math.min(maxDimension / width, maxDimension / height)
        val newWidth = Math.round(ratio * width)
        val newHeight = Math.round(ratio * height)

        return Bitmap.createScaledBitmap(original, newWidth, newHeight, true)
    }
}
