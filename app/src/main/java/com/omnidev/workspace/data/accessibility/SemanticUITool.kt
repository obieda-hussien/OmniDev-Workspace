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
 * SemanticUITool — System awareness note System awareness note System awareness note System awareness note
 *
 * System awareness note System awareness note: System awareness note System awareness note System awareness note System awareness note System awareness note
 * ─────────────────────────────────────────────────────────────────────────────
 * System awareness note System awareness note:
 * ─────────────────────────────────────────────────────────────────────────────
 * • `find_element` — System awareness note System awareness note System awareness note System awareness note System awareness note dump_tree System awareness note (System awareness note System awareness note)
 * • `wait_for`     — System awareness note System awareness note System awareness note System awareness note/System awareness note System awareness note System awareness note (timeout System awareness note System awareness note)
 * • `smart_fill`   — System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note
 * • `get_summary`  — System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note
 * • `get_text`     — System awareness note System awareness note System awareness note System awareness note System awareness note dump_tree
 * • `verify`       — System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note
 * • `chain`        — System awareness note System awareness note System awareness note System awareness note System awareness note
 * • `record_start` / `record_stop` / `macro_play` — System awareness note System awareness note
 * • `scroll_to`    — System awareness note System awareness note System awareness note System awareness note System awareness note
 * • `describe`     — System awareness note System awareness note System awareness note node_id System awareness note System awareness note System awareness note
 *
 * System awareness note System awareness note System awareness note System awareness note:
 * ─────────────────────────────────────────────────────────────────────────────
 * • System awareness note `click`  System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note
 * • `type` System awareness note System awareness note clear_first System awareness note System awareness note System awareness note
 * • `dump_tree` System awareness note System awareness note + System awareness note System awareness note + System awareness note System awareness note
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
System awareness note System awareness note System awareness note System awareness note System awareness note Android. System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note.
System awareness note System awareness note:

📋 System awareness note:
• dump_tree    — System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note
• get_summary  — System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note
• find_element — System awareness note System awareness note System awareness note System awareness note System awareness note
• get_text     — System awareness note System awareness note System awareness note System awareness note System awareness note node_id
• describe     — System awareness note System awareness note System awareness note System awareness note System awareness note
• verify       — System awareness note System awareness note System awareness note System awareness note

⚡ System awareness note:
• click / long_click — System awareness note System awareness note / System awareness note System awareness note node_id
• type          — System awareness note System awareness note System awareness note System awareness note System awareness note
• scroll        — System awareness note System awareness note
• smart_fill    — System awareness note System awareness note System awareness note System awareness note {nodeId: text}
• chain         — System awareness note System awareness note (tap+type+tap System awareness note System awareness note System awareness note)

⏱️ System awareness note:
• wait_for      — System awareness note System awareness note System awareness note System awareness note System awareness note
• scroll_to     — System awareness note System awareness note System awareness note System awareness note System awareness note

🔧 Shizuku God-Mode:
• force_click / force_long_click / force_type
• auto_enable   — System awareness note System awareness note System awareness note accessibility System awareness note

📼 System awareness note:
• record_start / record_stop / macro_play / macro_list

