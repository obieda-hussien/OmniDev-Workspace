package com.omnidev.workspace.data.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.omnidev.workspace.data.tools.ShizukuCommandTool
import com.omnidev.workspace.data.tools.ToolDefinition
import com.omnidev.workspace.data.tools.ToolExecutionResult
import com.omnidev.workspace.data.tools.ToolParameter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * SemanticUITool — Advanced semantic UI control tool
 *
 * Second-generation semantic UI interaction
 * ─────────────────────────────────────────────────────────────────────────────
 * Additional actions:
 * ─────────────────────────────────────────────────────────────────────────────
 * • `find_element` — Find an element by text without a full dump_tree
 * • `wait_for`     — Wait for text or an element with a configurable timeout
 * • `smart_fill`   — Fill a detected form in one call
 * • `get_summary`  — Return a screen summary without a full tree
 * • `get_text`     — Extract text from a node without dump_tree
 * • `verify`       — Check node existence and expected state
 * • `chain`        — Sequence actions in one call
 * • `record_start` / `record_stop` / `macro_play` — Macro recording and playback
 * • `scroll_to`    — Scroll until text appears
 * • `describe`     — Describe an element semantically by node_id
 *
 * Improvements to existing actions:
 * ─────────────────────────────────────────────────────────────────────────────
 * • Each `click` checks whether the UI changed afterward
 * • `type` supports clear_first to remove existing text
 * • `dump_tree` returns a summary, detected forms and navigation elements
 */
object SemanticUITool {

    private const val ACCESSIBILITY_SERVICE_MAX_WAIT_MS = 7_000L
    private const val DEFAULT_WAIT_TIMEOUT_MS = 10_000L
    private const val POST_ACTION_VERIFY_MS = 700L

    @Volatile
    private var lastParseResult: SemanticTreeParser.ParseResult? = null

    fun clearSnapshot() { lastParseResult = null }

