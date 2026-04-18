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
 * SemanticUITool — أداة التحكم الدلالي المتقدمة
 *
 * الجيل الثاني: ذكاء اصطناعي كامل في التفاعل
 * ─────────────────────────────────────────────────────────────────────────────
 * إجراءات جديدة:
 * ─────────────────────────────────────────────────────────────────────────────
 * • `find_element` — يبحث عن عنصر بالنص بدون dump_tree كامل (أسرع بكثير)
 * • `wait_for`     — ينتظر حتى يظهر نص/عنصر على الشاشة (timeout قابل للضبط)
 * • `smart_fill`   — يملأ نموذجاً كاملاً دفعةً واحدة بناءً على كشف النموذج
 * • `get_summary`  — يُرجع الملخص التنفيذي بدون شجرة كاملة
 * • `get_text`     — يستخرج النص من عقدة بدون dump_tree
 * • `verify`       — يتحقق أن عقدة موجودة وتملك الحالة المتوقعة
 * • `chain`        — تسلسل إجراءات في استدعاء واحد
 * • `record_start` / `record_stop` / `macro_play` — نظام الماكرو
 * • `scroll_to`    — تمرير ذكي حتى ظهور نص
 * • `describe`     — يصف العنصر بالـ node_id وصفاً دلالياً كاملاً
 *
 * تحسينات على الإجراءات القديمة:
 * ─────────────────────────────────────────────────────────────────────────────
 * • كل `click`  الآن يتحقق أن الواجهة تغيّرت بعده
 * • `type` الآن يدعم clear_first لمسح النص القديم
 * • `dump_tree` يُرجع الملخص + كشف النماذج + عناصر التنقل
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
أداة التحكم الدلالي الأقوى لـ Android. تتيح للوكيل رؤية الشاشة والتفاعل معها.
الإجراءات المتاحة:

📋 القراءة:
• dump_tree    — الشجرة الكاملة مع كشف النماذج والتنقل
• get_summary  — ملخص سريع للشاشة بدون الشجرة الكاملة
• find_element — ابحث عن عنصر بنصه مباشرة
• get_text     — استخرج نص من عقدة بـ node_id
• describe     — صف عنصراً وصفاً دلالياً شاملاً
• verify       — تحقق من حالة عنصر

⚡ التفاعل:
• click / long_click — نقر عادي / طويل بالـ node_id
• type          — كتابة نص في حقل إدخال
• scroll        — تمرير عقدة
• smart_fill    — ملء نموذج كامل بمجموعة {nodeId: text}
• chain         — تسلسل إجراءات (tap+type+tap في خطوة واحدة)

⏱️ الانتظار:
• wait_for      — انتظر ظهور نص على الشاشة
• scroll_to     — مرّر حتى يظهر نص مستهدف

🔧 Shizuku God-Mode:
• force_click / force_long_click / force_type
• auto_enable   — تفعيل خدمة الـ accessibility تلقائياً

📼 الماكرو:
• record_start / record_stop / macro_play / macro_list

