package com.omnidev.workspace.data.tools

import com.omnidev.workspace.data.ipc.PrivilegedExecutionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * UIAutomationTool — the "Phantom Touch" Engine for autonomous UI interaction.
 *
 * Allows the agent to interact with the device's screen directly using Shizuku-brokered
 * shell commands (uiautomator, input). All actions require Shizuku to be active.
 *
 * **CRITICAL ARCHITECTURE NOTE:**
 * This tool outputs raw Android `uiautomator` XML dumps. The AI Agent should ALWAYS prioritize
 * using the `semantic_ui` tool (compressed, token-optimized `[N1]` node IDs) over this tool.
 * This tool remains as a low-level fallback for coordinate interactions (gestures).
 *
 * Supported actions:
 * - `dump_screen`    — Dumps current UI hierarchy to XML via `uiautomator dump`
 * - `tap`            — Sends a basic tap gesture at (x, y) coordinates
 * - `long_press`     — Simulates a long press at (x, y) coordinates
 * - `swipe`          — Sends a swipe gesture (fast move) between two points
 * - `drag_and_drop`  — Sends a drag-and-drop gesture (slow move) between two points
 * - `press_and_drag` — Long presses first, then drags, ensuring element pick-up
 * - `input_text`     — Types text into the focused input field via hardware injection
 * - `press_key`      — Presses a named Android key (e.g. KEYCODE_BACK, KEYCODE_HOME)
 */
object UIAutomationTool {

    private const val DUMP_PATH = "/data/local/tmp/omnidev_uidump.xml"

    /** Maximum characters returned from a raw XML UI dump to prevent context window overflow. */
    private const val MAX_DUMP_LENGTH = 15_000

