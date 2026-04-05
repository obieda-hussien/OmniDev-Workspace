package com.omnidev.workspace.data.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.omnidev.workspace.data.accessibility.OmniAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * AI Eye: captures the device screen and returns it as a Base64-encoded JPEG string.
 *
 * FIXES APPLIED:
 * 1. Extreme Size Optimization: Downscales to 1024px and uses 50% JPEG compression.
 * This reduces the Base64 string from ~500,000 chars to ~50,000 chars, preventing 
 * JSON serialization lag, UI freezes, and LLM token parsing timeouts.
 * 2. Infinite Suspend Protection: Added strict timeouts to both Accessibility and 
 * Shizuku capture methods.
 */
object VisualInspectorTool {

    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "visual_inspector",
            description = "Captures a screenshot of the device screen and returns it as a Base64-encoded JPEG string. " +
                "Use this tool to visually inspect the UI state, debug layouts, read un-scrapable content, " +
                "or analyze visual elements on the screen. Highly compressed for speed.",
            parameters = emptyList()
        )
    )

    suspend fun execute(context: Context): ToolExecutionResult = withContext(Dispatchers.IO) {
        try {
            var bitmap: Bitmap? = null

            // Step 1: Fast, in-memory capture with TIMEOUT (Prevents infinite hang)
            val a11yService = OmniAccessibilityService.instance
            if (a11yService != null) {
                bitmap = withTimeoutOrNull(2000L) {
                    a11yService.takeSilentScreenshot()
                }
            }

            // Step 2: Fallback to Shizuku shell with timeout protection
            if (bitmap == null) {
                val tmpPath = "/data/local/tmp/omnidev_ui_dump.png"
                val localCache = File(context.cacheDir, "vision_dump.png")

                // 'screencap' directly to raw/png, copy, and clean up
                val cmd = "screencap -p $tmpPath && cat $tmpPath > '${localCache.absolutePath}' && chmod 666 '${localCache.absolutePath}' && rm -f $tmpPath"
                
                val captureResult = withTimeoutOrNull(5000L) {
                    ShizukuCommandTool.execute(cmd)
                }

                if (captureResult == null) {
                    return@withContext ToolExecutionResult("Screenshot capture timed out via Shizuku.", isError = true)
                }

                if (captureResult is ShizukuResult.Failure || !localCache.exists() || localCache.length() == 0L) {
                    return@withContext ToolExecutionResult(
                        "Screenshot capture failed via Shizuku fallback. Result: ${captureResult.toDisplayString()}", 
                        isError = true
                    )
                }

                // Decode safely
                bitmap = BitmapFactory.decodeFile(localCache.absolutePath)
                localCache.delete() 
            }

            if (bitmap == null) {
                return@withContext ToolExecutionResult("Failed to generate or decode screenshot bitmap.", isError = true)
            }

            // Step 3: Aggressive Optimization & Compression for LLMs
            // Max dimension 1024px + 50% JPEG quality keeps text perfectly readable for Vision Models
            // but drastically reduces Base64 string generation time and LLM processing lag.
            val optimizedBitmap = optimizeBitmapForLLM(bitmap)
            val outStream = ByteArrayOutputStream()
            optimizedBitmap.compress(Bitmap.CompressFormat.JPEG, 50, outStream)
            val bytes = outStream.toByteArray()
            
            // Encode to Base64
            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)

            // Step 4: Memory cleanup (Crucial to prevent OOM)
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
     * Scales down the bitmap to a maximum dimension of 1024px, preserving aspect ratio.
     * Vision LLMs process resized images much faster.
     */
    private fun optimizeBitmapForLLM(original: Bitmap): Bitmap {
        val maxDimension = 1024f
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
