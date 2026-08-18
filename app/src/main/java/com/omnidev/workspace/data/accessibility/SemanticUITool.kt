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
 * SemanticUITool — Context note Context note Context note Context note
 *
 * Context note Context note: Context note Context note Context note Context note Context note
 * ─────────────────────────────────────────────────────────────────────────────
 * Context note Context note:
 * ─────────────────────────────────────────────────────────────────────────────
 * • `find_element` — Context note Context note Context note Context note Context note dump_tree Context note (Context note Context note)
 * • `wait_for`     — Context note Context note Context note Context note/Context note Context note Context note (timeout Context note Context note)
 * • `smart_fill`   — Context note Context note Context note Context note Context note Context note Context note Context note Context note
 * • `get_summary`  — Context note Context note Context note Context note Context note Context note
 * • `get_text`     — Context note Context note Context note Context note Context note dump_tree
 * • `verify`       — Context note Context note Context note Context note Context note Context note Context note
 * • `chain`        — Context note Context note Context note Context note Context note
 * • `record_start` / `record_stop` / `macro_play` — Context note Context note
 * • `scroll_to`    — Context note Context note Context note Context note Context note
 * • `describe`     — Context note Context note Context note node_id Context note Context note Context note
 *
 * Context note Context note Context note Context note:
 * ─────────────────────────────────────────────────────────────────────────────
 * • Context note `click`  Context note Context note Context note Context note Context note Context note
 * • `type` Context note Context note clear_first Context note Context note Context note
 * • `dump_tree` Context note Context note + Context note Context note + Context note Context note
 */
object SemanticUITool {

    private const val ACCESSIBILITY_SERVICE_MAX_WAIT_MS = 7_000L
    private const val DEFAULT_WAIT_TIMEOUT_MS = 10_000L
    private const val POST_ACTION_VERIFY_MS = 700L

