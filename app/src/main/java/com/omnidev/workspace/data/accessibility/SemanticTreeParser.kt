package com.omnidev.workspace.data.accessibility

import android.view.accessibility.AccessibilityNodeInfo

/**
 * SemanticTreeParser — Token-optimized Android View Tree compressor.
 *
 * LLMs cannot efficiently process the raw Android accessibility tree (thousands of nodes,
 * massive XML). This parser traverses the tree and extracts ONLY actionable or informative
 * nodes — those that are clickable, scrollable, editable, checkable, or have visible
 * text/contentDescription.
 *
 * Each extracted node is assigned a short, temporary ID (e.g., `[N1]`, `[N2]`) that the
 * agent can reference in subsequent tool calls (click, type, scroll).
 *
 * Output format (minified structured text):
 * ```
 * [N1] Button: "Send" (Clickable)
 * [N2] EditText: "Type message..." (Editable)
 * [N3] TextView: "Hello World"
 * [N4] ScrollView (Scrollable, 3 children)
 * ```
 */
object SemanticTreeParser {

    /** Maximum nodes to extract to prevent context window overflow. */
    private const val MAX_NODES = 120

    /** Maximum tree depth to traverse. */
    private const val MAX_DEPTH = 25

    /**
     * Parsed result containing the semantic summary string and the node-ID-to-node mapping.
     */
    data class ParseResult(
        /** The minified semantic tree string for LLM consumption. */
        val semanticTree: String,
        /** Map of temporary node IDs (e.g., "N1") to their [AccessibilityNodeInfo] objects. */
        val nodeMap: Map<String, AccessibilityNodeInfo>,
        /** Total nodes found in the raw tree (before filtering). */
        val totalRawNodes: Int,
        /** Number of semantic nodes extracted (after filtering). */
        val extractedNodes: Int
    )

    /**
     * Parses the accessibility tree rooted at [root] into a token-optimized semantic summary.
     *
     * @param root The root [AccessibilityNodeInfo] of the active window.
     * @param packageName The package name of the foreground app (for context header).
     * @param activityName The activity class name (for context header).
     * @return A [ParseResult] with the semantic tree string and node map.
     */
    fun parse(
        root: AccessibilityNodeInfo,
        packageName: String? = null,
        activityName: String? = null
    ): ParseResult {
        val nodeMap = mutableMapOf<String, AccessibilityNodeInfo>()
        val lines = mutableListOf<String>()
        var nodeCounter = 0
        var totalRawNodes = 0

        // Header with app context
        val header = buildString {
            append("── Semantic UI Tree ──")
            if (packageName != null) append("\nApp: $packageName")
            if (activityName != null) {
                val shortActivity = activityName.substringAfterLast('.')
                append(" / $shortActivity")
            }
        }
        lines.add(header)

        fun traverse(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > MAX_DEPTH || nodeCounter >= MAX_NODES) return
            totalRawNodes++

            val isRelevant = isRelevantNode(node)

            if (isRelevant) {
                nodeCounter++
                val nodeId = "N$nodeCounter"
                nodeMap[nodeId] = node

                val indent = "  ".repeat(depth.coerceAtMost(6))
                val line = buildNodeLine(nodeId, node, indent)
                lines.add(line)
            }

            // Always traverse children to find nested relevant nodes
            val childCount = node.childCount
            for (i in 0 until childCount) {
                val child = node.getChild(i) ?: continue
                traverse(child, depth + 1)
            }
        }

        traverse(root, 0)

        if (nodeCounter == 0) {
            lines.add("(No actionable UI elements found on screen)")
        }

        if (nodeCounter >= MAX_NODES) {
            lines.add("... [truncated at $MAX_NODES nodes]")
        }

        return ParseResult(
            semanticTree = lines.joinToString("\n"),
            nodeMap = nodeMap,
            totalRawNodes = totalRawNodes,
            extractedNodes = nodeCounter
        )
    }

    /**
     * Determines if a node is "relevant" enough to include in the semantic tree.
     * A node is relevant if it is actionable (clickable, editable, scrollable, checkable)
     * or has visible text/description that provides semantic meaning.
     */
    private fun isRelevantNode(node: AccessibilityNodeInfo): Boolean {
        // Always include actionable nodes
        if (node.isClickable || node.isLongClickable) return true
        if (node.isEditable) return true
        if (node.isScrollable) return true
        if (node.isCheckable) return true
        if (node.isFocusable && node.isFocused) return true

        // Include nodes with meaningful text
        val text = node.text?.toString()?.trim()
        if (!text.isNullOrEmpty() && text.length <= 200) return true

        // Include nodes with content description (accessibility labels)
        val desc = node.contentDescription?.toString()?.trim()
        if (!desc.isNullOrEmpty() && desc.length <= 200) return true

        return false
    }

    /**
     * Builds a single-line representation of a node for the semantic tree output.
     */
    private fun buildNodeLine(
        nodeId: String,
        node: AccessibilityNodeInfo,
        indent: String
    ): String = buildString {
        append("$indent[$nodeId] ")

        // Class name (simplified)
        val className = node.className?.toString()?.substringAfterLast('.') ?: "View"
        append(className)

        // Text content
        val text = node.text?.toString()?.trim()
        if (!text.isNullOrEmpty()) {
            val truncatedText = if (text.length > 60) text.take(57) + "..." else text
            append(": \"$truncatedText\"")
        }

        // Content description (accessibility label)
        val desc = node.contentDescription?.toString()?.trim()
        if (!desc.isNullOrEmpty() && desc != text) {
            val truncatedDesc = if (desc.length > 60) desc.take(57) + "..." else desc
            append(" [desc: \"$truncatedDesc\"]")
        }

        // Capability flags
        val flags = mutableListOf<String>()
        if (node.isClickable) flags.add("Clickable")
        if (node.isLongClickable) flags.add("LongClickable")
        if (node.isEditable) flags.add("Editable")
        if (node.isScrollable) flags.add("Scrollable")
        if (node.isCheckable) {
            flags.add(if (node.isChecked) "Checked" else "Unchecked")
        }
        if (node.isSelected) flags.add("Selected")
        if (node.isFocused) flags.add("Focused")
        if (!node.isEnabled) flags.add("Disabled")

        if (flags.isNotEmpty()) {
            append(" (${flags.joinToString(", ")})")
        }

        // View ID for debugging (if present)
        val viewId = node.viewIdResourceName
        if (viewId != null) {
            val shortId = viewId.substringAfterLast('/')
            append(" #$shortId")
        }
    }
}
