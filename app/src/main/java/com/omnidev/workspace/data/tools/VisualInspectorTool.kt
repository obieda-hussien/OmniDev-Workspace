package com.omnidev.workspace.data.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * AI Eye: captures the device screen via Shizuku-elevated `screencap` and returns the
 * screenshot as a Base64-encoded PNG string, suitable for multi-modal LLM observations.
 *
 * The AI can build a Compose/XML layout, launch the app, run this tool to "see" the UI,
 * and autonomously fix padding, colors, or alignment issues.
 *
 * **Safety**: All executions are gated through [ConfirmationGate] via the agent pipeline.
 */
object VisualInspectorTool {

    private const val SCREENSHOT_PATH = "/data/local/tmp/omnidev_ui_dump.png"

    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "visual_inspector",
            description = "Captures a screenshot of the device screen and returns it as a Base64 PNG " +
                "string. Use this tool to visually inspect the current UI state after launching an app.",
            parameters = emptyList()
        )
    )

    suspend fun execute(): ToolExecutionResult = withContext(Dispatchers.IO) {
        try {
            // Step 1: Take screenshot via Shizuku elevated shell
            val captureResult = ShizukuCommandTool.execute("screencap -p $SCREENSHOT_PATH")
            if (captureResult is ShizukuResult.Failure) {
                return@withContext ToolExecutionResult(
                    "Screenshot capture failed: ${captureResult.reason}", isError = true
                )
            }
            if (captureResult is ShizukuResult.Unavailable || captureResult is ShizukuResult.PermissionRequired) {
                return@withContext ToolExecutionResult(captureResult.toDisplayString(), isError = true)
            }

            // Step 2: Read the PNG file and encode as Base64
            val screenshotFile = File(SCREENSHOT_PATH)
            if (!screenshotFile.exists() || screenshotFile.length() == 0L) {
                return@withContext ToolExecutionResult(
                    "Screenshot file not found or empty at $SCREENSHOT_PATH", isError = true
                )
            }

            val bytes = screenshotFile.readBytes()
            val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)

            // Step 3: Clean up
            screenshotFile.delete()

            // Return as a structured observation that the LLM can parse
            ToolExecutionResult(
                output = "[SCREENSHOT_BASE64]\ndata:image/png;base64,$base64\n[/SCREENSHOT_BASE64]\n" +
                    "Screenshot captured successfully (${bytes.size / 1024} KB)."
            )
        } catch (e: Exception) {
            ToolExecutionResult("Screenshot failed: ${e.message}", isError = true)
        }
    }
}
