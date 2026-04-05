package com.omnidev.workspace.data.accessibility

import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Singleton state manager holding the latest [AccessibilityNodeInfo] root node
 * from the active window. Updated by [OmniAccessibilityService] on each
 * window state or content change event.
 *
 * The agent's [SemanticUITool] reads from this state to parse and interact
 * with the on-screen UI tree without brittle X/Y coordinates.
 */
object AccessibilityStateManager {

    private val _rootNode = MutableStateFlow<AccessibilityNodeInfo?>(null)

    /** The latest root [AccessibilityNodeInfo] of the active window, or null if unavailable. */
    val rootNode: StateFlow<AccessibilityNodeInfo?> = _rootNode.asStateFlow()

    private val _isServiceConnected = MutableStateFlow(false)

    /** Whether the [OmniAccessibilityService] is currently connected and receiving events. */
    val isServiceConnected: StateFlow<Boolean> = _isServiceConnected.asStateFlow()

    private val _activePackage = MutableStateFlow<String?>(null)

    /** The package name of the currently focused app window. */
    val activePackage: StateFlow<String?> = _activePackage.asStateFlow()

    private val _activeActivity = MutableStateFlow<String?>(null)

    /** The class name of the currently focused activity. */
    val activeActivity: StateFlow<String?> = _activeActivity.asStateFlow()

    // ── AI Agent Enhancements ──

    private val _lastUpdateTime = MutableStateFlow(0L)

    /** * Timestamp (ms) of the last root node update. 
     * Crucial for the AI agent to determine if the UI state is stale. 
     */
    val lastUpdateTime: StateFlow<Long> = _lastUpdateTime.asStateFlow()

    private val _packageHistory = MutableStateFlow<List<String>>(emptyList())

    /** * Breadcrumbs: A running list of the last 5 unique packages visited.
     * Provides navigation context to the LLM (e.g., knew it came from WhatsApp to Chrome). 
     */
    val packageHistory: StateFlow<List<String>> = _packageHistory.asStateFlow()

    /** * Called by [OmniAccessibilityService] when a new root node is available. 
     * Automatically handles recycling of the old node to prevent catastrophic memory leaks.
     */
    fun updateRootNode(node: AccessibilityNodeInfo?) {
        // CRITICAL: Recycle the previous node before holding the new one
        val oldNode = _rootNode.value
        if (oldNode != null && oldNode != node) {
            try {
                oldNode.recycle()
            } catch (ignored: IllegalStateException) {
                // Node might have been already recycled by the system, safe to ignore
            }
        }
        
        _rootNode.value = node
        _lastUpdateTime.value = System.currentTimeMillis()
    }

    /** Called by [OmniAccessibilityService] when the active window changes. */
    fun updateActiveWindow(packageName: String?, activityName: String?) {
        _activePackage.value = packageName
        _activeActivity.value = activityName

        // Update Breadcrumbs history for the AI context window
        if (packageName != null) {
            val currentHistory = _packageHistory.value.toMutableList()
            // Only add if it's a new app transition
            if (currentHistory.lastOrNull() != packageName) {
                currentHistory.add(packageName)
                if (currentHistory.size > 5) {
                    currentHistory.removeAt(0) // Keep only the last 5
                }
                _packageHistory.value = currentHistory
            }
        }
    }

    /** Called by [OmniAccessibilityService.onServiceConnected]. */
    fun setServiceConnected(connected: Boolean) {
        _isServiceConnected.value = connected
        if (!connected) {
            // Cleanup and recycle on disconnect
            updateRootNode(null) 
            _activePackage.value = null
            _activeActivity.value = null
            _packageHistory.value = emptyList()
        }
    }
}
