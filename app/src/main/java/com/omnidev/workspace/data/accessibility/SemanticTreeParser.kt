package com.omnidev.workspace.data.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * SemanticTreeParser — [Localized] [Localized] [Localized] [Localized]
 *
 * [Localized] [Localized]: [Localized] [Localized] [Localized]
 * ─────────────────────────────────────────────────────────────────────────────
 * 1. **[Localized] [Localized] (Form Detection)**: [Localized] [Localized] [Localized] [Localized] [Localized]
 *    [Localized] [Localized] "Form Group" [Localized] [Localized] [Localized].
 *
 * 2. **[Localized] [Localized] (Relationship Mapping)**: [Localized] [Localized] [Localized] [Localized] [Localized]
 *    TextView [Localized] label [Localized] [Localized] [Localized].
 *
 * 3. **[Localized] [Localized] (Priority Scoring)**: [Localized] [Localized] [Localized] [Localized] [Localized] [Localized]
 *    [Localized] [Localized] + [Localized] + [Localized] [Localized].
 *
 * 4. **[Localized] [Localized] (Executive Summary)**: [Localized] [Localized] [Localized] [Localized] [Localized]
 *    [Localized] [Localized] [Localized] [Localized].
 *
 * 5. **[Localized] [Localized] [Localized] (Navigation Detection)**: [Localized] Bottom Nav / Tab Bar /
 *    Drawer / FAB [Localized] [Localized].
 *
 * 6. **[Localized] Compose [Localized]**: [Localized] semantics extras [Localized] [Localized]
 *    stateDescription / roleDescription / headings.
 */
object SemanticTreeParser {

    private const val MAX_NODES = 150
    private const val MAX_DEPTH = 30
    private const val MAX_TEXT_LENGTH = 200
    private const val MAX_DISPLAY_LENGTH = 80

    /**
     * [Localized] [Localized] [Localized] — [Localized] [Localized] [Localized].
     */
    data class ParseResult(
        val semanticTree: String,
        val nodeMap: Map<String, AccessibilityNodeInfo>,
        val totalRawNodes: Int,
        val extractedNodes: Int,
        /** [Localized] [Localized] [Localized] [Localized] */
        val summary: String,
        /** [Localized] [Localized] [Localized] */
        val detectedForms: List<FormGroup>,
        /** [Localized] [Localized] [Localized] */
        val navigationElements: List<String>,
        /** [Localized] [Localized] [Localized] [Localized] ([Localized] [Localized]) */
        val priorityOrder: List<String>
    )

    data class FormGroup(
        val groupName: String,
        val fields: List<FormField>
    )

    data class FormField(
        val nodeId: String,
        val labelText: String?,
        val placeholderText: String?,
        val fieldType: FieldType
    )

    enum class FieldType {
        TEXT, EMAIL, PASSWORD, NUMBER, PHONE, SEARCH, MULTILINE, UNKNOWN
    }

    // ── [Localized] [Localized] [Localized] ─────────────────────────────────────────────

    private data class NodeMeta(
        val node: AccessibilityNodeInfo,
        val nodeId: String,
        val priority: Int,
        val bounds: Rect,
        val isNavigational: Boolean,
        val isFormField: Boolean,
        val labelCandidate: AccessibilityNodeInfo?
    )

    // ── API [Localized] ───────────────────────────────────────────────────────────