    fun recordedSelector(nodeId: String?): com.omnidev.workspace.data.routines.UiSelector? =
        lastParseResult?.nodeMap?.get(nodeId?.uppercase())?.let(com.omnidev.workspace.data.routines.RoutineUi::selector)

    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "semantic_ui",
            description = """
Semantic UI control for Android: inspect the screen and interact with its elements.
Available actions:

📋 Reading:
• dump_tree    — Full tree with form and navigation detection
• get_summary  — Quick screen summary without the full tree
• find_element — Find an element directly by its text
• get_text     — Extract node text by node_id
• describe     — Describe an element semantically
• verify       — Check an element's state

⚡ Interaction:
• click / long_click — Click or long-click by node_id
• type          — Type text in an input field
• scroll        — Scroll a node
• smart_fill    — Fill a form using a {nodeId: text} map
• chain         — Sequence actions (tap+type+tap in one call)

⏱️ Waiting:
• wait_for      — Wait for text to appear on screen
• scroll_to     — Scroll until target text appears

🔧 Shizuku God-Mode:
• force_click / force_long_click / force_type
• auto_enable   — Enable the accessibility service automatically

📼 Macros:
• record_start / record_stop / macro_play / macro_list

🌍 Navigation:
• back / home / recents
• swipe / tap_xy
""".trimIndent(),
            parameters = listOf(
                ToolParameter("action", "string",
                    "Requested action (see description above)", required = true),
                ToolParameter("node_id", "string",
                    "Node ID from dump_tree (for example N3). Required for click, type, scroll, etc."),
                ToolParameter("text", "string",
                    "Text to enter (type/force_type) or search for (find_element/wait_for)"),
                ToolParameter("direction", "string",
                    "Scroll or swipe direction: forward/backward/up/down/left/right"),
                ToolParameter("timeout_ms", "string",
                    "Timeout in milliseconds for wait_for (default: 10000)"),
                ToolParameter("clear_first", "string",
                    "true to clear the field before type"),
                ToolParameter("verify_change", "string",
                    "false to disable the post-click UI change check"),
                ToolParameter("form_data", "string",
                    "JSON: {\"N1\":\"text1\",\"N2\":\"text2\"} for smart_fill"),
                ToolParameter("chain_steps", "string",
                    "JSON array of sequential actions for chain"),
                ToolParameter("macro_name", "string",
                    "Macro name for record_start/record_stop/macro_play"),
                ToolParameter("expected_state", "string",
                    "Expected state (Checked/Focused/Enabled/etc.) for verify"),
                ToolParameter("duration_ms", "string", "Swipe duration in milliseconds"),
                ToolParameter("distance_ratio", "string", "Swipe distance ratio (0.1..0.9)"),
                ToolParameter("x", "string", "X coordinate for tap_xy"),
                ToolParameter("y", "string", "Y coordinate for tap_xy")
            )
        )
    )

    suspend fun execute(action: String, params: Map<String, String>): ToolExecutionResult =
        withContext(Dispatchers.Main) {
            OmniAccessibilityService.instance?.let { service ->
                com.omnidev.workspace.data.admin.DeviceAccessGuard.toolDenial(service, "semantic_ui", params + ("action" to action))
            }?.let { clearSnapshot(); return@withContext it }
            if (action.lowercase() == "auto_enable") return@withContext autoEnable()

            if (!AccessibilityStateManager.isServiceConnected.value) {
                if (ShizukuCommandTool.isAvailable() && ShizukuCommandTool.hasPermission()) {
                    GodModeAccessibility.autoEnableOmniVision()
                    waitForAccessibilityConnection()
                }
                if (!AccessibilityStateManager.isServiceConnected.value) {
                    return@withContext ToolExecutionResult(
                        "⚠️ Accessibility service is not enabled. " +
                            "Go to Settings → Accessibility → OmniDev and enable it, " +
                            "or use the 'auto_enable' action.",
                        isError = true
                    )
                }
            }

            if (action.startsWith("routine_")) return@withContext com.omnidev.workspace.data.routines.RoutineUi.execute(action, params)

            when (action.lowercase()) {
                "dump_tree"    -> dumpTree()
                "get_summary"  -> getSummary()
                "click"        -> clickNode(params["node_id"], params["verify_change"] != "false")
                "long_click"   -> longClickNode(params["node_id"])
                "type"         -> typeText(params["node_id"], params["text"], params["clear_first"] == "true")
                "scroll"       -> scrollNode(params["node_id"], params["direction"])
                "find_element" -> findElement(params["text"])
                "wait_for"     -> waitFor(params["text"], params["timeout_ms"]?.toLongOrNull() ?: DEFAULT_WAIT_TIMEOUT_MS)
                "scroll_to"    -> scrollToText(params["text"], params["direction"])
                "smart_fill"   -> smartFill(params["form_data"])
                "get_text"     -> getNodeText(params["node_id"])
                "describe"     -> describeNode(params["node_id"])
                "verify"       -> verifyNode(params["node_id"], params["expected_state"])
                "chain"        -> executeChain(params["chain_steps"])
                "back"         -> pressBack()
                "home"         -> pressHome()
                "recents"      -> pressRecents()
                "swipe"        -> swipe(params["direction"], params["duration_ms"], params["distance_ratio"], params["node_id"])
                "tap_xy"       -> tapXY(params["x"], params["y"])
                "force_click"      -> forceClick(params["node_id"])
                "force_long_click" -> forceLongClick(params["node_id"])
                "force_type"       -> forceType(params["text"], params["node_id"])
                "record_start"     -> startRecording(params["macro_name"])
                "record_stop"      -> stopRecording(params["macro_name"])
                "macro_play"       -> playMacro(params["macro_name"])
                "macro_list"       -> listMacros()
                else -> ToolExecutionResult(
                    "Unknown action: '$action'. Use dump_tree to start interacting.",
                    isError = true
                )
            }
        }

    // ── Actions ─────────────────────────────────────────────────────────────

    private fun dumpTree(): ToolExecutionResult {
        val root = AccessibilityStateManager.rootNode.value
            ?: return ToolExecutionResult("No UI tree is available. The screen may be off.", isError = true)

        return try {
            val result = SemanticTreeParser.parse(
                root = root,
                packageName = AccessibilityStateManager.activePackage.value,
                activityName = AccessibilityStateManager.activeActivity.value
            )
            lastParseResult = result

            val output = buildString {
                append("📋 Summary: ${result.summary}\n\n")
                append(result.semanticTree)
                append("\n\n── Statistics: ${result.extractedNodes} semantic nodes / ${result.totalRawNodes} total ──")

                if (result.detectedForms.isNotEmpty()) {
                    append("\n\n📝 Detected forms:")
                    result.detectedForms.forEach { form ->
                        append("\n  • ${form.groupName}: ")
                        append(form.fields.joinToString(", ") { "[${it.nodeId}]${it.fieldType.name}" })
                    }
                }

                if (result.priorityOrder.isNotEmpty()) {
                    append("\n⭐ Interaction priority: ${result.priorityOrder.take(8).joinToString(" → ")}")
                }

                if (AccessibilityStateManager.shouldWaitForUI()) {
                    append("\n\n⏳ The UI is changing rapidly. Wait before interacting.")
                }
            }

            ToolExecutionResult(output, truncated = result.extractedNodes >= 150)
        } catch (e: Exception) {
            ToolExecutionResult("Tree analysis failed: ${e.message}", isError = true)
        }
    }

    private fun getSummary(): ToolExecutionResult {
        return ToolExecutionResult(
            buildString {
                append(AccessibilityStateManager.buildContextSummary())
                val result = lastParseResult
                if (result != null) {
                    append("\n\n📋 Last analyzed tree: ${result.extractedNodes} nodes")
                    if (result.detectedForms.isNotEmpty()) {
                        append("\n📝 Forms: ${result.detectedForms.joinToString(", ") { it.groupName }}")
                    }
                } else {
                    append("\n\n💡 The tree has not been analyzed yet. Use dump_tree.")
                }
            }
        )
    }

    private fun findElement(text: String?): ToolExecutionResult {
        if (text.isNullOrBlank()) {
            return ToolExecutionResult("Provide 'text' to search.", isError = true)
        }
        val found = AccessibilityStateManager.findNodeByText(text)
            ?: return ToolExecutionResult("❌ '$text' was not found in the current UI.", isError = true)

        val bounds = Rect().also { found.getBoundsInScreen(it) }
        val className = found.className?.toString()?.substringAfterLast('.') ?: "View"
        val isClickable = found.isClickable
        val isEditable = found.isEditable

        return ToolExecutionResult(
            buildString {
                append("✅ Found '$text'\n")
                append("Type: $className\n")
                append("Bounds: [${bounds.left}, ${bounds.top}, ${bounds.right}, ${bounds.bottom}]\n")
                if (isClickable) append("Clickable: yes\n")
                if (isEditable) append("Editable: yes\n")
                append("\nUse dump_tree to obtain the matching node_id.")
            }
        )
    }

    private suspend fun waitFor(text: String?, timeoutMs: Long): ToolExecutionResult {
        if (text.isNullOrBlank()) {
            return ToolExecutionResult("Provide 'text' to wait for.", isError = true)
        }

        val startTime = System.currentTimeMillis()
        val found = withTimeoutOrNull(timeoutMs) {
            while (true) {
                val node = AccessibilityStateManager.findNodeByText(text)
                if (node != null) return@withTimeoutOrNull true
                delay(300)
            }
            @Suppress("UNREACHABLE_CODE")
            false
        }

        val elapsed = System.currentTimeMillis() - startTime
        return if (found == true) {
            ToolExecutionResult("✅ '$text' appeared after ${elapsed}ms")
        } else {
            ToolExecutionResult(
                "⏱️ '$text' did not appear within ${timeoutMs}ms. Current screen: ${AccessibilityStateManager.activePackage.value}",
                isError = true
            )
        }
    }

    private suspend fun scrollToText(text: String?, direction: String?): ToolExecutionResult {
        if (text.isNullOrBlank()) {
            return ToolExecutionResult("Provide 'text' to scroll to.", isError = true)
        }
        val result = withContext(Dispatchers.IO) {
            GodModeAccessibility.scrollUntilVisible(text, direction ?: "down")
        }
        return ToolExecutionResult(result.message, isError = !result.found)
    }

    private suspend fun smartFill(formDataJson: String?): ToolExecutionResult {
        if (formDataJson.isNullOrBlank()) {
            return ToolExecutionResult(
                "Provide 'form_data' as JSON: {\"N1\":\"value1\",\"N2\":\"value2\"}",
                isError = true
            )
        }

        val parseResult = lastParseResult
            ?: return ToolExecutionResult("Use dump_tree first to obtain node_ids.", isError = true)

        return try {
            val data = parseJsonMap(formDataJson)
            val results = mutableListOf<String>()
            var successCount = 0

            for ((nodeId, value) in data) {
                val node = parseResult.nodeMap[nodeId.uppercase()]
                if (node == null) {
                    results.add("⚠️ $nodeId: Node not found")
                    continue
                }
                if (node.isPassword) { results.add("Protected input: user handoff required for $nodeId"); continue }
                if (!node.isEditable) {
                    results.add("⚠️ $nodeId: Not editable")
                    continue
                }

                val service = OmniAccessibilityService.instance
                    ?: return ToolExecutionResult("Accessibility service is inactive.", isError = true)

                // Click the field first
                service.clickNode(node)
                delay(200)

                // Enter the value
                val typed = service.typeIntoNode(node, value)
                if (typed) {
                    results.add("✅ $nodeId ← \"$value\"")
                    successCount++
                } else {
                    // fallback: Shizuku
                    val shizukuResult = withContext(Dispatchers.IO) {
                        GodModeAccessibility.hybridType(value, node)
                    }
                    results.add("⚠️ $nodeId ← \"$value\" (Shizuku: $shizukuResult)")
                    successCount++
                }
                delay(150)
            }

            ToolExecutionResult(
                "📝 smart_fill: $successCount/${data.size} fields\n${results.joinToString("\n")}"
            )
        } catch (e: Exception) {
            ToolExecutionResult("❌ smart_fill error: ${e.message}", isError = true)
        }
    }

    private fun getNodeText(nodeId: String?): ToolExecutionResult {
        if (nodeId.isNullOrBlank()) {
            return ToolExecutionResult("Provide 'node_id'.", isError = true)
        }
        val node = lastParseResult?.nodeMap?.get(nodeId.uppercase())
            ?: return ToolExecutionResult("[$nodeId] Not found. Use dump_tree.", isError = true)
        if (node.isPassword || node.viewIdResourceName?.substringAfterLast('/') in setOf("pinEntry", "passwordEntry"))
            return ToolExecutionResult("Protected input: value unavailable.", true)

        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        return ToolExecutionResult("[$nodeId] text: \"$text\" | desc: \"$desc\"")
    }

    private fun describeNode(nodeId: String?): ToolExecutionResult {
        if (nodeId.isNullOrBlank()) {
            return ToolExecutionResult("Provide 'node_id'.", isError = true)
        }
        val node = lastParseResult?.nodeMap?.get(nodeId.uppercase())
            ?: return ToolExecutionResult("[$nodeId] Not found. Use dump_tree.", isError = true)
        if (node.isPassword || node.viewIdResourceName?.substringAfterLast('/') in setOf("pinEntry", "passwordEntry"))
            return ToolExecutionResult("Protected input: value unavailable.", true)

        val bounds = Rect().also { node.getBoundsInScreen(it) }
        return ToolExecutionResult(buildString {
            append("Description of [$nodeId]:\n")
            append("Class: ${node.className?.toString()?.substringAfterLast('.') ?: "Unknown"}\n")
            append("Text: ${node.text ?: "(none)"}\n")
            append("Description: ${node.contentDescription ?: "(none)"}\n")
            append("Clickable: ${node.isClickable}\n")
            append("Editable: ${node.isEditable}\n")
            append("Scrollable: ${node.isScrollable}\n")
            append("Enabled: ${node.isEnabled}\n")
            append("Visible: ${node.isVisibleToUser}\n")
            append("Center: (${bounds.centerX()}, ${bounds.centerY()})\n")
            append("Bounds: [${bounds.left}, ${bounds.top}] → [${bounds.right}, ${bounds.bottom}]")
        })
    }

    private fun verifyNode(nodeId: String?, expectedState: String?): ToolExecutionResult {
        if (nodeId.isNullOrBlank()) {
            return ToolExecutionResult("Provide 'node_id'.", isError = true)
        }
        val node = lastParseResult?.nodeMap?.get(nodeId.uppercase())
            ?: return ToolExecutionResult("[$nodeId] Not found. The UI may have changed; use dump_tree.", isError = true)
        if (node.isPassword || node.viewIdResourceName?.substringAfterLast('/') in setOf("pinEntry", "passwordEntry"))
            return ToolExecutionResult("Protected input: value unavailable.", true)

        val exists = true
        val stateMatch = when (expectedState?.lowercase()) {
            "checked"  -> node.isChecked
            "unchecked"-> !node.isChecked
            "focused"  -> node.isFocused
            "enabled"  -> node.isEnabled
            "disabled" -> !node.isEnabled
            "visible"  -> node.isVisibleToUser
            "clickable"-> node.isClickable
            "editable" -> node.isEditable
            null       -> true
            else       -> {
                val text = node.text?.toString() ?: ""
                val desc = node.contentDescription?.toString() ?: ""
                text.contains(expectedState, ignoreCase = true) ||
                        desc.contains(expectedState, ignoreCase = true)
            }
        }

        return ToolExecutionResult(
            if (stateMatch) "✅ [$nodeId] Verification passed${expectedState?.let { ": $it" } ?: ""}"
            else "❌ [$nodeId] Verification failed. Expected state: $expectedState",
            isError = !stateMatch
        )
    }

    private suspend fun executeChain(chainStepsJson: String?): ToolExecutionResult {
        if (chainStepsJson.isNullOrBlank()) {
            return ToolExecutionResult(
                "Provide 'chain_steps' as a JSON array: [{\"action\":\"click\",\"node_id\":\"N3\"},{\"action\":\"type\",\"node_id\":\"N4\",\"text\":\"hello\"}]",
                isError = true
            )
        }

        return try {
            val steps = parseJsonArrayOfMaps(chainStepsJson)
            val results = mutableListOf<String>()

            for ((index, step) in steps.withIndex()) {
                val stepAction = step["action"] ?: continue
                val stepResult = execute(stepAction, step)
                results.add("${index + 1}. [$stepAction] ${stepResult.output.take(80)}")
                if (stepResult.isError) {
                    return ToolExecutionResult(
                        "❌ Failed at step ${index + 1} ($stepAction):\n${results.joinToString("\n")}",
                        isError = true
                    )
                }
                delay(200)
            }

            ToolExecutionResult("✅ chain succeeded (${steps.size} steps):\n${results.joinToString("\n")}")
        } catch (e: Exception) {
            ToolExecutionResult("❌ chain error: ${e.message}", isError = true)
        }
    }

    // ── Enhanced basic actions ──────────────────────────────────────────

    private suspend fun clickNode(nodeId: String?, verifyChange: Boolean = true): ToolExecutionResult {
        if (nodeId.isNullOrBlank()) return ToolExecutionResult("Provide 'node_id'.", isError = true)
        val node = resolveNode(nodeId) ?: return ToolExecutionResult(
            "[$nodeId] Not found. Use dump_tree to refresh the tree.", isError = true)

        val preTime = AccessibilityStateManager.lastUpdateTime.value
        val service = OmniAccessibilityService.instance
            ?: return ToolExecutionResult("Accessibility service is inactive.", isError = true)

        val success = service.clickNode(node)
        if (!success) return ToolExecutionResult("❌ Failed to click [$nodeId].", isError = true)

        if (verifyChange) {
            delay(POST_ACTION_VERIFY_MS.toLong())
            val postTime = AccessibilityStateManager.lastUpdateTime.value
            val changed = postTime > preTime
            return ToolExecutionResult(
                if (changed) "✅ Clicked [$nodeId]; the UI changed"
                else "✅ Clicked [$nodeId]; the UI did not change, which may be expected"
            )
        }
        return ToolExecutionResult("✅ Clicked [$nodeId]")
    }

    private fun longClickNode(nodeId: String?): ToolExecutionResult {
        if (nodeId.isNullOrBlank()) return ToolExecutionResult("Provide 'node_id'.", isError = true)
        val node = resolveNode(nodeId) ?: return ToolExecutionResult("[$nodeId] Not found.", isError = true)
        val service = OmniAccessibilityService.instance
            ?: return ToolExecutionResult("Accessibility service is inactive.", isError = true)

        return if (service.longClickNode(node)) ToolExecutionResult("✅ Long-clicked [$nodeId]")
        else ToolExecutionResult("❌ Failed to long-click [$nodeId].", isError = true)
    }

    private suspend fun typeText(nodeId: String?, text: String?, clearFirst: Boolean): ToolExecutionResult {
        if (nodeId.isNullOrBlank()) return ToolExecutionResult("Provide 'node_id'.", isError = true)
        if (text == null) return ToolExecutionResult("Provide 'text'.", isError = true)
        val node = resolveNode(nodeId) ?: return ToolExecutionResult("[$nodeId] Not found.", isError = true)
        val service = OmniAccessibilityService.instance
            ?: return ToolExecutionResult("Accessibility service is inactive.", isError = true)

        if (node.isPassword) return ToolExecutionResult("Protected input: user handoff required.", isError = true)
        if (!node.isEditable) {
            return ToolExecutionResult("[$nodeId] Not editable. Use force_type instead.", isError = true)
        }

        if (clearFirst) {
            service.clickNode(node)
            delay(150)
            val clearBundle = android.os.Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, clearBundle)
            delay(100)
        }

        return if (service.typeIntoNode(node, text)) {
            ToolExecutionResult("✅ Typed into [$nodeId]: \"${text.take(50)}\"")
        } else {
            // Fallback: Shizuku
            service.clickNode(node)
            delay(200)
            val shizukuResult = withContext(Dispatchers.IO) {
                GodModeAccessibility.hybridType(text, node, clearFirst)
            }
            ToolExecutionResult("⚠️ Accessibility typing failed. $shizukuResult")
        }
    }

    private fun scrollNode(nodeId: String?, direction: String?): ToolExecutionResult {
        val forward = direction?.lowercase() != "backward" && direction?.lowercase() != "up"
        val service = OmniAccessibilityService.instance
            ?: return ToolExecutionResult("Accessibility service is inactive.", isError = true)

        if (!nodeId.isNullOrBlank()) {
            val node = resolveNode(nodeId) ?: return ToolExecutionResult("[$nodeId] Not found.", isError = true)
            return if (service.scrollNode(node, forward))
                ToolExecutionResult("✅ Scrolled [$nodeId] ${if (forward) "forward" else "backward"}")
            else ToolExecutionResult("❌ Failed to scroll [$nodeId].", isError = true)
        }

        val root = AccessibilityStateManager.rootNode.value
            ?: return ToolExecutionResult("No UI tree is available.", isError = true)
        val scrollable = findFirstScrollable(root)
            ?: return ToolExecutionResult("No scrollable element was found.", isError = true)
        return if (service.scrollNode(scrollable, forward))
            ToolExecutionResult("✅ Scrolled screen ${if (forward) "forward" else "backward"}")
        else ToolExecutionResult("❌ Scrolling failed.", isError = true)
    }

    private fun pressBack() = performGlobal("BACK") { OmniAccessibilityService.instance?.pressBack() }
    private fun pressHome() = performGlobal("HOME") { OmniAccessibilityService.instance?.pressHome() }
    private fun pressRecents() = performGlobal("RECENTS") { OmniAccessibilityService.instance?.pressRecents() }

    private fun performGlobal(name: String, action: () -> Boolean?): ToolExecutionResult {
        val service = OmniAccessibilityService.instance
            ?: return ToolExecutionResult("Accessibility service is inactive.", isError = true)
        return if (action() == true) ToolExecutionResult("✅ $name")
        else ToolExecutionResult("❌ $name failed.", isError = true)
    }

    private fun tapXY(x: String?, y: String?): ToolExecutionResult {
        val xVal = x?.toFloatOrNull() ?: return ToolExecutionResult("Invalid x coordinate.", isError = true)
        val yVal = y?.toFloatOrNull() ?: return ToolExecutionResult("Invalid y coordinate.", isError = true)
        val service = OmniAccessibilityService.instance
            ?: return ToolExecutionResult("Accessibility service is inactive.", isError = true)
        return if (service.tapAtCoordinates(xVal, yVal)) ToolExecutionResult("✅ Tapped at ($xVal, $yVal)")
        else ToolExecutionResult("❌ Tap failed.", isError = true)
    }

    private fun swipe(direction: String?, durationMs: String?, distanceRatio: String?, nodeId: String?): ToolExecutionResult {
        val service = OmniAccessibilityService.instance
            ?: return ToolExecutionResult("Accessibility service is inactive.", isError = true)
        val dir = direction?.lowercase() ?: "forward"
        val duration = durationMs?.toLongOrNull()?.coerceIn(120L, 2500L) ?: 320L
        val ratio = distanceRatio?.toFloatOrNull()?.coerceIn(0.1f, 0.9f) ?: 0.35f

        val area = if (nodeId.isNullOrBlank()) {
            val root = AccessibilityStateManager.rootNode.value
                ?: return ToolExecutionResult("No UI tree is available.", isError = true)
            Rect().apply { root.getBoundsInScreen(this) }
        } else {
            val node = resolveNode(nodeId) ?: return ToolExecutionResult("[$nodeId] Not found.", isError = true)
            Rect().apply { node.getBoundsInScreen(this) }
        }

        if (area.isEmpty) return ToolExecutionResult("Invalid bounds.", isError = true)

        val dx = area.width() * ratio; val dy = area.height() * ratio
        val cx = area.exactCenterX(); val cy = area.exactCenterY()
        val (sx, sy, ex, ey) = when (dir) {
            "backward", "up" -> arrayOf(cx, cy + dy, cx, cy - dy)
            "down" -> arrayOf(cx, cy - dy, cx, cy + dy)
            "left" -> arrayOf(cx + dx, cy, cx - dx, cy)
            "right" -> arrayOf(cx - dx, cy, cx + dx, cy)
            else -> arrayOf(cx, cy - dy, cx, cy + dy)
        }
        return if (service.swipeGesture(sx, sy, ex, ey, duration))
            ToolExecutionResult("✅ swipe $dir")
        else ToolExecutionResult("❌ Swipe failed.", isError = true)
    }

    private suspend fun autoEnable(): ToolExecutionResult {
        val result = GodModeAccessibility.autoEnableOmniVision()
        return ToolExecutionResult(result, isError = result.startsWith("❌"))
    }

    private suspend fun forceClick(nodeId: String?): ToolExecutionResult {
        if (nodeId.isNullOrBlank()) return ToolExecutionResult("Provide 'node_id'.", isError = true)
        val node = resolveNode(nodeId) ?: return ToolExecutionResult("[$nodeId] Not found.", isError = true)
        val result = withContext(Dispatchers.IO) { GodModeAccessibility.hybridTap(node) }
        return ToolExecutionResult(result, isError = result.startsWith("❌"))
    }

    private suspend fun forceLongClick(nodeId: String?): ToolExecutionResult {
        if (nodeId.isNullOrBlank()) return ToolExecutionResult("Provide 'node_id'.", isError = true)
        val node = resolveNode(nodeId) ?: return ToolExecutionResult("[$nodeId] Not found.", isError = true)
        val result = withContext(Dispatchers.IO) { GodModeAccessibility.hybridLongPress(node) }
        return ToolExecutionResult(result, isError = result.startsWith("❌"))
    }

    private suspend fun forceType(text: String?, nodeId: String?): ToolExecutionResult {
        if (text.isNullOrBlank()) return ToolExecutionResult("Provide 'text'.", isError = true)
        val fallbackNode = nodeId?.let { resolveNode(it) }
        if (com.omnidev.workspace.data.assistant.AssistantRuntime.targetingScreen && (fallbackNode == null || fallbackNode.isPassword))
            return ToolExecutionResult("A non-protected target node is required; hand protected inputs to the user.", isError = true)
        val result = withContext(Dispatchers.IO) { GodModeAccessibility.hybridType(text, fallbackNode) }
        return ToolExecutionResult(result, isError = result.startsWith("❌"))
    }

    private fun startRecording(macroName: String?): ToolExecutionResult {
        if (macroName.isNullOrBlank()) return ToolExecutionResult("Provide 'macro_name'.", isError = true)
        GodModeAccessibility.startRecording(macroName)
        return ToolExecutionResult("🎬 Started recording macro '$macroName'")
    }

    private fun stopRecording(macroName: String?): ToolExecutionResult {
        if (macroName.isNullOrBlank()) return ToolExecutionResult("Provide 'macro_name'.", isError = true)
        val count = GodModeAccessibility.stopRecording(macroName)
        return ToolExecutionResult("⏹️ Saved macro '$macroName': $count actions")
    }

    private suspend fun playMacro(macroName: String?): ToolExecutionResult {
        if (macroName.isNullOrBlank()) return ToolExecutionResult("Provide 'macro_name'.", isError = true)
        val result = GodModeAccessibility.playMacro(macroName)
        return ToolExecutionResult(result, isError = result.startsWith("❌"))
    }

    private fun listMacros(): ToolExecutionResult =
        ToolExecutionResult(GodModeAccessibility.listMacros())

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun resolveNode(nodeId: String): AccessibilityNodeInfo? =
        lastParseResult?.nodeMap?.get(nodeId.uppercase())

    private suspend fun waitForAccessibilityConnection() {
        if (AccessibilityStateManager.isServiceConnected.value) return
        withTimeoutOrNull(ACCESSIBILITY_SERVICE_MAX_WAIT_MS) {
            AccessibilityStateManager.isServiceConnected.first { it }
        }
    }

    private fun findFirstScrollable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findFirstScrollable(child)
            if (result != null) return result
        }
        return null
    }

    /** Parse a simple {key: value} JSON object */
    private fun parseJsonMap(json: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val cleaned = json.trim().removePrefix("{").removeSuffix("}")
        cleaned.split(",").forEach { entry ->
            val parts = entry.trim().split(":", limit = 2)
            if (parts.size == 2) {
                val key = parts[0].trim().removeSurrounding("\"")
                val value = parts[1].trim().removeSurrounding("\"")
                result[key] = value
            }
        }
        return result
    }

    /** Parse a simple [{key: value}] JSON array */
    private fun parseJsonArrayOfMaps(json: String): List<Map<String, String>> {
        val result = mutableListOf<Map<String, String>>()
        // Simple parsing without an external library
        val cleaned = json.trim().removePrefix("[").removeSuffix("]")
        var depth = 0
        var start = 0
        val objects = mutableListOf<String>()
        for (i in cleaned.indices) {
            when (cleaned[i]) {
                '{' -> { if (depth == 0) start = i; depth++ }
                '}' -> { depth--; if (depth == 0) objects.add(cleaned.substring(start, i + 1)) }
            }
        }
        objects.forEach { obj -> result.add(parseJsonMap(obj)) }
        return result
    }
}
