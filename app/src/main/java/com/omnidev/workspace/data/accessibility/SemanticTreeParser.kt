package com.omnidev.workspace.data.accessibility

import android.graphics.Rect
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
 * [N1] Button: "Send" (Clickable) {bounds: [100, 800, 300, 950]}
 * [N2] EditText: "Type message..." (Editable) {bounds: [50, 400, 1000, 550]}
 * [N3] TextView: "Hello World" {bounds: [0, 100, 1080, 200]}
 * [N4] ScrollView (Scrollable, 3 children) {bounds: [0, 0, 1080, 1920]}
 * ```
 */
object SemanticTreeParser {

    /** Maximum nodes to extract to prevent context window overflow. */
    private const val MAX_NODES = 120

    /** Maximum tree depth to traverse. */
    private const val MAX_DEPTH = 25

    /** Maximum character length for text/contentDescription to be considered relevant. */
    private const val MAX_TEXT_LENGTH = 200

    /** Maximum display length for text in the semantic tree output. */
    private const val MAX_DISPLAY_LENGTH = 60

    /**
     * Parsed result containing the semantic summary string and the node-ID-to-node mapping.
     * * IMPORTANT MEMORY MANAGEMENT NOTE:
     * The [nodeMap] holds strong references to [AccessibilityNodeInfo] objects.
     * The consumer of this ParseResult MUST iterate over `nodeMap.values` and call
     * `recycle()` on each node once the agent has finished executing its action, 
     * otherwise the app will suffer from massive memory leaks.
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
                // Store a copy to prevent the system from recycling it unexpectedly underneath us
                val nodeCopy = AccessibilityNodeInfo.obtain(node)
                nodeMap[nodeId] = nodeCopy

                val indent = "  ".repeat(depth.coerceAtMost(6))
                val line = buildNodeLine(nodeId, node, indent)
                lines.add(line)
            }

            // Always traverse children to find nested relevant nodes
            val childCount = node.childCount
            for (i in 0 until childCount) {
                val child = node.getChild(i) ?: continue
                traverse(child, depth + 1)
                // We recycle the child ONLY if we didn't store it in our map.
                // However, since we stored a *copy* in the map (nodeCopy), 
                // it's safe to recycle this iteration's child object here.
                child.recycle() 
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
        // CRITICAL FILTER: Ignore off-screen or invisible elements to prevent LLM hallucinations
        if (!node.isVisibleToUser) return false

        // Always include actionable nodes
        if (isNodeActionable(node)) return true
        if (node.isEditable) return true
        if (node.isScrollable) return true
        if (node.isCheckable) return true
        if (node.isFocusable && node.isFocused) return true

        // Include nodes with meaningful text
        val text = node.text?.toString()?.trim()
        if (!text.isNullOrEmpty() && text.length <= MAX_TEXT_LENGTH) return true

        // Include nodes with content description (accessibility labels)
        val desc = node.contentDescription?.toString()?.trim()
        if (!desc.isNullOrEmpty() && desc.length <= MAX_TEXT_LENGTH) return true

        // Compose semantics and compat metadata often appear in extras.
        if (hasSemanticExtras(node)) return true

        // Include compose-related containers when they have children,
        // so the agent can reason about Compose trees better.
        if (isComposeNode(node) && node.childCount > 0) return true

        return false
    }

    /**
     * Truncates [text] to [MAX_DISPLAY_LENGTH], appending "..." if truncated.
     */
    private fun truncate(text: String): String =
        if (text.length > MAX_DISPLAY_LENGTH) text.take(MAX_DISPLAY_LENGTH - 3) + "..." else text

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
            // Mask password fields for safety
            if (node.isPassword) {
                append(": \"••••••••\"")
            } else {
                append(": \"${truncate(text)}\"")
            }
        }

        // Content description (accessibility label)
        val desc = node.contentDescription?.toString()?.trim()
        if (!desc.isNullOrEmpty() && desc != text) {
            append(" [desc: \"${truncate(desc)}\"]")
        }

        val state = readSemanticState(node)
        if (!state.isNullOrEmpty()) {
            append(" [state: \"${truncate(state)}\"]")
        }

        // Capability flags
        val flags = mutableListOf<String>()
        val hasClickAction = supportsAction(node, AccessibilityNodeInfo.ACTION_CLICK)
        val hasLongClickAction = supportsAction(node, AccessibilityNodeInfo.ACTION_LONG_CLICK)
        val hasScrollAction = supportsAnyScrollAction(node)
        
        if (node.isClickable || hasClickAction) flags.add("Clickable")
        if (node.isLongClickable || hasLongClickAction) flags.add("LongClickable")
        if (node.isEditable) flags.add("Editable")
        if (node.isScrollable || hasScrollAction) flags.add("Scrollable")
        if (node.isPassword) flags.add("Password") // Vital for LLM context
        if (node.isCheckable) {
            flags.add(if (node.isChecked) "Checked" else "Unchecked")
        }
        if (node.isSelected) flags.add("Selected")
        if (node.isFocused) flags.add("Focused")
        if (!node.isEnabled) flags.add("Disabled")
        if (isComposeNode(node)) flags.add("Compose")

        if (flags.isNotEmpty()) {
            append(" (${flags.joinToString(", ")})")
        }

        // Spatial Awareness: Add screen bounding box so LLM understands layout
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (!bounds.isEmpty) {
            append(" {bounds: [${bounds.left}, ${bounds.top}, ${bounds.right}, ${bounds.bottom}]}")
        }

        // View ID for debugging (if present)
        val viewId = node.viewIdResourceName
        if (viewId != null) {
            val shortId = viewId.substringAfterLast('/')
            append(" #$shortId")
        }
    }

    private fun isNodeActionable(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable || node.isLongClickable) return true
        if (supportsAction(node, AccessibilityNodeInfo.ACTION_CLICK)) return true
        if (supportsAction(node, AccessibilityNodeInfo.ACTION_LONG_CLICK)) return true
        if (supportsAnyScrollAction(node)) return true
        return false
    }

    private fun supportsAnyScrollAction(node: AccessibilityNodeInfo): Boolean {
        return supportsAction(node, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) ||
            supportsAction(node, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) ||
            node.actionList.any { action ->
                val label = action.label ?: return@any false
                label.toString().contains("scroll", ignoreCase = true)
            }
    }

    private fun supportsAction(node: AccessibilityNodeInfo, actionId: Int): Boolean {
        return node.actionList.any { it.id == actionId }
    }

    private fun isComposeNode(node: AccessibilityNodeInfo): Boolean {
        val className = node.className?.toString().orEmpty().lowercase()
        if ("compose" in className) return true
        if ("androidx.compose" in className) return true
        return node.extras.keySet().any { it.lowercase().contains("compose") }
    }

    private fun hasSemanticExtras(node: AccessibilityNodeInfo): Boolean {
        val keys = node.extras.keySet()
        if (keys.isEmpty()) return false
        return keys.any { key ->
            val normalized = key.lowercase()
            normalized.contains("role_description") ||
                normalized.contains("state_description") ||
                normalized.contains("pane_title") ||
                normalized.contains("hint_text") ||
                normalized.contains("tooltip_text") ||
                normalized.contains("heading") ||
                normalized.contains("compose")
        }
    }

    private fun readSemanticState(node: AccessibilityNodeInfo): String? {
        val extras = node.extras
        val candidateKeys = listOf(
            "androidx.view.accessibility.AccessibilityNodeInfoCompat.STATE_DESCRIPTION_KEY",
            "androidx.view.accessibility.AccessibilityNodeInfoCompat.ROLE_DESCRIPTION_KEY",
            "androidx.view.accessibility.AccessibilityNodeInfoCompat.PANE_TITLE_KEY"
        )
        for (key in candidateKeys) {
            val value = extras.getCharSequence(key)?.toString()?.trim()
            if (!value.isNullOrEmpty()) return value
        }
        return null
    }
}
