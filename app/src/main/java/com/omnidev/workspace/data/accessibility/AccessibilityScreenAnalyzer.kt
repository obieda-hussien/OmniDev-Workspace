package com.omnidev.workspace.data.accessibility

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.omnidev.workspace.data.accessibility.AccessibilityStateManager.ScreenClass

/** Background-only heuristic analysis. The caller owns root until this scan finishes. */
internal object AccessibilityScreenAnalyzer {
    data class Analysis(val nodeCount: Int, val screenClass: ScreenClass, val truncated: Boolean)

    fun analyze(root: AccessibilityNodeInfo): Analysis {
        var editable = 0
        var lists = 0
        var progress = false
        var dialog = false
        var error = false
        val result = BoundedTreeWalk.scan(
            root = root,
            childCount = { it.childCount },
            child = { node, index -> node.getChild(index) },
            release = { it.recycle() },
            visit = { node ->
                val cls = node.className?.toString()?.lowercase().orEmpty()
                // Credential contents never participate in screen classification.
                val protected = node.isPassword || node.viewIdResourceName?.substringAfterLast('/') in
                    setOf("pinEntry", "passwordEntry")
                val text = if (protected) "" else node.text?.take(200)?.toString()?.lowercase().orEmpty()
                val desc = if (protected) "" else node.contentDescription?.take(200)?.toString()?.lowercase().orEmpty()
                if (node.isEditable) editable++
                if ("recyclerview" in cls || "listview" in cls) lists++
                if ("progressbar" in cls || "progress" in text) progress = true
                if ("dialog" in cls) dialog = true
                if ("error" in text || "failed" in text || "error" in desc) error = true
            },
            now = SystemClock::elapsedRealtime
        )
        val screen = when {
            dialog -> ScreenClass.DIALOG
            error -> ScreenClass.ERROR
            editable >= 2 -> ScreenClass.FORM
            editable == 1 -> ScreenClass.SEARCH
            lists > 0 -> ScreenClass.LIST
            result.truncated -> ScreenClass.UNKNOWN
            progress && result.visited < 15 || result.visited < 5 -> ScreenClass.LOADING
            else -> ScreenClass.INTERACTIVE
        }
        return Analysis(result.visited, screen, result.truncated)
    }
}