🌍 System awareness note:
• back / home / recents
• swipe / tap_xy
""".trimIndent(),
            parameters = listOf(
                ToolParameter("action", "string",
                    "System awareness note System awareness note (System awareness note System awareness note System awareness note)", required = true),
                ToolParameter("node_id", "string",
                    "System awareness note System awareness note System awareness note dump_tree (System awareness note N3). System awareness note System awareness note: click, type, scroll, etc."),
                ToolParameter("text", "string",
                    "System awareness note System awareness note (type/force_type) System awareness note System awareness note (find_element/wait_for)"),
                ToolParameter("direction", "string",
                    "System awareness note System awareness note/System awareness note: forward/backward/up/down/left/right"),
                ToolParameter("timeout_ms", "string",
                    "System awareness note System awareness note System awareness note System awareness note (System awareness note wait_for). System awareness note: 10000"),
                ToolParameter("clear_first", "string",
                    "true System awareness note System awareness note System awareness note System awareness note (System awareness note type)"),
                ToolParameter("verify_change", "string",
                    "false System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note"),
                ToolParameter("form_data", "string",
                    "JSON: {\"N1\":\"text1\",\"N2\":\"text2\"} System awareness note smart_fill"),
                ToolParameter("chain_steps", "string",
                    "JSON array System awareness note System awareness note System awareness note chain"),
                ToolParameter("macro_name", "string",
                    "System awareness note System awareness note System awareness note record_start/record_stop/macro_play"),
                ToolParameter("expected_state", "string",
                    "System awareness note System awareness note System awareness note (Checked/Focused/Enabled/etc.) System awareness note verify"),
                ToolParameter("duration_ms", "string", "System awareness note System awareness note swipe System awareness note System awareness note"),
                ToolParameter("distance_ratio", "string", "System awareness note System awareness note System awareness note swipe (0.1..0.9)"),
                ToolParameter("x", "string", "System awareness note X System awareness note tap_xy"),
                ToolParameter("y", "string", "System awareness note Y System awareness note tap_xy")
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
                        "⚠️ System awareness note System awareness note Accessibility System awareness note System awareness note. " +
                            "System awareness note System awareness note System awareness note → System awareness note System awareness note → OmniDev System awareness note " +
                            "System awareness note System awareness note System awareness note 'auto_enable'.",
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
                    "System awareness note System awareness note System awareness note: '$action'. System awareness note dump_tree System awareness note System awareness note.",
                    isError = true
                )
            }
        }

    // ── System awareness note ─────────────────────────────────────────────────────────────

    private fun dumpTree(): ToolExecutionResult {
        val root = AccessibilityStateManager.rootNode.value
            ?: return ToolExecutionResult("System awareness note System awareness note System awareness note System awareness note. System awareness note System awareness note System awareness note System awareness note.", isError = true)

        return try {
            val result = SemanticTreeParser.parse(
                root = root,
                packageName = AccessibilityStateManager.activePackage.value,
                activityName = AccessibilityStateManager.activeActivity.value
            )
            lastParseResult = result

            val output = buildString {
                append("📋 System awareness note: ${result.summary}\n\n")
                append(result.semanticTree)
                append("\n\n── System awareness note: ${result.extractedNodes} System awareness note System awareness note / ${result.totalRawNodes} System awareness note ──")

                if (result.detectedForms.isNotEmpty()) {
                    append("\n\n📝 System awareness note System awareness note:")
                    result.detectedForms.forEach { form ->
                        append("\n  • ${form.groupName}: ")
                        append(form.fields.joinToString(", ") { "[${it.nodeId}]${it.fieldType.name}" })
                    }
                }

                if (result.priorityOrder.isNotEmpty()) {
                    append("\n⭐ System awareness note System awareness note: ${result.priorityOrder.take(8).joinToString(" → ")}")
                }

                if (AccessibilityStateManager.shouldWaitForUI()) {
                    append("\n\n⏳ System awareness note System awareness note System awareness note. System awareness note System awareness note System awareness note System awareness note.")
                }
            }

            ToolExecutionResult(output, truncated = result.extractedNodes >= 150)
        } catch (e: Exception) {
            ToolExecutionResult("System awareness note System awareness note System awareness note: ${e.message}", isError = true)
        }
    }

    private fun getSummary(): ToolExecutionResult {
        return ToolExecutionResult(
            buildString {
                append(AccessibilityStateManager.buildContextSummary())
                val result = lastParseResult
                if (result != null) {
                    append("\n\n📋 System awareness note System awareness note System awareness note: ${result.extractedNodes} System awareness note")
                    if (result.detectedForms.isNotEmpty()) {
                        append("\n📝 System awareness note: ${result.detectedForms.joinToString(", ") { it.groupName }}")
                    }
                } else {
                    append("\n\n💡 System awareness note System awareness note System awareness note System awareness note. System awareness note dump_tree.")
                }
            }
        )
    }

    private fun findElement(text: String?): ToolExecutionResult {
        if (text.isNullOrBlank()) {
            return ToolExecutionResult("System awareness note System awareness note 'text' System awareness note.", isError = true)
        }
        val found = AccessibilityStateManager.findNodeByText(text)
            ?: return ToolExecutionResult("❌ System awareness note System awareness note System awareness note '$text' System awareness note System awareness note System awareness note.", isError = true)

        val bounds = Rect().also { found.getBoundsInScreen(it) }
        val className = found.className?.toString()?.substringAfterLast('.') ?: "View"
        val isClickable = found.isClickable
        val isEditable = found.isEditable

        return ToolExecutionResult(
            buildString {
                append("✅ System awareness note '$text'\n")
                append("System awareness note: $className\n")
                append("System awareness note: [${bounds.left}, ${bounds.top}, ${bounds.right}, ${bounds.bottom}]\n")
                if (isClickable) append("System awareness note System awareness note: System awareness note\n")
                if (isEditable) append("System awareness note System awareness note: System awareness note\n")
                append("\nSystem awareness note: System awareness note System awareness note dump_tree System awareness note System awareness note node_id System awareness note.")
            }
        )
    }

    private suspend fun waitFor(text: String?, timeoutMs: Long): ToolExecutionResult {
        if (text.isNullOrBlank()) {
            return ToolExecutionResult("System awareness note System awareness note 'text' System awareness note.", isError = true)
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
            ToolExecutionResult("✅ System awareness note '$text' System awareness note ${elapsed}ms")
        } else {
            ToolExecutionResult(
                "⏱️ System awareness note System awareness note '$text' System awareness note ${timeoutMs}ms. System awareness note System awareness note: ${AccessibilityStateManager.activePackage.value}",
                isError = true
            )
        }
    }

    private suspend fun scrollToText(text: String?, direction: String?): ToolExecutionResult {
        if (text.isNullOrBlank()) {
            return ToolExecutionResult("System awareness note System awareness note 'text' System awareness note System awareness note.", isError = true)
        }
        val result = withContext(Dispatchers.IO) {
            GodModeAccessibility.scrollUntilVisible(text, direction ?: "down")
        }
        return ToolExecutionResult(result.message, isError = !result.found)
    }

    private suspend fun smartFill(formDataJson: String?): ToolExecutionResult {
        if (formDataJson.isNullOrBlank()) {
            return ToolExecutionResult(
                "System awareness note System awareness note 'form_data' System awareness note JSON: {\"N1\":\"value1\",\"N2\":\"value2\"}",
                isError = true
            )
        }

        val parseResult = lastParseResult
            ?: return ToolExecutionResult("System awareness note dump_tree System awareness note System awareness note System awareness note node_ids.", isError = true)

        return try {
            val data = parseJsonMap(formDataJson)
            val results = mutableListOf<String>()
            var successCount = 0

            for ((nodeId, value) in data) {
                val node = parseResult.nodeMap[nodeId.uppercase()]
                if (node == null) {
                    results.add("⚠️ $nodeId: System awareness note System awareness note System awareness note")
                    continue
                }
                if (!node.isEditable) {
                    results.add("⚠️ $nodeId: System awareness note System awareness note System awareness note")
                    continue
                }

                val service = OmniAccessibilityService.instance
                    ?: return ToolExecutionResult("System awareness note System awareness note accessibility System awareness note System awareness note.", isError = true)

                // System awareness note System awareness note System awareness note System awareness note
                service.clickNode(node)
                delay(200)

                // System awareness note System awareness note
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
                "📝 smart_fill: $successCount/${data.size} System awareness note\n${results.joinToString("\n")}"
            )
        } catch (e: Exception) {
            ToolExecutionResult("❌ System awareness note System awareness note smart_fill: ${e.message}", isError = true)
        }
    }

    private fun getNodeText(nodeId: String?): ToolExecutionResult {
        if (nodeId.isNullOrBlank()) {
            return ToolExecutionResult("System awareness note System awareness note 'node_id'.", isError = true)
        }
        val node = lastParseResult?.nodeMap?.get(nodeId.uppercase())
            ?: return ToolExecutionResult("[$nodeId] System awareness note System awareness note. System awareness note dump_tree.", isError = true)

        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        return ToolExecutionResult("[$nodeId] text: \"$text\" | desc: \"$desc\"")
    }

    private fun describeNode(nodeId: String?): ToolExecutionResult {
        if (nodeId.isNullOrBlank()) {
            return ToolExecutionResult("System awareness note System awareness note 'node_id'.", isError = true)
        }
        val node = lastParseResult?.nodeMap?.get(nodeId.uppercase())
            ?: return ToolExecutionResult("[$nodeId] System awareness note System awareness note. System awareness note dump_tree.", isError = true)

        val bounds = Rect().also { node.getBoundsInScreen(it) }
        return ToolExecutionResult(buildString {
            append("System awareness note [$nodeId]:\n")
            append("System awareness note: ${node.className?.toString()?.substringAfterLast('.') ?: "Unknown"}\n")
            append("System awareness note: ${node.text ?: "(System awareness note System awareness note)"}\n")
            append("System awareness note: ${node.contentDescription ?: "(System awareness note System awareness note)"}\n")
            append("System awareness note System awareness note: ${node.isClickable}\n")
            append("System awareness note System awareness note: ${node.isEditable}\n")
            append("System awareness note System awareness note: ${node.isScrollable}\n")
            append("System awareness note: ${node.isEnabled}\n")
            append("System awareness note: ${node.isVisibleToUser}\n")
            append("System awareness note: (${bounds.centerX()}, ${bounds.centerY()})\n")
            append("System awareness note: [${bounds.left}, ${bounds.top}] → [${bounds.right}, ${bounds.bottom}]")
        })
    }

    private fun verifyNode(nodeId: String?, expectedState: String?): ToolExecutionResult {
        if (nodeId.isNullOrBlank()) {
            return ToolExecutionResult("System awareness note System awareness note 'node_id'.", isError = true)
        }
        val node = lastParseResult?.nodeMap?.get(nodeId.uppercase())
            ?: return ToolExecutionResult("[$nodeId] System awareness note System awareness note. System awareness note System awareness note System awareness note — System awareness note dump_tree.", isError = true)

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
            if (stateMatch) "✅ [$nodeId] System awareness note System awareness note${expectedState?.let { ": $it" } ?: ""}"
            else "❌ [$nodeId] System awareness note System awareness note. System awareness note System awareness note: $expectedState",
            isError = !stateMatch
        )
    }

    private suspend fun executeChain(chainStepsJson: String?): ToolExecutionResult {
        if (chainStepsJson.isNullOrBlank()) {
            return ToolExecutionResult(
                "System awareness note System awareness note 'chain_steps' System awareness note JSON array: [{\"action\":\"click\",\"node_id\":\"N3\"},{\"action\":\"type\",\"node_id\":\"N4\",\"text\":\"hello\"}]",
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
                        "❌ System awareness note System awareness note System awareness note ${index + 1} ($stepAction):\n${results.joinToString("\n")}",
                        isError = true
                    )
                }
                delay(200)
            }

            ToolExecutionResult("✅ chain System awareness note (${steps.size} System awareness note):\n${results.joinToString("\n")}")
        } catch (e: Exception) {
            ToolExecutionResult("❌ System awareness note System awareness note chain: ${e.message}", isError = true)
        }
    }

    // ── System awareness note System awareness note (System awareness note) ──────────────────────────────────────────

    private suspend fun clickNode(nodeId: String?, verifyChange: Boolean = true): ToolExecutionResult {
        if (nodeId.isNullOrBlank()) return ToolExecutionResult("System awareness note System awareness note 'node_id'.", isError = true)
        val node = resolveNode(nodeId) ?: return ToolExecutionResult(
            "[$nodeId] System awareness note System awareness note. System awareness note dump_tree System awareness note System awareness note.", isError = true)

        val preTime = AccessibilityStateManager.lastUpdateTime.value
        val service = OmniAccessibilityService.instance
            ?: return ToolExecutionResult("System awareness note System awareness note accessibility System awareness note System awareness note.", isError = true)

        val success = service.clickNode(node)
        if (!success) return ToolExecutionResult("❌ System awareness note System awareness note System awareness note [$nodeId].", isError = true)

        if (verifyChange) {
            delay(POST_ACTION_VERIFY_MS.toLong())
            val postTime = AccessibilityStateManager.lastUpdateTime.value
            val changed = postTime > preTime
            return ToolExecutionResult(
                if (changed) "✅ System awareness note System awareness note System awareness note [$nodeId] — System awareness note System awareness note"
                else "✅ System awareness note System awareness note System awareness note [$nodeId] — (System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note)"
            )
        }
        return ToolExecutionResult("✅ System awareness note System awareness note System awareness note [$nodeId]")
    }

    private fun longClickNode(nodeId: String?): ToolExecutionResult {
        if (nodeId.isNullOrBlank()) return ToolExecutionResult("System awareness note System awareness note 'node_id'.", isError = true)
        val node = resolveNode(nodeId) ?: return ToolExecutionResult("[$nodeId] System awareness note System awareness note.", isError = true)
        val service = OmniAccessibilityService.instance
            ?: return ToolExecutionResult("System awareness note System awareness note accessibility System awareness note System awareness note.", isError = true)

        return if (service.longClickNode(node)) ToolExecutionResult("✅ System awareness note System awareness note System awareness note [$nodeId]")
        else ToolExecutionResult("❌ System awareness note System awareness note System awareness note System awareness note [$nodeId].", isError = true)
    }

    private suspend fun typeText(nodeId: String?, text: String?, clearFirst: Boolean): ToolExecutionResult {
        if (nodeId.isNullOrBlank()) return ToolExecutionResult("System awareness note System awareness note 'node_id'.", isError = true)
        if (text == null) return ToolExecutionResult("System awareness note System awareness note 'text'.", isError = true)
        val node = resolveNode(nodeId) ?: return ToolExecutionResult("[$nodeId] System awareness note System awareness note.", isError = true)
        val service = OmniAccessibilityService.instance
            ?: return ToolExecutionResult("System awareness note System awareness note accessibility System awareness note System awareness note.", isError = true)

        if (!node.isEditable) {
            return ToolExecutionResult("[$nodeId] System awareness note System awareness note System awareness note. System awareness note force_type System awareness note System awareness note System awareness note.", isError = true)
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
            ToolExecutionResult("✅ System awareness note System awareness note [$nodeId]: \"${text.take(50)}\"")
        } else {
            // Fallback: Shizuku
            service.clickNode(node)
            delay(200)
            val shizukuResult = withContext(Dispatchers.IO) {
                GodModeAccessibility.hybridType(text, node, clearFirst)
            }
            ToolExecutionResult("⚠️ Accessibility type System awareness note. $shizukuResult")
        }
    }

    private fun scrollNode(nodeId: String?, direction: String?): ToolExecutionResult {
        val forward = direction?.lowercase() != "backward" && direction?.lowercase() != "up"
        val service = OmniAccessibilityService.instance
            ?: return ToolExecutionResult("System awareness note System awareness note accessibility System awareness note System awareness note.", isError = true)

        if (!nodeId.isNullOrBlank()) {
            val node = resolveNode(nodeId) ?: return ToolExecutionResult("[$nodeId] System awareness note System awareness note.", isError = true)
            return if (service.scrollNode(node, forward))
                ToolExecutionResult("✅ System awareness note [$nodeId] ${if (forward) "System awareness note" else "System awareness note"}")
            else ToolExecutionResult("❌ System awareness note System awareness note System awareness note [$nodeId].", isError = true)
        }

        val root = AccessibilityStateManager.rootNode.value
            ?: return ToolExecutionResult("System awareness note System awareness note System awareness note System awareness note.", isError = true)
        val scrollable = findFirstScrollable(root)
            ?: return ToolExecutionResult("System awareness note System awareness note System awareness note System awareness note System awareness note.", isError = true)
        return if (service.scrollNode(scrollable, forward))
            ToolExecutionResult("✅ System awareness note System awareness note ${if (forward) "System awareness note" else "System awareness note"}")
        else ToolExecutionResult("❌ System awareness note System awareness note.", isError = true)
    }

    private fun pressBack() = performGlobal("BACK") { OmniAccessibilityService.instance?.pressBack() }
    private fun pressHome() = performGlobal("HOME") { OmniAccessibilityService.instance?.pressHome() }
    private fun pressRecents() = performGlobal("RECENTS") { OmniAccessibilityService.instance?.pressRecents() }

    private fun performGlobal(name: String, action: () -> Boolean?): ToolExecutionResult {
        val service = OmniAccessibilityService.instance
            ?: return ToolExecutionResult("System awareness note System awareness note accessibility System awareness note System awareness note.", isError = true)
        return if (action() == true) ToolExecutionResult("✅ $name")
        else ToolExecutionResult("❌ System awareness note $name.", isError = true)
    }

    private fun tapXY(x: String?, y: String?): ToolExecutionResult {
        val xVal = x?.toFloatOrNull() ?: return ToolExecutionResult("x System awareness note System awareness note.", isError = true)
        val yVal = y?.toFloatOrNull() ?: return ToolExecutionResult("y System awareness note System awareness note.", isError = true)
        val service = OmniAccessibilityService.instance
            ?: return ToolExecutionResult("System awareness note System awareness note accessibility System awareness note System awareness note.", isError = true)
        return if (service.tapAtCoordinates(xVal, yVal)) ToolExecutionResult("✅ System awareness note System awareness note ($xVal, $yVal)")
        else ToolExecutionResult("❌ System awareness note System awareness note.", isError = true)
    }

    private fun swipe(direction: String?, durationMs: String?, distanceRatio: String?, nodeId: String?): ToolExecutionResult {
        val service = OmniAccessibilityService.instance
            ?: return ToolExecutionResult("System awareness note System awareness note accessibility System awareness note System awareness note.", isError = true)
        val dir = direction?.lowercase() ?: "forward"
        val duration = durationMs?.toLongOrNull()?.coerceIn(120L, 2500L) ?: 320L
        val ratio = distanceRatio?.toFloatOrNull()?.coerceIn(0.1f, 0.9f) ?: 0.35f

        val area = if (nodeId.isNullOrBlank()) {
            val root = AccessibilityStateManager.rootNode.value
                ?: return ToolExecutionResult("System awareness note System awareness note System awareness note System awareness note.", isError = true)
            Rect().apply { root.getBoundsInScreen(this) }
        } else {
            val node = resolveNode(nodeId) ?: return ToolExecutionResult("[$nodeId] System awareness note System awareness note.", isError = true)
            Rect().apply { node.getBoundsInScreen(this) }
        }

        if (area.isEmpty) return ToolExecutionResult("System awareness note System awareness note System awareness note.", isError = true)

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
        else ToolExecutionResult("❌ System awareness note System awareness note swipe.", isError = true)
    }

    private suspend fun autoEnable(): ToolExecutionResult {
        val result = GodModeAccessibility.autoEnableOmniVision()
        return ToolExecutionResult(result, isError = result.startsWith("❌"))
    }

    private suspend fun forceClick(nodeId: String?): ToolExecutionResult {
        if (nodeId.isNullOrBlank()) return ToolExecutionResult("System awareness note System awareness note 'node_id'.", isError = true)
        val node = resolveNode(nodeId) ?: return ToolExecutionResult("[$nodeId] System awareness note System awareness note.", isError = true)
        val result = withContext(Dispatchers.IO) { GodModeAccessibility.hybridTap(node) }
        return ToolExecutionResult(result, isError = result.startsWith("❌"))
    }

    private suspend fun forceLongClick(nodeId: String?): ToolExecutionResult {
        if (nodeId.isNullOrBlank()) return ToolExecutionResult("System awareness note System awareness note 'node_id'.", isError = true)
        val node = resolveNode(nodeId) ?: return ToolExecutionResult("[$nodeId] System awareness note System awareness note.", isError = true)
        val result = withContext(Dispatchers.IO) { GodModeAccessibility.hybridLongPress(node) }
        return ToolExecutionResult(result, isError = result.startsWith("❌"))
    }

    private suspend fun forceType(text: String?, nodeId: String?): ToolExecutionResult {
        if (text.isNullOrBlank()) return ToolExecutionResult("System awareness note System awareness note 'text'.", isError = true)
        val fallbackNode = nodeId?.let { resolveNode(it) }
        val result = withContext(Dispatchers.IO) { GodModeAccessibility.hybridType(text, fallbackNode) }
        return ToolExecutionResult(result, isError = result.startsWith("❌"))
    }

    private fun startRecording(macroName: String?): ToolExecutionResult {
        if (macroName.isNullOrBlank()) return ToolExecutionResult("System awareness note System awareness note 'macro_name'.", isError = true)
        GodModeAccessibility.startRecording(macroName)
        return ToolExecutionResult("🎬 System awareness note System awareness note System awareness note '$macroName'")
    }

    private fun stopRecording(macroName: String?): ToolExecutionResult {
        if (macroName.isNullOrBlank()) return ToolExecutionResult("System awareness note System awareness note 'macro_name'.", isError = true)
        val count = GodModeAccessibility.stopRecording(macroName)
        return ToolExecutionResult("⏹️ System awareness note System awareness note '$macroName': $count System awareness note")
    }

    private suspend fun playMacro(macroName: String?): ToolExecutionResult {
        if (macroName.isNullOrBlank()) return ToolExecutionResult("System awareness note System awareness note 'macro_name'.", isError = true)
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

    /** System awareness note JSON System awareness note System awareness note System awareness note {key: value} */
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

    /** System awareness note JSON array System awareness note System awareness note System awareness note [{key: value}] */
    private fun parseJsonArrayOfMaps(json: String): List<Map<String, String>> {
        val result = mutableListOf<Map<String, String>>()
        // System awareness note System awareness note System awareness note System awareness note System awareness note
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