    @Volatile
    private var lastParseResult: SemanticTreeParser.ParseResult? = null

    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "semantic_ui",
            description = """
Info Info Info Info Info Android. Info Info Info Info Info Info.
Info Info:

📋 Info:
• dump_tree    — Info Info Info Info Info Info
• get_summary  — Info Info Info Info Info Info
• find_element — Info Info Info Info Info
• get_text     — Info Info Info Info Info node_id
• describe     — Info Info Info Info Info
• verify       — Info Info Info Info

⚡ Info:
• click / long_click — Info Info / Info Info node_id
• type          — Info Info Info Info Info
• scroll        — Info Info
• smart_fill    — Info Info Info Info {nodeId: text}
• chain         — Info Info (tap+type+tap Info Info Info)

⏱️ Info:
• wait_for      — Info Info Info Info Info
• scroll_to     — Info Info Info Info Info

🔧 Shizuku God-Mode:
• force_click / force_long_click / force_type
• auto_enable   — Info Info Info accessibility Info

📼 Info:
• record_start / record_stop / macro_play / macro_list

🌍 Info:
• back / home / recents
• swipe / tap_xy
""".trimIndent(),
            parameters = listOf(
                ToolParameter("action", "string",
                    "Info Info (Info Info Info)", required = true),
                ToolParameter("node_id", "string",
                    "Info Info Info dump_tree (Info N3). Info Info: click, type, scroll, etc."),
                ToolParameter("text", "string",
                    "Info Info (type/force_type) Info Info (find_element/wait_for)"),
                ToolParameter("direction", "string",
                    "Info Info/Info: forward/backward/up/down/left/right"),
                ToolParameter("timeout_ms", "string",
                    "Info Info Info Info (Info wait_for). Info: 10000"),
                ToolParameter("clear_first", "string",
                    "true Info Info Info Info (Info type)"),
                ToolParameter("verify_change", "string",
                    "false Info Info Info Info Info Info Info"),
                ToolParameter("form_data", "string",
                    "JSON: {\"N1\":\"text1\",\"N2\":\"text2\"} Info smart_fill"),
                ToolParameter("chain_steps", "string",
                    "JSON array Info Info Info chain"),
                ToolParameter("macro_name", "string",
                    "Info Info Info record_start/record_stop/macro_play"),
                ToolParameter("expected_state", "string",
                    "Info Info Info (Checked/Focused/Enabled/etc.) Info verify"),
                ToolParameter("duration_ms", "string", "Info Info swipe Info Info"),
                ToolParameter("distance_ratio", "string", "Info Info Info swipe (0.1..0.9)"),
                ToolParameter("x", "string", "Info X Info tap_xy"),
                ToolParameter("y", "string", "Info Y Info tap_xy")
            )
        )
    )

    suspend fun execute(action: String, params: Map<String, String>): ToolExecutionResult =
        withContext(Dispatchers.Main) {
            if (action.lowercase() == "auto_enable") return@withContext autoEnable()

            if (!AccessibilityStateManager.isServiceConnected.value) {
                if (ShizukuCommandTool.isAvailable() && ShizukuCommandTool.hasPermission()) {
                    GodModeAccessibility.autoEnableOmniVision()
                    waitForAccessibilityConnection()
                }
                if (!AccessibilityStateManager.isServiceConnected.value) {
                    return@withContext ToolExecutionResult(
                        "⚠️ Info Info Accessibility Info Info. " +
                            "Info Info Info → Info Info → OmniDev Info " +
                            "Info Info Info 'auto_enable'.",
                        isError = true
                    )
                }
            }

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
                    "Info Info Info: '$action'. Info dump_tree Info Info.",
                    isError = true
                )
            }
        }

    // ── Context note ─────────────────────────────────────────────────────────────

    private fun dumpTree(): ToolExecutionResult {
        val root = AccessibilityStateManager.rootNode.value
            ?: return ToolExecutionResult("Info Info Info Info. Info Info Info Info.", isError = true)

        return try {
            val result = SemanticTreeParser.parse(
                root = root,
                packageName = AccessibilityStateManager.activePackage.value,
                activityName = AccessibilityStateManager.activeActivity.value
            )
            lastParseResult = result

            val output = buildString {
                append("📋 Info: ${result.summary}\n\n")
                append(result.semanticTree)
                append("\n\n── Info: ${result.extractedNodes} Info Info / ${result.totalRawNodes} Info ──")

                if (result.detectedForms.isNotEmpty()) {
                    append("\n\n📝 Info Info:")
                    result.detectedForms.forEach { form ->
                        append("\n  • ${form.groupName}: ")
                        append(form.fields.joinToString(", ") { "[${it.nodeId}]${it.fieldType.name}" })
                    }
                }

                if (result.priorityOrder.isNotEmpty()) {
                    append("\n⭐ Info Info: ${result.priorityOrder.take(8).joinToString(" → ")}")
                }

                if (AccessibilityStateManager.shouldWaitForUI()) {
                    append("\n\n⏳ Info Info Info. Info Info Info Info.")
                }
            }

            ToolExecutionResult(output, truncated = result.extractedNodes >= 150)
        } catch (e: Exception) {
            ToolExecutionResult("Info Info Info: ${e.message}", isError = true)
        }
    }

    private fun getSummary(): ToolExecutionResult {
        return ToolExecutionResult(
            buildString {
                append(AccessibilityStateManager.buildContextSummary())
                val result = lastParseResult
                if (result != null) {
                    append("\n\n📋 Info Info Info: ${result.extractedNodes} Info")
                    if (result.detectedForms.isNotEmpty()) {
                        append("\n📝 Info: ${result.detectedForms.joinToString(", ") { it.groupName }}")
                    }
                } else {
                    append("\n\n💡 Info Info Info Info. Info dump_tree.")
                }
            }
        )
    }

    private fun findElement(text: String?): ToolExecutionResult {
        if (text.isNullOrBlank()) {
            return ToolExecutionResult("Info Info 'text' Info.", isError = true)
        }
        val found = AccessibilityStateManager.findNodeByText(text)
            ?: return ToolExecutionResult("❌ Info Info Info '$text' Info Info Info.", isError = true)

        val bounds = Rect().also { found.getBoundsInScreen(it) }
        val className = found.className?.toString()?.substringAfterLast('.') ?: "View"
        val isClickable = found.isClickable
        val isEditable = found.isEditable

        return ToolExecutionResult(
            buildString {
                append("✅ Info '$text'\n")
                append("Info: $className\n")
                append("Info: [${bounds.left}, ${bounds.top}, ${bounds.right}, ${bounds.bottom}]\n")
                if (isClickable) append("Info Info: Info\n")
                if (isEditable) append("Info Info: Info\n")
                append("\nInfo: Info Info dump_tree Info Info node_id Info.")
            }
        )
    }

    private suspend fun waitFor(text: String?, timeoutMs: Long): ToolExecutionResult {
        if (text.isNullOrBlank()) {
            return ToolExecutionResult("Info Info 'text' Info.", isError = true)
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
            ToolExecutionResult("✅ Info '$text' Info ${elapsed}ms")
        } else {
            ToolExecutionResult(
                "⏱️ Info Info '$text' Info ${timeoutMs}ms. Info Info: ${AccessibilityStateManager.activePackage.value}",
                isError = true
            )
        }
    }

    private suspend fun scrollToText(text: String?, direction: String?): ToolExecutionResult {
        if (text.isNullOrBlank()) {
            return ToolExecutionResult("Info Info 'text' Info Info.", isError = true)
        }
        val result = withContext(Dispatchers.IO) {
            GodModeAccessibility.scrollUntilVisible(text, direction ?: "down")
        }
        return ToolExecutionResult(result.message, isError = !result.found)
    }

    private suspend fun smartFill(formDataJson: String?): ToolExecutionResult {
        if (formDataJson.isNullOrBlank()) {
            return ToolExecutionResult(
                "Info Info 'form_data' Info JSON: {\"N1\":\"value1\",\"N2\":\"value2\"}",
                isError = true
            )
        }

        val parseResult = lastParseResult
            ?: return ToolExecutionResult("Info dump_tree Info Info Info node_ids.", isError = true)

        return try {
            val data = parseJsonMap(formDataJson)
            val results = mutableListOf<String>()
            var successCount = 0

            for ((nodeId, value) in data) {
                val node = parseResult.nodeMap[nodeId.uppercase()]
                if (node == null) {
                    results.add("⚠️ $nodeId: Info Info Info")
                    continue
                }
                if (!node.isEditable) {
                    results.add("⚠️ $nodeId: Info Info Info")
                    continue
                }

                val service = OmniAccessibilityService.instance
                    ?: return ToolExecutionResult("Info Info accessibility Info Info.", isError = true)

                // Context note Context note Context note Context note
                service.clickNode(node)
                delay(200)

                // Context note Context note
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
                "📝 smart_fill: $successCount/${data.size} Info\n${results.joinToString("\n")}"
            )
        } catch (e: Exception) {
            ToolExecutionResult("❌ Info Info smart_fill: ${e.message}", isError = true)
        }
    }

    private fun getNodeText(nodeId: String?): ToolExecutionResult {
        if (nodeId.isNullOrBlank()) {
            return ToolExecutionResult("Info Info 'node_id'.", isError = true)
        }
        val node = lastParseResult?.nodeMap?.get(nodeId.uppercase())
            ?: return ToolExecutionResult("[$nodeId] Info Info. Info dump_tree.", isError = true)

        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        return ToolExecutionResult("[$nodeId] text: \"$text\" | desc: \"$desc\"")
    }

    private fun describeNode(nodeId: String?): ToolExecutionResult {
        if (nodeId.isNullOrBlank()) {
            return ToolExecutionResult("Info Info 'node_id'.", isError = true)
        }
        val node = lastParseResult?.nodeMap?.get(nodeId.uppercase())
            ?: return ToolExecutionResult("[$nodeId] Info Info. Info dump_tree.", isError = true)

        val bounds = Rect().also { node.getBoundsInScreen(it) }
        return ToolExecutionResult(buildString {
            append("Info [$nodeId]:\n")
            append("Info: ${node.className?.toString()?.substringAfterLast('.') ?: "Unknown"}\n")
            append("Info: ${node.text ?: "(Info Info)"}\n")
            append("Info: ${node.contentDescription ?: "(Info Info)"}\n")
            append("Info Info: ${node.isClickable}\n")
            append("Info Info: ${node.isEditable}\n")
            append("Info Info: ${node.isScrollable}\n")
            append("Info: ${node.isEnabled}\n")
            append("Info: ${node.isVisibleToUser}\n")
            append("Info: (${bounds.centerX()}, ${bounds.centerY()})\n")
            append("Info: [${bounds.left}, ${bounds.top}] → [${bounds.right}, ${bounds.bottom}]")
        })
    }

    private fun verifyNode(nodeId: String?, expectedState: String?): ToolExecutionResult {
        if (nodeId.isNullOrBlank()) {
            return ToolExecutionResult("Info Info 'node_id'.", isError = true)
        }
        val node = lastParseResult?.nodeMap?.get(nodeId.uppercase())
            ?: return ToolExecutionResult("[$nodeId] Info Info. Info Info Info — Info dump_tree.", isError = true)

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
            if (stateMatch) "✅ [$nodeId] Info Info${expectedState?.let { ": $it" } ?: ""}"
            else "❌ [$nodeId] Info Info. Info Info: $expectedState",
            isError = !stateMatch
        )
    }

    private suspend fun executeChain(chainStepsJson: String?): ToolExecutionResult {
        if (chainStepsJson.isNullOrBlank()) {
            return ToolExecutionResult(
                "Info Info 'chain_steps' Info JSON array: [{\"action\":\"click\",\"node_id\":\"N3\"},{\"action\":\"type\",\"node_id\":\"N4\",\"text\":\"hello\"}]",
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
                        "❌ Info Info Info ${index + 1} ($stepAction):\n${results.joinToString("\n")}",
                        isError = true
                    )
                }
                delay(200)
            }

            ToolExecutionResult("✅ chain Info (${steps.size} Info):\n${results.joinToString("\n")}")
        } catch (e: Exception) {
            ToolExecutionResult("❌ Info Info chain: ${e.message}", isError = true)
        }
    }

    // ── Context note Context note (Context note) ──────────────────────────────────────────

    private suspend fun clickNode(nodeId: String?, verifyChange: Boolean = true): ToolExecutionResult {
        if (nodeId.isNullOrBlank()) return ToolExecutionResult("Info Info 'node_id'.", isError = true)
        val node = resolveNode(nodeId) ?: return ToolExecutionResult(
            "[$nodeId] Info Info. Info dump_tree Info Info.", isError = true)

        val preTime = AccessibilityStateManager.lastUpdateTime.value
        val service = OmniAccessibilityService.instance
            ?: return ToolExecutionResult("Info Info accessibility Info Info.", isError = true)

        val success = service.clickNode(node)
        if (!success) return ToolExecutionResult("❌ Info Info Info [$nodeId].", isError = true)

        if (verifyChange) {
            delay(POST_ACTION_VERIFY_MS.toLong())
            val postTime = AccessibilityStateManager.lastUpdateTime.value
            val changed = postTime > preTime
            return ToolExecutionResult(
                if (changed) "✅ Info Info Info [$nodeId] — Info Info"
                else "✅ Info Info Info [$nodeId] — (Info Info Info Info Info Info Info Info)"
            )
        }
        return ToolExecutionResult("✅ Info Info Info [$nodeId]")
    }

    private fun longClickNode(nodeId: String?): ToolExecutionResult {
        if (nodeId.isNullOrBlank()) return ToolExecutionResult("Info Info 'node_id'.", isError = true)
        val node = resolveNode(nodeId) ?: return ToolExecutionResult("[$nodeId] Info Info.", isError = true)
        val service = OmniAccessibilityService.instance
            ?: return ToolExecutionResult("Info Info accessibility Info Info.", isError = true)

        return if (service.longClickNode(node)) ToolExecutionResult("✅ Info Info Info [$nodeId]")
        else ToolExecutionResult("❌ Info Info Info Info [$nodeId].", isError = true)
    }

    private suspend fun typeText(nodeId: String?, text: String?, clearFirst: Boolean): ToolExecutionResult {
        if (nodeId.isNullOrBlank()) return ToolExecutionResult("Info Info 'node_id'.", isError = true)
        if (text == null) return ToolExecutionResult("Info Info 'text'.", isError = true)
        val node = resolveNode(nodeId) ?: return ToolExecutionResult("[$nodeId] Info Info.", isError = true)
        val service = OmniAccessibilityService.instance
            ?: return ToolExecutionResult("Info Info accessibility Info Info.", isError = true)

        if (!node.isEditable) {
            return ToolExecutionResult("[$nodeId] Info Info Info. Info force_type Info Info Info.", isError = true)
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
            ToolExecutionResult("✅ Info Info [$nodeId]: \"${text.take(50)}\"")
        } else {
            // Fallback: Shizuku
            service.clickNode(node)
            delay(200)
            val shizukuResult = withContext(Dispatchers.IO) {
                GodModeAccessibility.hybridType(text, node, clearFirst)
            }
            ToolExecutionResult("⚠️ Accessibility type Info. $shizukuResult")
        }
    }

    private fun scrollNode(nodeId: String?, direction: String?): ToolExecutionResult {
        val forward = direction?.lowercase() != "backward" && direction?.lowercase() != "up"
        val service = OmniAccessibilityService.instance
            ?: return ToolExecutionResult("Info Info accessibility Info Info.", isError = true)

        if (!nodeId.isNullOrBlank()) {
            val node = resolveNode(nodeId) ?: return ToolExecutionResult("[$nodeId] Info Info.", isError = true)
            return if (service.scrollNode(node, forward))
                ToolExecutionResult("✅ Info [$nodeId] ${if (forward) "Info" else "Info"}")
            else ToolExecutionResult("❌ Info Info Info [$nodeId].", isError = true)
        }

        val root = AccessibilityStateManager.rootNode.value
            ?: return ToolExecutionResult("Info Info Info Info.", isError = true)
        val scrollable = findFirstScrollable(root)
            ?: return ToolExecutionResult("Info Info Info Info Info.", isError = true)
        return if (service.scrollNode(scrollable, forward))
            ToolExecutionResult("✅ Info Info ${if (forward) "Info" else "Info"}")
        else ToolExecutionResult("❌ Info Info.", isError = true)
    }

    private fun pressBack() = performGlobal("BACK") { OmniAccessibilityService.instance?.pressBack() }
    private fun pressHome() = performGlobal("HOME") { OmniAccessibilityService.instance?.pressHome() }
    private fun pressRecents() = performGlobal("RECENTS") { OmniAccessibilityService.instance?.pressRecents() }

    private fun performGlobal(name: String, action: () -> Boolean?): ToolExecutionResult {
        val service = OmniAccessibilityService.instance
            ?: return ToolExecutionResult("Info Info accessibility Info Info.", isError = true)
        return if (action() == true) ToolExecutionResult("✅ $name")
        else ToolExecutionResult("❌ Info $name.", isError = true)
    }

    private fun tapXY(x: String?, y: String?): ToolExecutionResult {
        val xVal = x?.toFloatOrNull() ?: return ToolExecutionResult("x Info Info.", isError = true)
        val yVal = y?.toFloatOrNull() ?: return ToolExecutionResult("y Info Info.", isError = true)
        val service = OmniAccessibilityService.instance
            ?: return ToolExecutionResult("Info Info accessibility Info Info.", isError = true)
        return if (service.tapAtCoordinates(xVal, yVal)) ToolExecutionResult("✅ Info Info ($xVal, $yVal)")
        else ToolExecutionResult("❌ Info Info.", isError = true)
    }

    private fun swipe(direction: String?, durationMs: String?, distanceRatio: String?, nodeId: String?): ToolExecutionResult {
        val service = OmniAccessibilityService.instance
            ?: return ToolExecutionResult("Info Info accessibility Info Info.", isError = true)
        val dir = direction?.lowercase() ?: "forward"
        val duration = durationMs?.toLongOrNull()?.coerceIn(120L, 2500L) ?: 320L
        val ratio = distanceRatio?.toFloatOrNull()?.coerceIn(0.1f, 0.9f) ?: 0.35f

        val area = if (nodeId.isNullOrBlank()) {
            val root = AccessibilityStateManager.rootNode.value
                ?: return ToolExecutionResult("Info Info Info Info.", isError = true)
            Rect().apply { root.getBoundsInScreen(this) }
        } else {
            val node = resolveNode(nodeId) ?: return ToolExecutionResult("[$nodeId] Info Info.", isError = true)
            Rect().apply { node.getBoundsInScreen(this) }
        }

        if (area.isEmpty) return ToolExecutionResult("Info Info Info.", isError = true)

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
        else ToolExecutionResult("❌ Info Info swipe.", isError = true)
    }

    private suspend fun autoEnable(): ToolExecutionResult {
        val result = GodModeAccessibility.autoEnableOmniVision()
        return ToolExecutionResult(result, isError = result.startsWith("❌"))
    }

    private suspend fun forceClick(nodeId: String?): ToolExecutionResult {
        if (nodeId.isNullOrBlank()) return ToolExecutionResult("Info Info 'node_id'.", isError = true)
        val node = resolveNode(nodeId) ?: return ToolExecutionResult("[$nodeId] Info Info.", isError = true)
        val result = withContext(Dispatchers.IO) { GodModeAccessibility.hybridTap(node) }
        return ToolExecutionResult(result, isError = result.startsWith("❌"))
    }

    private suspend fun forceLongClick(nodeId: String?): ToolExecutionResult {
        if (nodeId.isNullOrBlank()) return ToolExecutionResult("Info Info 'node_id'.", isError = true)
        val node = resolveNode(nodeId) ?: return ToolExecutionResult("[$nodeId] Info Info.", isError = true)
        val result = withContext(Dispatchers.IO) { GodModeAccessibility.hybridLongPress(node) }
        return ToolExecutionResult(result, isError = result.startsWith("❌"))
    }

    private suspend fun forceType(text: String?, nodeId: String?): ToolExecutionResult {
        if (text.isNullOrBlank()) return ToolExecutionResult("Info Info 'text'.", isError = true)
        val fallbackNode = nodeId?.let { resolveNode(it) }
        val result = withContext(Dispatchers.IO) { GodModeAccessibility.hybridType(text, fallbackNode) }
        return ToolExecutionResult(result, isError = result.startsWith("❌"))
    }

    private fun startRecording(macroName: String?): ToolExecutionResult {
        if (macroName.isNullOrBlank()) return ToolExecutionResult("Info Info 'macro_name'.", isError = true)
        GodModeAccessibility.startRecording(macroName)
        return ToolExecutionResult("🎬 Info Info Info '$macroName'")
    }

    private fun stopRecording(macroName: String?): ToolExecutionResult {
        if (macroName.isNullOrBlank()) return ToolExecutionResult("Info Info 'macro_name'.", isError = true)
        val count = GodModeAccessibility.stopRecording(macroName)
        return ToolExecutionResult("⏹️ Info Info '$macroName': $count Info")
    }

    private suspend fun playMacro(macroName: String?): ToolExecutionResult {
        if (macroName.isNullOrBlank()) return ToolExecutionResult("Info Info 'macro_name'.", isError = true)
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

    /** Context note JSON Context note Context note Context note {key: value} */
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

    /** Context note JSON array Context note Context note Context note [{key: value}] */
    private fun parseJsonArrayOfMaps(json: String): List<Map<String, String>> {
        val result = mutableListOf<Map<String, String>>()
        // Context note Context note Context note Context note Context note
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
