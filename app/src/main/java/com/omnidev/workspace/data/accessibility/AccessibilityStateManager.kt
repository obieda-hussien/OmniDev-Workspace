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

    /** Called by [OmniAccessibilityService] when a new root node is available. */
    fun updateRootNode(node: AccessibilityNodeInfo?) {
        _rootNode.value = node
    }

    /** Called by [OmniAccessibilityService] when the active window changes. */
    fun updateActiveWindow(packageName: String?, activityName: String?) {
        _activePackage.value = packageName
        _activeActivity.value = activityName
    }

    /** Called by [OmniAccessibilityService.onServiceConnected]. */
    fun setServiceConnected(connected: Boolean) {
        _isServiceConnected.value = connected
        if (!connected) {
            _rootNode.value = null
            _activePackage.value = null
            _activeActivity.value = null
        }
    }
}
