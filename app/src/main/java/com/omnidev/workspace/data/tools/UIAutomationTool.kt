package com.omnidev.workspace.data.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * UIAutomationTool — the "Ghost Finger" for autonomous UI interaction.
 *
 * Allows the agent to interact with the device's screen directly using Shizuku-brokered
 * shell commands (uiautomator, input). All actions require Shizuku to be active.
 *
 * Supported actions:
 * - `dump_screen`  — Dumps the current UI hierarchy to XML via `uiautomator dump`
 * - `tap`          — Sends a tap gesture at given (x, y) coordinates
 * - `swipe`        — Sends a swipe gesture between two coordinate pairs
 * - `input_text`   — Types text into the focused input field
 * - `press_key`    — Presses a named Android key (e.g. KEYCODE_BACK, KEYCODE_HOME)
 */
object UIAutomationTool {

    private const val DUMP_PATH = "/data/local/tmp/omnidev_uidump.xml"

    /** Maximum number of characters returned from a UI hierarchy dump to avoid context overflow. */
    private const val MAX_DUMP_LENGTH = 8_000

    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "ui_automation",
            description = "CRITICAL: Use this tool to interact with the device screen autonomously " +
                "(tap buttons, type text, swipe, read UI elements). " +
                "DO NOT use codebase search or terminal tools for UI interaction tasks. " +
                "Actions: 'dump_screen' (returns XML of visible UI), " +
                "'tap' (tap a coordinate), 'swipe' (swipe between two points), " +
                "'input_text' (type text into focused field), " +
                "'press_key' (press a hardware/software key by KEYCODE name).",
            parameters = listOf(
                ToolParameter(
                    name = "action",
                    type = "string",
                    description = "Action to perform: 'dump_screen', 'tap', 'swipe', " +
                        "'input_text', or 'press_key'.",
                    required = true
                ),
                ToolParameter(
                    name = "x",
                    type = "string",
                    description = "X coordinate (pixels). Required for 'tap' and 'swipe' (start X).",
                    required = false
                ),
                ToolParameter(
                    name = "y",
                    type = "string",
                    description = "Y coordinate (pixels). Required for 'tap' and 'swipe' (start Y).",
                    required = false
                ),
                ToolParameter(
                    name = "x2",
                    type = "string",
                    description = "End X coordinate (pixels). Required for 'swipe'.",
                    required = false
                ),
                ToolParameter(
                    name = "y2",
                    type = "string",
                    description = "End Y coordinate (pixels). Required for 'swipe'.",
                    required = false
                ),
                ToolParameter(
                    name = "duration",
                    type = "string",
                    description = "Swipe duration in milliseconds (default: 300). Used with 'swipe'.",
                    required = false
                ),
                ToolParameter(
                    name = "text",
                    type = "string",
                    description = "Text to type. Required for 'input_text'.",
                    required = false
                ),
                ToolParameter(
                    name = "keycode",
                    type = "string",
                    description = "Android KEYCODE name (e.g. KEYCODE_BACK, KEYCODE_HOME, " +
                        "KEYCODE_ENTER, KEYCODE_DPAD_UP). Required for 'press_key'.",
                    required = false
                )
            )
        )
    )

    suspend fun execute(action: String, params: Map<String, String>): ToolExecutionResult =
        withContext(Dispatchers.IO) {
            runCatching {
                when (action.lowercase()) {
                    "dump_screen" -> dumpScreen()
                    "tap" -> {
                        val x = params["x"]?.toIntOrNull()
                            ?: return@withContext ToolExecutionResult(
                                "Missing or invalid 'x' coordinate for tap.", isError = true)
                        val y = params["y"]?.toIntOrNull()
                            ?: return@withContext ToolExecutionResult(
                                "Missing or invalid 'y' coordinate for tap.", isError = true)
                        tap(x, y)
                    }
                    "swipe" -> {
                        val x1 = params["x"]?.toIntOrNull()
                            ?: return@withContext ToolExecutionResult(
                                "Missing or invalid 'x' for swipe start.", isError = true)
                        val y1 = params["y"]?.toIntOrNull()
                            ?: return@withContext ToolExecutionResult(
                                "Missing or invalid 'y' for swipe start.", isError = true)
                        val x2 = params["x2"]?.toIntOrNull()
                            ?: return@withContext ToolExecutionResult(
                                "Missing or invalid 'x2' for swipe end.", isError = true)
                        val y2 = params["y2"]?.toIntOrNull()
                            ?: return@withContext ToolExecutionResult(
                                "Missing or invalid 'y2' for swipe end.", isError = true)
                        val duration = params["duration"]?.toIntOrNull() ?: 300
                        swipe(x1, y1, x2, y2, duration)
                    }
                    "input_text" -> {
                        val text = params["text"]
                            ?: return@withContext ToolExecutionResult(
                                "Missing 'text' argument for input_text.", isError = true)
                        inputText(text)
                    }
                    "press_key" -> {
                        val keycode = params["keycode"]
                            ?: return@withContext ToolExecutionResult(
                                "Missing 'keycode' argument for press_key.", isError = true)
                        pressKey(keycode)
                    }
                    else -> ToolExecutionResult(
                        "Unknown ui_automation action: '$action'. " +
                            "Supported: dump_screen, tap, swipe, input_text, press_key.",
                        isError = true
                    )
                }
            }.getOrElse { error ->
                if (ShizukuCommandTool.isShizukuServiceException(error)) {
                    ToolExecutionResult(ShizukuCommandTool.SHIZUKU_UNAVAILABLE_ERROR, isError = true)
                } else {
                    ToolExecutionResult(
                        output = "UI automation failed: ${error.message}",
                        isError = true
                    )
                }
            }
        }

    // ── Private helpers ──

    private suspend fun dumpScreen(): ToolExecutionResult {
        // Dump UI hierarchy to a temp file then read it back
        val dumpResult = ShizukuCommandTool.execute(
            "uiautomator dump $DUMP_PATH && cat $DUMP_PATH && rm -f $DUMP_PATH"
        )
        return when (dumpResult) {
            is ShizukuResult.Success -> {
                val xml = dumpResult.output.trim()
                if (xml.isBlank() || xml == "(no output)") {
                    ToolExecutionResult(
                        "UI dump succeeded but returned no content. " +
                            "The screen may be off or fully black.",
                        isError = false
                    )
                } else {
                    val truncated = xml.length > MAX_DUMP_LENGTH
                    ToolExecutionResult(
                        output = if (truncated) xml.take(MAX_DUMP_LENGTH) + "\n...[truncated]" else xml,
                        truncated = truncated
                    )
                }
            }
            is ShizukuResult.PartialSuccess -> {
                val xml = dumpResult.output.trim()
                if (xml.isBlank() || xml == "(no output)") {
                    ToolExecutionResult(
                        "UI dump partial succeeded but returned no content. " +
                            "The screen may be off or fully black.",
                        isError = false
                    )
                } else {
                    val truncated = xml.length > MAX_DUMP_LENGTH
                    ToolExecutionResult(
                        output = "⚠️ [partial] " + if (truncated) xml.take(MAX_DUMP_LENGTH) + "\n...[truncated]" else xml,
                        truncated = truncated
                    )
                }
            }
            is ShizukuResult.Failure -> ToolExecutionResult(dumpResult.reason, isError = true)
            is ShizukuResult.PermissionRequired -> ToolExecutionResult(
                dumpResult.message, isError = true)
            is ShizukuResult.Unavailable -> ToolExecutionResult(dumpResult.message, isError = true)
        }
    }

    private suspend fun tap(x: Int, y: Int): ToolExecutionResult {
        val result = ShizukuCommandTool.execute("input tap $x $y")
        return when (result) {
            is ShizukuResult.Success -> ToolExecutionResult("✅ Tapped ($x, $y).")
            is ShizukuResult.PartialSuccess -> ToolExecutionResult("⚠️ Tapped ($x, $y) (partial): ${result.output}", isError = false)
            is ShizukuResult.Failure -> ToolExecutionResult(result.reason, isError = true)
            is ShizukuResult.PermissionRequired -> ToolExecutionResult(result.message, isError = true)
            is ShizukuResult.Unavailable -> ToolExecutionResult(result.message, isError = true)
        }
    }

    private suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, duration: Int): ToolExecutionResult {
        val result = ShizukuCommandTool.execute("input swipe $x1 $y1 $x2 $y2 $duration")
        return when (result) {
            is ShizukuResult.Success ->
                ToolExecutionResult("✅ Swiped ($x1,$y1) → ($x2,$y2) in ${duration}ms.")
            is ShizukuResult.PartialSuccess ->
                ToolExecutionResult("⚠️ Swiped (partial) ($x1,$y1) → ($x2,$y2): ${result.output}", isError = false)
            is ShizukuResult.Failure -> ToolExecutionResult(result.reason, isError = true)
            is ShizukuResult.PermissionRequired -> ToolExecutionResult(result.message, isError = true)
            is ShizukuResult.Unavailable -> ToolExecutionResult(result.message, isError = true)
        }
    }

    private suspend fun inputText(text: String): ToolExecutionResult {
        val encoded = text.map { ch ->
            when {
                ch.isLetterOrDigit() -> ch.toString()
                ch == ' ' -> "%s"
                else -> "%" + ch.code.toString(16).uppercase().padStart(2, '0')
            }
        }.joinToString("")
        val result = ShizukuCommandTool.execute("input text $encoded")
        return when (result) {
            is ShizukuResult.Success -> ToolExecutionResult("✅ Typed text into focused field.")
            is ShizukuResult.PartialSuccess -> ToolExecutionResult("⚠️ Typed text (partial): ${result.output}", isError = false)
            is ShizukuResult.Failure -> ToolExecutionResult(result.reason, isError = true)
            is ShizukuResult.PermissionRequired -> ToolExecutionResult(result.message, isError = true)
            is ShizukuResult.Unavailable -> ToolExecutionResult(result.message, isError = true)
        }
    }

    private suspend fun pressKey(keycode: String): ToolExecutionResult {
        val safeKeycode = keycode.replace(Regex("[^a-zA-Z0-9_]"), "")
        if (safeKeycode.isEmpty()) {
            return ToolExecutionResult("Invalid keycode: '$keycode'.", isError = true)
        }
        val result = ShizukuCommandTool.execute("input keyevent $safeKeycode")
        return when (result) {
            is ShizukuResult.Success ->
                ToolExecutionResult("✅ Pressed key: $safeKeycode.")
            is ShizukuResult.PartialSuccess ->
                ToolExecutionResult("⚠️ Pressed key (partial): $safeKeycode — ${result.output}", isError = false)
            is ShizukuResult.Failure -> ToolExecutionResult(result.reason, isError = true)
            is ShizukuResult.PermissionRequired -> ToolExecutionResult(result.message, isError = true)
            is ShizukuResult.Unavailable -> ToolExecutionResult(result.message, isError = true)
        }
    }
}