    fun parse(
        root: AccessibilityNodeInfo,
        packageName: String? = null,
        activityName: String? = null
    ): ParseResult {
        val nodeMap = mutableMapOf<String, AccessibilityNodeInfo>()
        val allMeta = mutableListOf<NodeMeta>()
        var nodeCounter = 0
        var totalRawNodes = 0

        // [Localized] [Localized]: [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] priority
        fun traverse(node: AccessibilityNodeInfo, depth: Int, parent: AccessibilityNodeInfo?) {
            if (depth > MAX_DEPTH || nodeCounter >= MAX_NODES) return
            totalRawNodes++

            if (isRelevantNode(node)) {
                nodeCounter++
                val nodeId = "N$nodeCounter"
                val nodeCopy = AccessibilityNodeInfo.obtain(node)
                nodeMap[nodeId] = nodeCopy

                val bounds = Rect().also { node.getBoundsInScreen(it) }
                val priority = computePriority(node, depth, bounds)
                val labelCandidate = findLabelForNode(node, parent)

                allMeta.add(
                    NodeMeta(
                        node = nodeCopy,
                        nodeId = nodeId,
                        priority = priority,
                        bounds = bounds,
                        isNavigational = isNavigationalElement(node),
                        isFormField = node.isEditable,
                        labelCandidate = labelCandidate
                    )
                )
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                traverse(child, depth + 1, node)
                child.recycle()
            }
        }

        traverse(root, 0, null)

        // [Localized] [Localized]: [Localized] [Localized] [Localized]
        val lines = mutableListOf<String>()
        val header = buildHeader(packageName, activityName, totalRawNodes, nodeCounter)
        lines.add(header)

        // [Localized] [Localized] [Localized] [Localized] [Localized]
        buildFormattedTree(root, allMeta, lines)

        if (nodeCounter == 0) lines.add("([Localized] [Localized] [Localized] [Localized] [Localized] [Localized])")
        if (nodeCounter >= MAX_NODES) lines.add("... [[Localized] [Localized] [Localized] $MAX_NODES [Localized]]")

        // [Localized] [Localized]
        val forms = detectForms(allMeta, nodeMap)

        // [Localized] [Localized] [Localized]
        val navElements = allMeta
            .filter { it.isNavigational }
            .map { meta ->
                val node = nodeMap[meta.nodeId]
                "${meta.nodeId}: ${node?.text ?: node?.contentDescription ?: "nav"}"
            }

        // [Localized] [Localized] [Localized]
        val priorityOrder = allMeta
            .sortedByDescending { it.priority }
            .take(20)
            .map { it.nodeId }

        // [Localized] [Localized]
        val summary = buildExecutiveSummary(
            packageName, allMeta, forms, navElements, nodeCounter
        )

        return ParseResult(
            semanticTree = lines.joinToString("\n"),
            nodeMap = nodeMap,
            totalRawNodes = totalRawNodes,
            extractedNodes = nodeCounter,
            summary = summary,
            detectedForms = forms,
            navigationElements = navElements,
            priorityOrder = priorityOrder
        )
    }

    // ── [Localized] [Localized] ───────────────────────────────────────────────────────────

    private fun buildFormattedTree(
        root: AccessibilityNodeInfo,
        allMeta: List<NodeMeta>,
        lines: MutableList<String>
    ) {
        val metaByPriority = allMeta.sortedWith(
            compareBy({ it.bounds.top }, { it.bounds.left })
        )

        // [Localized] [Localized] navigation elements [Localized]
        val navMeta = metaByPriority.filter { it.isNavigational }
        val formMeta = metaByPriority.filter { it.isFormField && !it.isNavigational }
        val restMeta = metaByPriority.filter { !it.isNavigational && !it.isFormField }

        if (navMeta.isNotEmpty()) {
            lines.add("\n📍 [Localized] [Localized]:")
            navMeta.forEach { meta -> lines.add(buildNodeLine(meta)) }
        }

        if (formMeta.isNotEmpty()) {
            lines.add("\n📝 [Localized] [Localized]:")
            formMeta.forEach { meta -> lines.add(buildNodeLine(meta, showLabel = true)) }
        }

        if (restMeta.isNotEmpty()) {
            lines.add("\n🖱️ [Localized] [Localized]:")
            restMeta.forEach { meta -> lines.add(buildNodeLine(meta)) }
        }
    }

