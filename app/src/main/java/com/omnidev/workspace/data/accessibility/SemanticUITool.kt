package com.omnidev.workspace.data.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.omnidev.workspace.data.tools.ShizukuCommandTool
import com.omnidev.workspace.data.tools.ToolDefinition
import com.omnidev.workspace.data.tools.ToolExecutionResult
import com.omnidev.workspace.data.tools.ToolParameter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * SemanticUITool — The Agent's semantic hand for interacting with any Android app.
 *
 * Replaces brittle X/Y coordinate-based [UIAutomationTool] with semantic node-based
 * interactions powered by [OmniAccessibilityService].
 *
 * Actions:
 * - `dump_tree`   — Returns the token-optimized semantic UI tree with node IDs
 * - `click`       — Clicks a node by its semantic ID (e.g., "N3")
 * - `long_click`  — Long-clicks a node by its semantic ID
 * - `type`        — Types text into an editable node by its semantic ID
 * - `scroll`      — Scrolls a scrollable node (forward or backward)
 * - `back`        — Presses the global BACK button
 * - `home`        — Presses the global HOME button
 * - `tap_xy`      — Fallback: taps at raw X/Y coordinates via gesture dispatch
 *
 * The node IDs (e.g., "N1", "N2") are ephemeral — they are regenerated on every
 * `dump_tree` call. The agent MUST call `dump_tree` first, then reference the
 * returned node IDs in subsequent actions.
 */
object SemanticUITool {

    /** Delay (ms) to wait for accessibility service to connect after auto-enable. */
    private const val ACCESSIBILITY_SERVICE_CONNECTION_DELAY_MS = 1500L