🌍 التنقل:
• back / home / recents
• swipe / tap_xy
""".trimIndent(),
            parameters = listOf(
                ToolParameter("action", "string",
                    "الإجراء المطلوب (انظر الوصف أعلاه)", required = true),
                ToolParameter("node_id", "string",
                    "معرّف العقدة من dump_tree (مثل N3). مطلوب لـ: click, type, scroll, etc."),
                ToolParameter("text", "string",
                    "نص للكتابة (type/force_type) أو للبحث (find_element/wait_for)"),
                ToolParameter("direction", "string",
                    "اتجاه التمرير/السحب: forward/backward/up/down/left/right"),
                ToolParameter("timeout_ms", "string",
                    "مهلة الانتظار بالمللي ثانية (لـ wait_for). افتراضي: 10000"),
                ToolParameter("clear_first", "string",
                    "true لمسح الحقل قبل الكتابة (لـ type)"),
                ToolParameter("verify_change", "string",
                    "false لتعطيل التحقق من تغيير الواجهة بعد النقر"),
                ToolParameter("form_data", "string",
                    "JSON: {\"N1\":\"text1\",\"N2\":\"text2\"} لـ smart_fill"),
                ToolParameter("chain_steps", "string",
                    "JSON array للإجراءات المتسلسلة لـ chain"),
                ToolParameter("macro_name", "string",
                    "اسم الماكرو لـ record_start/record_stop/macro_play"),
                ToolParameter("expected_state", "string",
                    "الحالة المتوقعة للتحقق (Checked/Focused/Enabled/etc.) لـ verify"),
                ToolParameter("duration_ms", "string", "مدة الـ swipe بالمللي ثانية"),
                ToolParameter("distance_ratio", "string", "نسبة مسافة الـ swipe (0.1..0.9)"),
                ToolParameter("x", "string", "إحداثي X لـ tap_xy"),
                ToolParameter("y", "string", "إحداثي Y لـ tap_xy")
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
                        "⚠️ خدمة الـ Accessibility غير مفعّلة. " +
                            "اذهب إلى الإعدادات → إمكانية الوصول → OmniDev وفعّلها، " +
                            "أو استخدم الإجراء 'auto_enable'.",
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
                    "إجراء غير معروف: '$action'. استخدم dump_tree لبدء التفاعل.",
                    isError = true
                )
            }
        }

    // ── الإجراءات ─────────────────────────────────────────────────────────────

    private fun dumpTree(): ToolExecutionResult {
        val root = AccessibilityStateManager.rootNode.value
            ?: return ToolExecutionResult("لا تتوفر شجرة واجهة. قد تكون الشاشة مطفأة.", isError = true)

        return try {
            val result = SemanticTreeParser.parse(
                root = root,
                packageName = AccessibilityStateManager.activePackage.value,
                activityName = AccessibilityStateManager.activeActivity.value
            )
            lastParseResult = result

            val output = buildString {
                append("📋 الملخص: ${result.summary}\n\n")
                append(result.semanticTree)
                append("\n\n── إحصاءات: ${result.extractedNodes} عقدة دلالية / ${result.totalRawNodes} إجمالي ──")

                if (result.detectedForms.isNotEmpty()) {
                    append("\n\n📝 نماذج مكتشفة:")
                    result.detectedForms.forEach { form ->
                        append("\n  • ${form.groupName}: ")
                        append(form.fields.joinToString(", ") { "[${it.nodeId}]${it.fieldType.name}" })
                    }
                }

                if (result.priorityOrder.isNotEmpty()) {
                    append("\n⭐ أولوية التفاعل: ${result.priorityOrder.take(8).joinToString(" → ")}")
                }

                if (AccessibilityStateManager.shouldWaitForUI()) {
                    append("\n\n⏳ الواجهة تتغيّر بسرعة. يُنصح بالانتظار قبل التفاعل.")
                }
            }

            ToolExecutionResult(output, truncated = result.extractedNodes >= 150)
        } catch (e: Exception) {
            ToolExecutionResult("فشل تحليل الشجرة: ${e.message}", isError = true)
        }
    }

    private fun getSummary(): ToolExecutionResult {
        return ToolExecutionResult(
            buildString {
                append(AccessibilityStateManager.buildContextSummary())
                val result = lastParseResult
                if (result != null) {
                    append("\n\n📋 آخر شجرة محلّلة: ${result.extractedNodes} عقدة")
                    if (result.detectedForms.isNotEmpty()) {
                        append("\n📝 نماذج: ${result.detectedForms.joinToString(", ") { it.groupName }}")
                    }
                } else {
                    append("\n\n💡 لم تُحلَّل الشجرة بعد. استخدم dump_tree.")
                }
            }
        )
    }

    private fun findElement(text: String?): ToolExecutionResult {
        if (text.isNullOrBlank()) {
            return ToolExecutionResult("يجب تحديد 'text' للبحث.", isError = true)
        }
        val found = AccessibilityStateManager.findNodeByText(text)
            ?: return ToolExecutionResult("❌ لم يُعثر على '$text' في الواجهة الحالية.", isError = true)

        val bounds = Rect().also { found.getBoundsInScreen(it) }
        val className = found.className?.toString()?.substringAfterLast('.') ?: "View"
        val isClickable = found.isClickable
        val isEditable = found.isEditable

        return ToolExecutionResult(
            buildString {
                append("✅ وُجد '$text'\n")
                append("النوع: $className\n")
                append("الحدود: [${bounds.left}, ${bounds.top}, ${bounds.right}, ${bounds.bottom}]\n")
                if (isClickable) append("قابل للنقر: نعم\n")
                if (isEditable) append("قابل للتحرير: نعم\n")
                append("\nلاستخدامه: قم بـ dump_tree واحصل على node_id المناسب.")
            }
        )
    }

    private suspend fun waitFor(text: String?, timeoutMs: Long): ToolExecutionResult {
        if (text.isNullOrBlank()) {
            return ToolExecutionResult("يجب تحديد 'text' للانتظار.", isError = true)
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
            ToolExecutionResult("✅ ظهر '$text' بعد ${elapsed}ms")
        } else {
            ToolExecutionResult(
                "⏱️ لم يظهر '$text' خلال ${timeoutMs}ms. الشاشة الحالية: ${AccessibilityStateManager.activePackage.value}",
                isError = true
            )
        }
    }

    private suspend fun scrollToText(text: String?, direction: String?): ToolExecutionResult {
        if (text.isNullOrBlank()) {
            return ToolExecutionResult("يجب تحديد 'text' للتمرير إليه.", isError = true)
        }
        val result = withContext(Dispatchers.IO) {
            GodModeAccessibility.scrollUntilVisible(text, direction ?: "down")
        }
        return ToolExecutionResult(result.message, isError = !result.found)
    }

    private suspend fun smartFill(formDataJson: String?): ToolExecutionResult {
        if (formDataJson.isNullOrBlank()) {
            return ToolExecutionResult(
                "يجب تحديد 'form_data' كـ JSON: {\"N1\":\"value1\",\"N2\":\"value2\"}",
                isError = true
            )
        }

        val parseResult = lastParseResult
            ?: return ToolExecutionResult("استخدم dump_tree أولاً للحصول على node_ids.", isError = true)

        return try {
            val data = parseJsonMap(formDataJson)
            val results = mutableListOf<String>()
            var successCount = 0

            for ((nodeId, value) in data) {
                val node = parseResult.nodeMap[nodeId.uppercase()]
                if (node == null) {
                    results.add("⚠️ $nodeId: عقدة غير موجودة")
                    continue
                }
                if (!node.isEditable) {
                    results.add("⚠️ $nodeId: غير قابل للتحرير")
                    continue
                }

                val service = OmniAccessibilityService.instance
                    ?: return ToolExecutionResult("خدمة الـ accessibility غير نشطة.", isError = true)

                // انقر على الحقل أولاً
                service.clickNode(node)
                delay(200)

                // اكتب القيمة
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
                "📝 smart_fill: $successCount/${data.size} حقل\n${results.joinToString("\n")}"
            )
        } catch (e: Exception) {
            ToolExecutionResult("❌ خطأ في smart_fill: ${e.message}", isError = true)
        }
    }

    private fun getNodeText(nodeId: String?): ToolExecutionResult {
        if (nodeId.isNullOrBlank()) {
            return ToolExecutionResult("يجب تحديد 'node_id'.", isError = true)
        }
        val node = lastParseResult?.nodeMap?.get(nodeId.uppercase())
            ?: return ToolExecutionResult("[$nodeId] غير موجود. استخدم dump_tree.", isError = true)

        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        return ToolExecutionResult("[$nodeId] text: \"$text\" | desc: \"$desc\"")
    }

    private fun describeNode(nodeId: String?): ToolExecutionResult {
        if (nodeId.isNullOrBlank()) {
            return ToolExecutionResult("يجب تحديد 'node_id'.", isError = true)
        }
        val node = lastParseResult?.nodeMap?.get(nodeId.uppercase())
            ?: return ToolExecutionResult("[$nodeId] غير موجود. استخدم dump_tree.", isError = true)

        val bounds = Rect().also { node.getBoundsInScreen(it) }
        return ToolExecutionResult(buildString {
            append("وصف [$nodeId]:\n")
            append("الفئة: ${node.className?.toString()?.substringAfterLast('.') ?: "Unknown"}\n")
            append("النص: ${node.text ?: "(لا يوجد)"}\n")
            append("الوصف: ${node.contentDescription ?: "(لا يوجد)"}\n")
            append("قابل للنقر: ${node.isClickable}\n")
            append("قابل للتحرير: ${node.isEditable}\n")
            append("قابل للتمرير: ${node.isScrollable}\n")
            append("مفعّل: ${node.isEnabled}\n")
            append("مرئي: ${node.isVisibleToUser}\n")
            append("مركزه: (${bounds.centerX()}, ${bounds.centerY()})\n")
            append("الحدود: [${bounds.left}, ${bounds.top}] → [${bounds.right}, ${bounds.bottom}]")
        })
    }

    private fun verifyNode(nodeId: String?, expectedState: String?): ToolExecutionResult {
        if (nodeId.isNullOrBlank()) {
            return ToolExecutionResult("يجب تحديد 'node_id'.", isError = true)
        }
        val node = lastParseResult?.nodeMap?.get(nodeId.uppercase())
            ?: return ToolExecutionResult("[$nodeId] غير موجود. الواجهة قد تغيّرت — استخدم dump_tree.", isError = true)

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
            if (stateMatch) "✅ [$nodeId] التحقق نجح${expectedState?.let { ": $it" } ?: ""}"
            else "❌ [$nodeId] التحقق فشل. الحالة المتوقعة: $expectedState",
            isError = !stateMatch
        )
    }

    private suspend fun executeChain(chainStepsJson: String?): ToolExecutionResult {
        if (chainStepsJson.isNullOrBlank()) {
            return ToolExecutionResult(
                "يجب تحديد 'chain_steps' كـ JSON array: [{\"action\":\"click\",\"node_id\":\"N3\"},{\"action\":\"type\",\"node_id\":\"N4\",\"text\":\"hello\"}]",
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
                        "❌ فشل في الخطوة ${index + 1} ($stepAction):\n${results.joinToString("\n")}",
                        isError = true
                    )
                }
                delay(200)
            }

            ToolExecutionResult("✅ chain نجحت (${steps.size} خطوة):\n${results.joinToString("\n")}")
        } catch (e: Exception) {
            ToolExecutionResult("❌ خطأ في chain: ${e.message}", isError = true)
        }
    }

    // ── الإجراءات الأساسية (محسّنة) ──────────────────────────────────────────

    private suspend fun clickNode(nodeId: String?, verifyChange: Boolean = true): ToolExecutionResult {
        if (nodeId.isNullOrBlank()) return ToolExecutionResult("يجب تحديد 'node_id'.", isError = true)
        val node = resolveNode(nodeId) ?: return ToolExecutionResult(
            "[$nodeId] غير موجود. استخدم dump_tree لتحديث الشجرة.", isError = true)

        val preTime = AccessibilityStateManager.lastUpdateTime.value
        val service = OmniAccessibilityService.instance
            ?: return ToolExecutionResult("خدمة الـ accessibility غير نشطة.", isError = true)

        val success = service.clickNode(node)
        if (!success) return ToolExecutionResult("❌ فشل النقر على [$nodeId].", isError = true)

        if (verifyChange) {
            delay(POST_ACTION_VERIFY_MS.toLong())
            val postTime = AccessibilityStateManager.lastUpdateTime.value
            val changed = postTime > preTime
            return ToolExecutionResult(
                if (changed) "✅ تم النقر على [$nodeId] — الواجهة تغيّرت"
                else "✅ تم النقر على [$nodeId] — (الواجهة لم تتغيّر بعد النقر، قد يكون طبيعياً)"
            )
        }
        return ToolExecutionResult("✅ تم النقر على [$nodeId]")
    }

    private fun longClickNode(nodeId: String?): ToolExecutionResult {
        if (nodeId.isNullOrBlank()) return ToolExecutionResult("يجب تحديد 'node_id'.", isError = true)
        val node = resolveNode(nodeId) ?: return ToolExecutionResult("[$nodeId] غير موجود.", isError = true)
        val service = OmniAccessibilityService.instance
            ?: return ToolExecutionResult("خدمة الـ accessibility غير نشطة.", isError = true)

        return if (service.longClickNode(node)) ToolExecutionResult("✅ نقر طويل على [$nodeId]")
        else ToolExecutionResult("❌ فشل النقر الطويل على [$nodeId].", isError = true)
    }

    private suspend fun typeText(nodeId: String?, text: String?, clearFirst: Boolean): ToolExecutionResult {
        if (nodeId.isNullOrBlank()) return ToolExecutionResult("يجب تحديد 'node_id'.", isError = true)
        if (text == null) return ToolExecutionResult("يجب تحديد 'text'.", isError = true)
        val node = resolveNode(nodeId) ?: return ToolExecutionResult("[$nodeId] غير موجود.", isError = true)
        val service = OmniAccessibilityService.instance
            ?: return ToolExecutionResult("خدمة الـ accessibility غير نشطة.", isError = true)

        if (!node.isEditable) {
            return ToolExecutionResult("[$nodeId] غير قابل للتحرير. استخدم force_type بدلاً من ذلك.", isError = true)
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
            ToolExecutionResult("✅ كُتب في [$nodeId]: \"${text.take(50)}\"")
        } else {
            // Fallback: Shizuku
            service.clickNode(node)
            delay(200)
            val shizukuResult = withContext(Dispatchers.IO) {
                GodModeAccessibility.hybridType(text, node, clearFirst)
            }
            ToolExecutionResult("⚠️ Accessibility type فشل. $shizukuResult")
        }
    }

    private fun scrollNode(nodeId: String?, direction: String?): ToolExecutionResult {
        val forward = direction?.lowercase() != "backward" && direction?.lowercase() != "up"
        val service = OmniAccessibilityService.instance
            ?: return ToolExecutionResult("خدمة الـ accessibility غير نشطة.", isError = true)

        if (!nodeId.isNullOrBlank()) {
            val node = resolveNode(nodeId) ?: return ToolExecutionResult("[$nodeId] غير موجود.", isError = true)
            return if (service.scrollNode(node, forward))
                ToolExecutionResult("✅ تمرير [$nodeId] ${if (forward) "للأمام" else "للخلف"}")
            else ToolExecutionResult("❌ فشل التمرير على [$nodeId].", isError = true)
        }

        val root = AccessibilityStateManager.rootNode.value
            ?: return ToolExecutionResult("لا تتوفر شجرة واجهة.", isError = true)
        val scrollable = findFirstScrollable(root)
            ?: return ToolExecutionResult("لا يوجد عنصر قابل للتمرير.", isError = true)
        return if (service.scrollNode(scrollable, forward))
            ToolExecutionResult("✅ تمرير الشاشة ${if (forward) "للأمام" else "للخلف"}")
        else ToolExecutionResult("❌ فشل التمرير.", isError = true)
    }

    private fun pressBack() = performGlobal("BACK") { OmniAccessibilityService.instance?.pressBack() }
    private fun pressHome() = performGlobal("HOME") { OmniAccessibilityService.instance?.pressHome() }
    private fun pressRecents() = performGlobal("RECENTS") { OmniAccessibilityService.instance?.pressRecents() }

    private fun performGlobal(name: String, action: () -> Boolean?): ToolExecutionResult {
        val service = OmniAccessibilityService.instance
            ?: return ToolExecutionResult("خدمة الـ accessibility غير نشطة.", isError = true)
        return if (action() == true) ToolExecutionResult("✅ $name")
        else ToolExecutionResult("❌ فشل $name.", isError = true)
    }

    private fun tapXY(x: String?, y: String?): ToolExecutionResult {
        val xVal = x?.toFloatOrNull() ?: return ToolExecutionResult("x غير صحيح.", isError = true)
        val yVal = y?.toFloatOrNull() ?: return ToolExecutionResult("y غير صحيح.", isError = true)
        val service = OmniAccessibilityService.instance
            ?: return ToolExecutionResult("خدمة الـ accessibility غير نشطة.", isError = true)
        return if (service.tapAtCoordinates(xVal, yVal)) ToolExecutionResult("✅ نقر في ($xVal, $yVal)")
        else ToolExecutionResult("❌ فشل النقر.", isError = true)
    }

    private fun swipe(direction: String?, durationMs: String?, distanceRatio: String?, nodeId: String?): ToolExecutionResult {
        val service = OmniAccessibilityService.instance
            ?: return ToolExecutionResult("خدمة الـ accessibility غير نشطة.", isError = true)
        val dir = direction?.lowercase() ?: "forward"
        val duration = durationMs?.toLongOrNull()?.coerceIn(120L, 2500L) ?: 320L
        val ratio = distanceRatio?.toFloatOrNull()?.coerceIn(0.1f, 0.9f) ?: 0.35f

        val area = if (nodeId.isNullOrBlank()) {
            val root = AccessibilityStateManager.rootNode.value
                ?: return ToolExecutionResult("لا تتوفر شجرة واجهة.", isError = true)
            Rect().apply { root.getBoundsInScreen(this) }
        } else {
            val node = resolveNode(nodeId) ?: return ToolExecutionResult("[$nodeId] غير موجود.", isError = true)
            Rect().apply { node.getBoundsInScreen(this) }
        }

        if (area.isEmpty) return ToolExecutionResult("الحدود غير صحيحة.", isError = true)

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
        else ToolExecutionResult("❌ فشل الـ swipe.", isError = true)
    }

    private suspend fun autoEnable(): ToolExecutionResult {
        val result = GodModeAccessibility.autoEnableOmniVision()
        return ToolExecutionResult(result, isError = result.startsWith("❌"))
    }

    private suspend fun forceClick(nodeId: String?): ToolExecutionResult {
        if (nodeId.isNullOrBlank()) return ToolExecutionResult("يجب تحديد 'node_id'.", isError = true)
        val node = resolveNode(nodeId) ?: return ToolExecutionResult("[$nodeId] غير موجود.", isError = true)
        val result = withContext(Dispatchers.IO) { GodModeAccessibility.hybridTap(node) }
        return ToolExecutionResult(result, isError = result.startsWith("❌"))
    }

    private suspend fun forceLongClick(nodeId: String?): ToolExecutionResult {
        if (nodeId.isNullOrBlank()) return ToolExecutionResult("يجب تحديد 'node_id'.", isError = true)
        val node = resolveNode(nodeId) ?: return ToolExecutionResult("[$nodeId] غير موجود.", isError = true)
        val result = withContext(Dispatchers.IO) { GodModeAccessibility.hybridLongPress(node) }
        return ToolExecutionResult(result, isError = result.startsWith("❌"))
    }

    private suspend fun forceType(text: String?, nodeId: String?): ToolExecutionResult {
        if (text.isNullOrBlank()) return ToolExecutionResult("يجب تحديد 'text'.", isError = true)
        val fallbackNode = nodeId?.let { resolveNode(it) }
        val result = withContext(Dispatchers.IO) { GodModeAccessibility.hybridType(text, fallbackNode) }
        return ToolExecutionResult(result, isError = result.startsWith("❌"))
    }

    private fun startRecording(macroName: String?): ToolExecutionResult {
        if (macroName.isNullOrBlank()) return ToolExecutionResult("يجب تحديد 'macro_name'.", isError = true)
        GodModeAccessibility.startRecording(macroName)
        return ToolExecutionResult("🎬 بدأ تسجيل الماكرو '$macroName'")
    }

    private fun stopRecording(macroName: String?): ToolExecutionResult {
        if (macroName.isNullOrBlank()) return ToolExecutionResult("يجب تحديد 'macro_name'.", isError = true)
        val count = GodModeAccessibility.stopRecording(macroName)
        return ToolExecutionResult("⏹️ حُفظ الماكرو '$macroName': $count إجراء")
    }

    private suspend fun playMacro(macroName: String?): ToolExecutionResult {
        if (macroName.isNullOrBlank()) return ToolExecutionResult("يجب تحديد 'macro_name'.", isError = true)
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

    /** يُحلّل JSON بسيط من نوع {key: value} */
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

    /** يُحلّل JSON array بسيطاً من نوع [{key: value}] */
    private fun parseJsonArrayOfMaps(json: String): List<Map<String, String>> {
        val result = mutableListOf<Map<String, String>>()
        // تحليل بسيط بدون مكتبة خارجية
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