    // ─────────────────────────────────────────────────────────────────────
    // Tool Definition
    // ─────────────────────────────────────────────────────────────────────

    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "ui_automation",
            description = "Low-level autonomous device interaction. WARNING: Prefer 'semantic_ui' tool " +
                "for reading the screen. Use this tool ONLY for coordinate-based gestures. " +
                "Actions: 'dump_screen' (raw XML UI - TOKEN HEAVY), " +
                "'tap' (quick tap), 'long_press' (long press coordinate), " +
                "'swipe' (fast swipe), 'drag_and_drop' (slow drag), " +
                "'press_and_drag' (long press then drag), " +
                "'input_text' (type text via ADB injection), " +
                "'press_key' (press hardware key by KEYCODE name).",
            parameters = listOf(
                ToolParameter(
                    name = "action",
                    type = "string",
                    description = "Action: dump_screen, tap, long_press, swipe, drag_and_drop, press_and_drag, input_text, press_key.",
                    required = true
                ),
                ToolParameter(
                    name = "x",
                    type = "string",
                    description = "X coordinate (pixels). Required for all gestures (start X).",
                    required = false
                ),
                ToolParameter(
                    name = "y",
                    type = "string",
                    description = "Y coordinate (pixels). Required for all gestures (start Y).",
                    required = false
                ),
                ToolParameter(
                    name = "x2",
                    type = "string",
                    description = "End X coordinate (pixels). Required for swipe, drag, press_and_drag.",
                    required = false
                ),
                ToolParameter(
                    name = "y2",
                    type = "string",
                    description = "End Y coordinate (pixels). Required for swipe, drag, press_and_drag.",
                    required = false
                ),
                ToolParameter(
                    name = "duration",
                    type = "string",
                    description = "Gesture duration in milliseconds (Optional. Defaults vary per gesture).",
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
                    description = "Android KEYCODE integer/name (e.g. 4 or KEYCODE_BACK). Required for 'press_key'.",
                    required = false
                )
            )
        )
    )

    // ─────────────────────────────────────────────────────────────────────
    // Execution Router
    // ─────────────────────────────────────────────────────────────────────

    suspend fun execute(action: String, params: Map<String, String>): ToolExecutionResult =
        withContext(Dispatchers.IO) {
            runCatching {
                when (action.lowercase().trim()) {
                    "dump_screen" -> dumpScreen()
                    
                    "tap" -> {
                        val x = params["x"]?.toIntOrNull() ?: return@withContext missingArg("x")
                        val y = params["y"]?.toIntOrNull() ?: return@withContext missingArg("y")
                        tap(x, y)
                    }

                    "long_press" -> {
                        val x = params["x"]?.toIntOrNull() ?: return@withContext missingArg("x")
                        val y = params["y"]?.toIntOrNull() ?: return@withContext missingArg("y")
                        val duration = params["duration"]?.toIntOrNull() ?: 1000 // default 1s long press
                        longPress(x, y, duration)
                    }
                    
                    "swipe" -> {
                        val x1 = params["x"]?.toIntOrNull() ?: return@withContext missingArg("x1")
                        val y1 = params["y"]?.toIntOrNull() ?: return@withContext missingArg("y1")
                        val x2 = params["x2"]?.toIntOrNull() ?: return@withContext missingArg("x2")
                        val y2 = params["y2"]?.toIntOrNull() ?: return@withContext missingArg("y2")
                        val duration = params["duration"]?.toIntOrNull() ?: 300 // default 300ms swipe
                        swipe(x1, y1, x2, y2, duration)
                    }

                    "drag_and_drop" -> {
                        val x1 = params["x"]?.toIntOrNull() ?: return@withContext missingArg("x1")
                        val y1 = params["y"]?.toIntOrNull() ?: return@withContext missingArg("y1")
                        val x2 = params["x2"]?.toIntOrNull() ?: return@withContext missingArg("x2")
                        val y2 = params["y2"]?.toIntOrNull() ?: return@withContext missingArg("y2")
                        val duration = params["duration"]?.toIntOrNull() ?: 1500 // default 1.5s drag
                        dragAndDrop(x1, y1, x2, y2, duration)
                    }

                    "press_and_drag" -> {
                        val x1 = params["x"]?.toIntOrNull() ?: return@withContext missingArg("x1")
                        val y1 = params["y"]?.toIntOrNull() ?: return@withContext missingArg("y1")
                        val x2 = params["x2"]?.toIntOrNull() ?: return@withContext missingArg("x2")
                        val y2 = params["y2"]?.toIntOrNull() ?: return@withContext missingArg("y2")
                        // Duration for the drag phase, default 1s
                        val duration = params["duration"]?.toIntOrNull() ?: 1000
                        pressAndDrag(x1, y1, x2, y2, duration)
                    }
                    
                    "input_text" -> {
                        val text = params["text"] ?: return@withContext missingArg("text")
                        inputText(text)
                    }
                    
                    "press_key" -> {
                        val keycode = params["keycode"] ?: return@withContext missingArg("keycode")
                        pressKey(keycode)
                    }
                    
                    else -> ToolExecutionResult(
                        "Unknown ui_automation action: '$action'. See tool description for supported actions.",
                        isError = true
                    )
                }
            }.getOrElse { error ->
                if (ShizukuCommandTool.isShizukuServiceException(error)) {
                    ToolExecutionResult(ShizukuCommandTool.SHIZUKU_UNAVAILABLE_ERROR, isError = true)
                } else {
                    ToolExecutionResult(
                        output = "UI automation phantom engine failed: ${error.message}",
                        isError = true
                    )
                }
            }
        }

    // ─────────────────────────────────────────────────────────────────────
    // Private Gesture Helpers (Low-Level Hardware Injection)
    // ─────────────────────────────────────────────────────────────────────

    private suspend fun dumpScreen(): ToolExecutionResult {
        // Warning sent directly to the LLM output to strongly discourage using this method over semantic_ui
        val prefixWarning = "⚠️ WARNING: You are reading raw XML UI dump. This is highly token-inefficient. " +
                "PLEEASE switch to using the 'semantic_ui' tool for compressed, node-based UI tree observations.\n\n"

        // Dump UI hierarchy to a temp file then read it back
        val dumpResult = ShizukuCommandTool.execute(
            "uiautomator dump $DUMP_PATH > /dev/null 2>&1 && cat $DUMP_PATH && rm -f $DUMP_PATH"
        )
        
        return when (dumpResult) {
            is ShizukuResult.Success, is ShizukuResult.PartialSuccess -> {
                val xml = dumpResult.outputOrNull()?.trim() ?: ""
                
                if (xml.isBlank() || !xml.contains("<?xml")) {
                    ToolExecutionResult(
                        "UI dump succeeded but returned invalid or empty content. The screen might be FLAG_SECURE protected.",
                        isError = false
                    )
                } else {
                    val truncated = xml.length > MAX_DUMP_LENGTH
                    ToolExecutionResult(
                        output = prefixWarning + if (truncated) {
                            xml.take(MAX_DUMP_LENGTH) + "\n\n...[XML TRUNCATED DUE TO EXTREME LENGTH. USE semantic_ui INSTEAD]"
                        } else {
                            xml
                        },
                        truncated = truncated
                    )
                }
            }
            is ShizukuResult.Failure -> ToolExecutionResult(dumpResult.reason, isError = true)
            is ShizukuResult.PermissionRequired -> ToolExecutionResult(dumpResult.message, isError = true)
            is ShizukuResult.Unavailable -> ToolExecutionResult(dumpResult.message, isError = true)
        }
    }

    private suspend fun tap(x: Int, y: Int): ToolExecutionResult {
        val result = PrivilegedExecutionManager.injectTap(x, y)
        return if (result.isSuccess) {
            ToolExecutionResult("✅ Hardware tapped ($x, $y).")
        } else {
            ToolExecutionResult("❌ Tap failed: ${result.exceptionOrNull()?.message}", isError = true)
        }
    }

    /**
     * Re-uses the underlying swipe implementation but enforces same-point swipe to simulate
     * a hardware long press for a specified duration.
     */
    private suspend fun longPress(x: Int, y: Int, duration: Int): ToolExecutionResult {
        // Architecture Trick: Long press is just a swipe from (x,y) to (x,y) over time.
        val result = PrivilegedExecutionManager.injectSwipe(x, y, x, y, duration)
        return if (result.isSuccess) {
            ToolExecutionResult("✅ Hardware long-pressed ($x, $y) for ${duration}ms.")
        } else {
            ToolExecutionResult("❌ Long press failed: ${result.exceptionOrNull()?.message}", isError = true)
        }
    }

    private suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, duration: Int): ToolExecutionResult {
        val result = PrivilegedExecutionManager.injectSwipe(x1, y1, x2, y2, duration)
        return if (result.isSuccess) {
            ToolExecutionResult("✅ Hardware swiped ($x1,$y1) → ($x2,$y2) in ${duration}ms.")
        } else {
            ToolExecutionResult("❌ Swipe failed: ${result.exceptionOrNull()?.message}", isError = true)
        }
    }

    /**
     * Similar to swipe but enforces a longer default duration to ensure the UI can recognize
     * the dragging intent before movement begins.
     */
    private suspend fun dragAndDrop(x1: Int, y1: Int, x2: Int, y2: Int, duration: Int): ToolExecutionResult {
        // Drag requires longer duration for the UI to pick up the element.
        val result = PrivilegedExecutionManager.injectSwipe(x1, y1, x2, y2, duration)
        return if (result.isSuccess) {
            ToolExecutionResult("✅ Hardware dragged ($x1,$y1) → ($x2,$y2) in ${duration}ms.")
        } else {
            ToolExecutionResult("❌ Drag failed: ${result.exceptionOrNull()?.message}", isError = true)
        }
    }

    /**
     * Executes a hardware-level long press, waits, then initiates a drag operation.
     * Essential for dragging app icons on the home screen where a standard swipe won't pick up the element.
     */
    private suspend fun pressAndDrag(x1: Int, y1: Int, x2: Int, y2: Int, duration: Int): ToolExecutionResult {
        // First, explicitly long press to "pick up" the element
        val pressResult = PrivilegedExecutionManager.injectSwipe(x1, y1, x1, y1, 1000)
        if (!pressResult.isSuccess) {
            return ToolExecutionResult("❌ Initial press failed: ${pressResult.exceptionOrNull()?.message}", isError = true)
        }
        
        // Wait a tiny bit for the OS to catch up
        delay(100)
        
        // Then execute the drag phase
        val dragResult = PrivilegedExecutionManager.injectSwipe(x1, y1, x2, y2, duration)
        return if (dragResult.isSuccess) {
            ToolExecutionResult("✅ Element picked up at ($x1,$y1), then dragged and dropped to ($x2,$y2) in ${duration}ms.")
        } else {
            ToolExecutionResult("❌ Drag phase failed: ${dragResult.exceptionOrNull()?.message}", isError = true)
        }
    }

    private suspend fun inputText(text: String): ToolExecutionResult {
        // Leverages the robust shell-escaping mechanism in PrivilegedExecutionManager
        val result = PrivilegedExecutionManager.injectText(text)
        return if (result.isSuccess) {
            ToolExecutionResult("✅ Typed text into focused field via hardware injection.")
        } else {
            ToolExecutionResult("❌ Failed to type text: ${result.exceptionOrNull()?.message}", isError = true)
        }
    }

    private suspend fun pressKey(keycode: String): ToolExecutionResult {
        // Sanitize the keycode to prevent any shell injection
        val safeKeycode = keycode.replace(Regex("[^a-zA-Z0-9_]"), "")
        if (safeKeycode.isEmpty()) {
            return ToolExecutionResult("Invalid keycode input: '$keycode'.", isError = true)
        }
        
        val cmd = "input keyevent $safeKeycode"
        // Keyevents don't need direct injection, simple command execution is fine.
        val result = PrivilegedExecutionManager.executeCommand(cmd)
        
        return if (result.isSuccess) {
            ToolExecutionResult("✅ Pressed hardware key: $safeKeycode.")
        } else {
            ToolExecutionResult("❌ Failed to press key: ${result.exceptionOrNull()?.message}", isError = true)
        }
    }
    
    // ── Diagnostic Helpers ──

    private fun missingArg(name: String) =
        ToolExecutionResult("Missing or invalid argument for action: '$name'.", isError = true)
}