    /**
     * Holds the latest parse result from [SemanticTreeParser].
     * Populated by `dump_tree`; consumed by `click`, `type`, `scroll`.
     * Volatile for visibility across coroutines.
     */
    @Volatile
    private var lastParseResult: SemanticTreeParser.ParseResult? = null

    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "semantic_ui",
            description = "PREFERRED UI tool. Reads and interacts with the Android screen using " +
                "semantic node IDs instead of raw pixel coordinates. ALWAYS call 'dump_tree' " +
                "first to get the current UI tree with node IDs ([N1], [N2], ...), then use " +
                "'click', 'type', or 'scroll' with those IDs. " +
                "Actions: 'dump_tree' (get semantic UI tree), 'click' (click node by ID), " +
                "'long_click' (long-press node), 'type' (type text into editable node), " +
                "'scroll' (scroll node forward/backward), 'back' (press BACK), " +
                "'home' (press HOME), 'recents' (open recent apps), " +
                "'swipe' (gesture swipe by direction), 'tap_xy' (fallback: tap raw coordinates), " +
                "'force_click' (hardware tap via Shizuku — unstoppable, bypasses app restrictions), " +
                "'force_long_click' (hardware long-press via Shizuku), " +
                "'auto_enable' (auto-enable accessibility service via Shizuku).",
            parameters = listOf(
                ToolParameter(
                    name = "action",
                    type = "string",
                    description = "Action: 'dump_tree', 'click', 'long_click', 'type', " +
                        "'scroll', 'back', 'home', 'recents', 'swipe', 'tap_xy', " +
                        "'force_click' (Shizuku hardware tap), " +
                        "'force_long_click' (Shizuku hardware long-press), or 'auto_enable'.",
                    required = true
                ),
                ToolParameter(
                    name = "node_id",
                    type = "string",
                    description = "Semantic node ID from dump_tree output (e.g., 'N3'). " +
                        "Required for 'click', 'long_click', 'type', 'scroll'.",
                    required = false
                ),
                ToolParameter(
                    name = "text",
                    type = "string",
                    description = "Text to type. Required for 'type' action.",
                    required = false
                ),
                ToolParameter(
                    name = "direction",
                    type = "string",
                    description = "Scroll direction: 'forward' (down) or 'backward' (up). " +
                        "Default: 'forward'. Used with 'scroll' and 'swipe'.",
                    required = false
                ),
                ToolParameter(
                    name = "duration_ms",
                    type = "string",
                    description = "Gesture duration in milliseconds for 'swipe'. Default: 320.",
                    required = false
                ),
                ToolParameter(
                    name = "distance_ratio",
                    type = "string",
                    description = "Swipe travel distance ratio (0.1..0.9). Default: 0.35.",
                    required = false
                ),
                ToolParameter(
                    name = "x",
                    type = "string",
                    description = "X coordinate (pixels). Used with 'tap_xy' fallback.",
                    required = false
                ),
                ToolParameter(
                    name = "y",
                    type = "string",
                    description = "Y coordinate (pixels). Used with 'tap_xy' fallback.",
                    required = false
                )
            )
        )
    )

    suspend fun execute(action: String, params: Map<String, String>): ToolExecutionResult =
        withContext(Dispatchers.Main) {
            // Auto-enable doesn't require service to be connected
            if (action.lowercase() == "auto_enable") {
                return@withContext autoEnable()
            }

            // Check if accessibility service is connected
            if (!AccessibilityStateManager.isServiceConnected.value) {
                // Try auto-enable via Shizuku before giving up
                if (ShizukuCommandTool.isAvailable() && ShizukuCommandTool.hasPermission()) {
                    val enableResult = GodModeAccessibility.autoEnableOmniVision()
                    // Wait briefly for the service to connect
                    kotlinx.coroutines.delay(ACCESSIBILITY_SERVICE_CONNECTION_DELAY_MS)
                    if (!AccessibilityStateManager.isServiceConnected.value) {
                        return@withContext ToolExecutionResult(
                            "⚠️ Accessibility Service auto-enable attempted: $enableResult\n" +
                                "Service has not connected yet. Try again in a few seconds.",
                            isError = true
                        )
                    }
                } else {
                    return@withContext ToolExecutionResult(
                        "⚠️ Accessibility Service is not enabled. " +
                            "Go to Settings → Accessibility → OmniDev Workspace and enable it. " +
                            "This is required for semantic UI interaction.",
                        isError = true
                    )
                }
            }

            when (action.lowercase()) {
                "dump_tree" -> dumpTree()
                "click" -> clickNode(params["node_id"])
                "long_click" -> longClickNode(params["node_id"])
                "type" -> typeText(params["node_id"], params["text"])
                "scroll" -> scrollNode(params["node_id"], params["direction"])
                "back" -> pressBack()
                "home" -> pressHome()
                "recents" -> pressRecents()
                "swipe" -> swipe(direction = params["direction"], durationMs = params["duration_ms"], distanceRatio = params["distance_ratio"], nodeId = params["node_id"])
                "tap_xy" -> tapXY(params["x"], params["y"])
                "force_click" -> forceClick(params["node_id"])
                "force_long_click" -> forceLongClick(params["node_id"])
                else -> ToolExecutionResult(
                    "Unknown semantic_ui action: '$action'. " +
                        "Supported: dump_tree, click, long_click, type, scroll, back, home, recents, swipe, " +
                        "tap_xy, force_click, force_long_click, auto_enable.",
                    isError = true
                )
            }
        }

    // ── Action implementations ──

    private fun dumpTree(): ToolExecutionResult {
        val root = AccessibilityStateManager.rootNode.value
            ?: return ToolExecutionResult(
                "No UI tree available. The screen may be off or no window is focused.",
                isError = true
            )

        return try {
            val result = SemanticTreeParser.parse(
                root = root,
                packageName = AccessibilityStateManager.activePackage.value,
                activityName = AccessibilityStateManager.activeActivity.value
            )
            lastParseResult = result

            val summary = "\n${result.semanticTree}\n\n" +
                "── Stats: ${result.extractedNodes} semantic nodes extracted " +
                "from ${result.totalRawNodes} raw nodes ──"

            ToolExecutionResult(
                output = summary,
                truncated = result.extractedNodes >= 120
            )
        } catch (e: Exception) {
            ToolExecutionResult(
                "Failed to parse UI tree: ${e.message}",
                isError = true
            )
        }
    }

    private fun clickNode(nodeId: String?): ToolExecutionResult {
        if (nodeId.isNullOrBlank()) {
            return ToolExecutionResult("Missing 'node_id' for click action.", isError = true)
        }

        val parseResult = lastParseResult
            ?: return ToolExecutionResult(
                "No UI tree cached. Call 'dump_tree' first to get node IDs.",
                isError = true
            )

        val node = parseResult.nodeMap[nodeId.uppercase()]
            ?: return ToolExecutionResult(
                "Node '$nodeId' not found. Available: ${parseResult.nodeMap.keys.sorted().joinToString()}. " +
                    "Call 'dump_tree' to refresh.",
                isError = true
            )

        val service = OmniAccessibilityService.instance
            ?: return ToolExecutionResult("Accessibility service not running.", isError = true)

        return if (service.clickNode(node)) {
            ToolExecutionResult("✅ Clicked [$nodeId]")
        } else {
            ToolExecutionResult("❌ Click failed on [$nodeId] — node may not be clickable.", isError = true)
        }
    }

    private fun longClickNode(nodeId: String?): ToolExecutionResult {
        if (nodeId.isNullOrBlank()) {
            return ToolExecutionResult("Missing 'node_id' for long_click action.", isError = true)
        }

        val parseResult = lastParseResult
            ?: return ToolExecutionResult(
                "No UI tree cached. Call 'dump_tree' first.",
                isError = true
            )

        val node = parseResult.nodeMap[nodeId.uppercase()]
            ?: return ToolExecutionResult(
                "Node '$nodeId' not found. Call 'dump_tree' to refresh.",
                isError = true
            )

        val service = OmniAccessibilityService.instance
            ?: return ToolExecutionResult("Accessibility service not running.", isError = true)

        return if (service.longClickNode(node)) {
            ToolExecutionResult("✅ Long-clicked [$nodeId]")
        } else {
            ToolExecutionResult("❌ Long-click failed on [$nodeId].", isError = true)
        }
    }

    private fun typeText(nodeId: String?, text: String?): ToolExecutionResult {
        if (nodeId.isNullOrBlank()) {
            return ToolExecutionResult("Missing 'node_id' for type action.", isError = true)
        }
        if (text == null) {
            return ToolExecutionResult("Missing 'text' for type action.", isError = true)
        }

        val parseResult = lastParseResult
            ?: return ToolExecutionResult(
                "No UI tree cached. Call 'dump_tree' first.",
                isError = true
            )

        val node = parseResult.nodeMap[nodeId.uppercase()]
            ?: return ToolExecutionResult(
                "Node '$nodeId' not found. Call 'dump_tree' to refresh.",
                isError = true
            )

        val service = OmniAccessibilityService.instance
            ?: return ToolExecutionResult("Accessibility service not running.", isError = true)

        return if (service.typeIntoNode(node, text)) {
            ToolExecutionResult("✅ Typed into [$nodeId]: \"$text\"")
        } else {
            ToolExecutionResult("❌ Type failed on [$nodeId] — node may not be editable.", isError = true)
        }
    }

    private fun scrollNode(nodeId: String?, direction: String?): ToolExecutionResult {
        val forward = direction?.lowercase() != "backward"

        // If node_id is provided, scroll that specific node
        if (!nodeId.isNullOrBlank()) {
            val parseResult = lastParseResult
                ?: return ToolExecutionResult(
                    "No UI tree cached. Call 'dump_tree' first.",
                    isError = true
                )

            val node = parseResult.nodeMap[nodeId.uppercase()]
                ?: return ToolExecutionResult(
                    "Node '$nodeId' not found. Call 'dump_tree' to refresh.",
                    isError = true
                )

            val service = OmniAccessibilityService.instance
                ?: return ToolExecutionResult("Accessibility service not running.", isError = true)

            return if (service.scrollNode(node, forward)) {
                val dir = if (forward) "forward" else "backward"
                ToolExecutionResult("✅ Scrolled [$nodeId] $dir")
            } else {
                ToolExecutionResult(
                    "❌ Scroll failed on [$nodeId] — node may not be scrollable.",
                    isError = true
                )
            }
        }

        // No node_id: try to find the first scrollable node in the tree
        val root = AccessibilityStateManager.rootNode.value
            ?: return ToolExecutionResult("No UI tree available.", isError = true)

        val scrollable = findFirstScrollable(root)
            ?: return ToolExecutionResult(
                "No scrollable element found on screen. Provide a specific 'node_id'.",
                isError = true
            )

        val service = OmniAccessibilityService.instance
            ?: return ToolExecutionResult("Accessibility service not running.", isError = true)

        return if (service.scrollNode(scrollable, forward)) {
            val dir = if (forward) "forward" else "backward"
            ToolExecutionResult("✅ Scrolled $dir (auto-detected scrollable)")
        } else {
            ToolExecutionResult("❌ Scroll failed.", isError = true)
        }
    }

    private fun pressBack(): ToolExecutionResult {
        val service = OmniAccessibilityService.instance
            ?: return ToolExecutionResult("Accessibility service not running.", isError = true)

        return if (service.pressBack()) {
            ToolExecutionResult("✅ Pressed BACK")
        } else {
            ToolExecutionResult("❌ BACK action failed.", isError = true)
        }
    }

    private fun pressHome(): ToolExecutionResult {
        val service = OmniAccessibilityService.instance
            ?: return ToolExecutionResult("Accessibility service not running.", isError = true)

        return if (service.pressHome()) {
            ToolExecutionResult("✅ Pressed HOME")
        } else {
            ToolExecutionResult("❌ HOME action failed.", isError = true)
        }
    }

    private fun pressRecents(): ToolExecutionResult {
        val service = OmniAccessibilityService.instance
            ?: return ToolExecutionResult("Accessibility service not running.", isError = true)

        return if (service.pressRecents()) {
            ToolExecutionResult("✅ Opened recent apps")
        } else {
            ToolExecutionResult("❌ RECENTS action failed.", isError = true)
        }
    }

    private fun tapXY(x: String?, y: String?): ToolExecutionResult {
        val xVal = x?.toFloatOrNull()
            ?: return ToolExecutionResult("Missing or invalid 'x' for tap_xy.", isError = true)
        val yVal = y?.toFloatOrNull()
            ?: return ToolExecutionResult("Missing or invalid 'y' for tap_xy.", isError = true)

        val service = OmniAccessibilityService.instance
            ?: return ToolExecutionResult("Accessibility service not running.", isError = true)

        return if (service.tapAtCoordinates(xVal, yVal)) {
            ToolExecutionResult("✅ Tapped at ($xVal, $yVal)")
        } else {
            ToolExecutionResult("❌ Tap gesture dispatch failed.", isError = true)
        }
    }

    private fun swipe(
        direction: String?,
        durationMs: String?,
        distanceRatio: String?,
        nodeId: String?
    ): ToolExecutionResult {
        val service = OmniAccessibilityService.instance
            ?: return ToolExecutionResult("Accessibility service not running.", isError = true)

        val dir = direction?.lowercase() ?: "forward"
        val duration = durationMs?.toLongOrNull()?.coerceIn(120L, 2_500L) ?: 320L
        val ratio = distanceRatio?.toFloatOrNull()?.coerceIn(0.1f, 0.9f) ?: 0.35f

        val area = if (nodeId.isNullOrBlank()) {
            val root = AccessibilityStateManager.rootNode.value
                ?: return ToolExecutionResult("No UI tree available for swipe.", isError = true)
            Rect().apply { root.getBoundsInScreen(this) }
        } else {
            val parseResult = lastParseResult
                ?: return ToolExecutionResult(
                    "No UI tree cached. Call 'dump_tree' first to use node-specific swipe.",
                    isError = true
                )
            val node = parseResult.nodeMap[nodeId.uppercase()]
                ?: return ToolExecutionResult(
                    "Node '$nodeId' not found. Call 'dump_tree' to refresh.",
                    isError = true
                )
            Rect().apply { node.getBoundsInScreen(this) }
        }

        if (area.isEmpty) {
            return ToolExecutionResult("Swipe area has invalid bounds.", isError = true)
        }

        val dx = area.width() * ratio
        val dy = area.height() * ratio
        val centerX = area.exactCenterX()
        val centerY = area.exactCenterY()

        val (startX, startY, endX, endY) = when (dir) {
            "backward", "up" -> SwipePoints(centerX, centerY + dy, centerX, centerY - dy)
            "down" -> SwipePoints(centerX, centerY - dy, centerX, centerY + dy)
            "left" -> SwipePoints(centerX + dx, centerY, centerX - dx, centerY)
            "right" -> SwipePoints(centerX - dx, centerY, centerX + dx, centerY)
            "forward" -> SwipePoints(centerX, centerY - dy, centerX, centerY + dy)
            else -> return ToolExecutionResult(
                "Invalid swipe direction '$dir'. Use forward/backward/up/down/left/right.",
                isError = true
            )
        }

        return if (service.swipeGesture(startX, startY, endX, endY, duration)) {
            val scope = if (nodeId.isNullOrBlank()) "screen" else "[$nodeId]"
            ToolExecutionResult("✅ Swiped $dir on $scope (duration=${duration}ms, ratio=$ratio)")
        } else {
            ToolExecutionResult("❌ Swipe gesture failed.", isError = true)
        }
    }

    // ── Helpers ──

    private data class SwipePoints(
        val startX: Float,
        val startY: Float,
        val endX: Float,
        val endY: Float
    )

    /**
     * Auto-enables OmniAccessibilityService via Shizuku.
     */
    private suspend fun autoEnable(): ToolExecutionResult {
        val result = GodModeAccessibility.autoEnableOmniVision()
        val isError = result.startsWith("❌")
        return ToolExecutionResult(result, isError = isError)
    }

    /**
     * Force-clicks a node via Shizuku hardware tap (bypasses app restrictions).
     */
    private suspend fun forceClick(nodeId: String?): ToolExecutionResult {
        if (nodeId.isNullOrBlank()) {
            return ToolExecutionResult("Missing 'node_id' for force_click action.", isError = true)
        }

        val parseResult = lastParseResult
            ?: return ToolExecutionResult(
                "No UI tree cached. Call 'dump_tree' first to get node IDs.",
                isError = true
            )

        val node = parseResult.nodeMap[nodeId.uppercase()]
            ?: return ToolExecutionResult(
                "Node '$nodeId' not found. Call 'dump_tree' to refresh.",
                isError = true
            )

        val result = withContext(kotlinx.coroutines.Dispatchers.IO) {
            GodModeAccessibility.hybridTap(node)
        }
        val isError = result.startsWith("❌")
        return ToolExecutionResult(
            if (!isError) "✅ Force-clicked [$nodeId] via Shizuku hardware tap" else result,
            isError = isError
        )
    }

    /**
     * Force-long-clicks a node via Shizuku hardware long-press.
     */
    private suspend fun forceLongClick(nodeId: String?): ToolExecutionResult {
        if (nodeId.isNullOrBlank()) {
            return ToolExecutionResult("Missing 'node_id' for force_long_click action.", isError = true)
        }

        val parseResult = lastParseResult
            ?: return ToolExecutionResult(
                "No UI tree cached. Call 'dump_tree' first to get node IDs.",
                isError = true
            )

        val node = parseResult.nodeMap[nodeId.uppercase()]
            ?: return ToolExecutionResult(
                "Node '$nodeId' not found. Call 'dump_tree' to refresh.",
                isError = true
            )

        val result = withContext(kotlinx.coroutines.Dispatchers.IO) {
            GodModeAccessibility.hybridLongPress(node)
        }
        val isError = result.startsWith("❌")
        return ToolExecutionResult(
            if (!isError) "✅ Force-long-clicked [$nodeId] via Shizuku hardware long-press" else result,
            isError = isError
        )
    }

    /**
     * Recursively finds the first scrollable node in the tree.
     */
    private fun findFirstScrollable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findFirstScrollable(child)
            if (result != null) return result
        }
        return null
    }
}