    private fun buildNodeLine(meta: NodeMeta, showLabel: Boolean = false): String = buildString {
        val node = meta.node
        val indent = "  "
        append("$indent[${meta.nodeId}]")

        // Priority indicator
        append(when {
            meta.priority >= 90 -> " 🔥"
            meta.priority >= 70 -> " ⭐"
            else -> " ·"
        })
        append(" ")

        val className = node.className?.toString()?.substringAfterLast('.') ?: "View"
        append(className)

        // Label [Localized] [Localized] parent ([Localized])
        if (showLabel && meta.labelCandidate != null) {
            val labelText = meta.labelCandidate.text?.toString()?.trim()
                ?: meta.labelCandidate.contentDescription?.toString()?.trim()
            if (!labelText.isNullOrEmpty()) {
                append(" [label: \"${truncate(labelText)}\"]")
            }
        }

        // [Localized]
        val text = node.text?.toString()?.trim()
        if (!text.isNullOrEmpty()) {
            if (node.isPassword) append(": \"••••\"")
            else append(": \"${truncate(text)}\"")
        }

        // [Localized]
        val desc = node.contentDescription?.toString()?.trim()
        if (!desc.isNullOrEmpty() && desc != text) {
            append(" [desc: \"${truncate(desc)}\"]")
        }

        // [Localized] [Localized] [Localized] extras
        val semanticState = readSemanticState(node)
        if (!semanticState.isNullOrEmpty()) {
            append(" [state: \"${truncate(semanticState)}\"]")
        }

        // [Localized] [Localized] [Localized] EditText
        if (node.isEditable) {
            append(" (${inferFieldType(node).name})")
        }

        // [Localized]
        val flags = buildFlagsList(node)
        if (flags.isNotEmpty()) append(" (${flags.joinToString(", ")})")

        // [Localized]
        if (!meta.bounds.isEmpty) {
            append(" {${meta.bounds.left},${meta.bounds.top}–${meta.bounds.right},${meta.bounds.bottom}}")
        }

        // [Localized] resource ID [Localized]
        node.viewIdResourceName?.substringAfterLast('/')?.let {
            append(" #$it")
        }
    }

    // ── [Localized] [Localized] ───────────────────────────────────────────────────────────

    private fun detectForms(
        allMeta: List<NodeMeta>,
        nodeMap: Map<String, AccessibilityNodeInfo>
    ): List<FormGroup> {
        val editableMeta = allMeta.filter { it.isFormField }
        if (editableMeta.isEmpty()) return emptyList()

        // [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized]
        val groups = mutableListOf<MutableList<NodeMeta>>()
        var currentGroup = mutableListOf<NodeMeta>()

        for (meta in editableMeta.sortedBy { it.bounds.top }) {
            if (currentGroup.isEmpty()) {
                currentGroup.add(meta)
            } else {
                val lastBottom = currentGroup.last().bounds.bottom
                val gap = meta.bounds.top - lastBottom
                if (gap < 250) { // [Localized] [Localized] 250px [Localized] [Localized] = [Localized] [Localized]
                    currentGroup.add(meta)
                } else {
                    groups.add(currentGroup)
                    currentGroup = mutableListOf(meta)
                }
            }
        }
        if (currentGroup.isNotEmpty()) groups.add(currentGroup)

        return groups.mapIndexed { groupIndex, group ->
            val fields = group.map { meta ->
                val node = nodeMap[meta.nodeId]!!
                val label = meta.labelCandidate?.text?.toString()?.trim()
                    ?: meta.labelCandidate?.contentDescription?.toString()?.trim()
                val placeholder = node.text?.toString()?.trim()
                    ?.takeIf { it.isNotEmpty() && !it.all { c -> c.isDigit() } }
                FormField(
                    nodeId = meta.nodeId,
                    labelText = label,
                    placeholderText = placeholder,
                    fieldType = inferFieldType(node)
                )
            }
            val groupName = when {
                fields.any { it.fieldType == FieldType.PASSWORD } -> "[Localized] [Localized] [Localized]"
                fields.any { it.fieldType == FieldType.EMAIL } -> "[Localized] [Localized]"
                fields.size == 1 && fields.first().fieldType == FieldType.SEARCH -> "[Localized] [Localized]"
                else -> "[Localized] ${groupIndex + 1}"
            }
            FormGroup(groupName, fields)
        }
    }

    // ── [Localized] [Localized] ───────────────────────────────────────────────────────

    private fun buildExecutiveSummary(
        packageName: String?,
        allMeta: List<NodeMeta>,
        forms: List<FormGroup>,
        navElements: List<String>,
        totalNodes: Int
    ): String = buildString {
        val appName = packageName?.substringAfterLast('.') ?: "[Localized]"
        append("[Localized] [Localized] [Localized] $appName [Localized] [Localized] $totalNodes [Localized] [Localized]. ")

        if (forms.isNotEmpty()) {
            append("[Localized] ${forms.size} [Localized]: ${forms.joinToString(", ") { it.groupName }}. ")
        }

        val clickableCount = allMeta.count { it.node.isClickable }
        if (clickableCount > 0) {
            append("$clickableCount [Localized]/[Localized] [Localized] [Localized]. ")
        }

        if (navElements.isNotEmpty()) {
            append("${navElements.size} [Localized] [Localized] ([Localized]/[Localized]). ")
        }

        val topNodes = allMeta.sortedByDescending { it.priority }.take(3)
        if (topNodes.isNotEmpty()) {
            val topDesc = topNodes.mapNotNull { meta ->
                meta.node.text?.toString()?.trim()
                    ?: meta.node.contentDescription?.toString()?.trim()
            }.take(3)
            if (topDesc.isNotEmpty()) {
                append("[Localized] [Localized]: ${topDesc.joinToString(", ") { "\"$it\"" }}.")
            }
        }
    }

    private fun buildHeader(
        packageName: String?,
        activityName: String?,
        total: Int,
        extracted: Int
    ): String = buildString {
        append("── Semantic UI Tree ──")
        if (packageName != null) append("\nApp: $packageName")
        if (activityName != null) append(" / ${activityName.substringAfterLast('.')}")
        append("\nNodes: $extracted extracted / $total total")
    }

    // ── [Localized] [Localized] ─────────────────────────────────────────────────────────

    private fun computePriority(
        node: AccessibilityNodeInfo,
        depth: Int,
        bounds: Rect
    ): Int {
        var score = 0

        // [Localized] [Localized]
        if (node.isClickable) score += 30
        if (node.isEditable) score += 40
        if (node.isFocused) score += 25
        if (node.isFocusable) score += 10
        if (node.isScrollable) score += 20

        // [Localized] [Localized] ([Localized] = [Localized] [Localized])
        score -= depth * 2

        // [Localized] [Localized] ([Localized] = [Localized] [Localized] [Localized])
        // [Localized] [Localized] [Localized] (Bottom Nav) [Localized] [Localized]
        if (bounds.top < 400) score += 10

        // [Localized] [Localized]
        val text = node.text?.toString()
        if (!text.isNullOrEmpty()) score += 10
        if (text?.length in 2..30) score += 5 // [Localized] [Localized] [Localized]

        // Compose
        if (isComposeNode(node)) score += 5

        return score.coerceIn(0, 100)
    }

    private fun isNavigationalElement(node: AccessibilityNodeInfo): Boolean {
        val className = node.className?.toString()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val text = node.text?.toString()?.lowercase() ?: ""
        val viewId = node.viewIdResourceName?.lowercase() ?: ""

        return "bottomnavigation" in className ||
                "tabbar" in className ||
                "navigationview" in className ||
                "tab" in viewId ||
                "nav" in viewId ||
                "menu" in viewId ||
                "bottom_nav" in viewId ||
                (node.isClickable && ("home" in desc || "back" in desc || "menu" in desc)) ||
                className.contains("fab") ||
                className.contains("floatingaction")
    }

    private fun findLabelForNode(
        node: AccessibilityNodeInfo,
        parent: AccessibilityNodeInfo?
    ): AccessibilityNodeInfo? {
        if (parent == null || !node.isEditable) return null
        // [Localized] [Localized] TextView [Localized] [Localized] [Localized] sibling
        for (i in 0 until parent.childCount) {
            val sibling = parent.getChild(i) ?: continue
            if (sibling == node) break
            val sibClass = sibling.className?.toString() ?: ""
            if ("TextView" in sibClass || "Text" in sibClass) {
                val hasText = !sibling.text.isNullOrEmpty() || !sibling.contentDescription.isNullOrEmpty()
                if (hasText) return sibling
            }
            sibling.recycle()
        }
        return null
    }

    private fun inferFieldType(node: AccessibilityNodeInfo): FieldType {
        if (!node.isEditable) return FieldType.UNKNOWN
        if (node.isPassword) return FieldType.PASSWORD

        val inputType = node.inputType
        val hint = node.text?.toString()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val viewId = node.viewIdResourceName?.lowercase() ?: ""
        val combined = "$hint $desc $viewId"

        return when {
            inputType and 0x03 == 0x01 -> // TYPE_CLASS_NUMBER
                FieldType.NUMBER
            inputType and 0x20 == 0x20 -> // TYPE_TEXT_VARIATION_EMAIL
                FieldType.EMAIL
            inputType and 0x81 == 0x81 -> // TYPE_TEXT_VARIATION_PHONE
                FieldType.PHONE
            "email" in combined || "@" in hint -> FieldType.EMAIL
            "password" in combined || "pass" in combined -> FieldType.PASSWORD
            "phone" in combined || "mobile" in combined -> FieldType.PHONE
            "search" in combined -> FieldType.SEARCH
            "number" in combined || "amount" in combined -> FieldType.NUMBER
            inputType and 0x20000 == 0x20000 -> FieldType.MULTILINE
            else -> FieldType.TEXT
        }
    }

    private fun buildFlagsList(node: AccessibilityNodeInfo): List<String> {
        val flags = mutableListOf<String>()
        if (node.isClickable || supportsAction(node, AccessibilityNodeInfo.ACTION_CLICK)) flags.add("Clickable")
        if (node.isLongClickable) flags.add("LongClickable")
        if (node.isEditable) flags.add("Editable")
        if (node.isScrollable) flags.add("Scrollable")
        if (node.isPassword) flags.add("Password")
        if (node.isCheckable) flags.add(if (node.isChecked) "Checked" else "Unchecked")
        if (node.isSelected) flags.add("Selected")
        if (node.isFocused) flags.add("Focused")
        if (!node.isEnabled) flags.add("Disabled")
        if (isComposeNode(node)) flags.add("Compose")
        return flags
    }

    private fun isRelevantNode(node: AccessibilityNodeInfo): Boolean {
        if (!node.isVisibleToUser) return false
        if (isNodeActionable(node)) return true
        if (node.isEditable || node.isScrollable || node.isCheckable) return true
        if (node.isFocusable && node.isFocused) return true

        val text = node.text?.toString()?.trim()
        if (!text.isNullOrEmpty() && text.length <= MAX_TEXT_LENGTH) return true

        val desc = node.contentDescription?.toString()?.trim()
        if (!desc.isNullOrEmpty() && desc.length <= MAX_TEXT_LENGTH) return true

        if (hasSemanticExtras(node)) return true
        if (isComposeNode(node) && node.childCount > 0) return true
        return false
    }

    private fun isNodeActionable(node: AccessibilityNodeInfo): Boolean {
        return node.isClickable || node.isLongClickable ||
                supportsAction(node, AccessibilityNodeInfo.ACTION_CLICK) ||
                supportsAction(node, AccessibilityNodeInfo.ACTION_LONG_CLICK) ||
                supportsAnyScrollAction(node)
    }

    private fun supportsAction(node: AccessibilityNodeInfo, actionId: Int): Boolean =
        node.actionList.any { it.id == actionId }

    private fun supportsAnyScrollAction(node: AccessibilityNodeInfo): Boolean =
        supportsAction(node, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) ||
                supportsAction(node, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)

    private fun isComposeNode(node: AccessibilityNodeInfo): Boolean {
        val className = node.className?.toString().orEmpty().lowercase()
        return "compose" in className || "androidx.compose" in className ||
                node.extras.keySet().any { "compose" in it.lowercase() }
    }

    private fun hasSemanticExtras(node: AccessibilityNodeInfo): Boolean {
        return node.extras.keySet().any { key ->
            val k = key.lowercase()
            "role_description" in k || "state_description" in k || "pane_title" in k ||
                    "hint_text" in k || "tooltip_text" in k || "heading" in k || "compose" in k
        }
    }

    private fun readSemanticState(node: AccessibilityNodeInfo): String? {
        val extras = node.extras
        listOf(
            "androidx.view.accessibility.AccessibilityNodeInfoCompat.STATE_DESCRIPTION_KEY",
            "androidx.view.accessibility.AccessibilityNodeInfoCompat.ROLE_DESCRIPTION_KEY",
            "androidx.view.accessibility.AccessibilityNodeInfoCompat.PANE_TITLE_KEY"
        ).forEach { key ->
            val value = extras.getCharSequence(key)?.toString()?.trim()
            if (!value.isNullOrEmpty()) return value
        }
        return null
    }

    private fun truncate(text: String): String =
        if (text.length > MAX_DISPLAY_LENGTH) text.take(MAX_DISPLAY_LENGTH - 3) + "..." else text
}
