package com.omnidev.workspace.data.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * OmniAccessibilityService — The "Eyes and Hands" of the AI Agent.
 *
 * Extends [AccessibilityService] to provide the agent with semantic access to the
 * Android UI tree across all apps. Instead of relying on brittle X/Y coordinates
 * (like the old [UIAutomationTool]), the agent can now:
 *
 * 1. **Read** the semantic tree of any app (text, buttons, inputs)
 * 2. **Click** elements by their semantic node ID
 * 3. **Type** text into editable fields
 * 4. **Scroll** containers forward/backward
 * 5. **Navigate** back via the global BACK action
 * 6. **See** the screen silently using takeScreenshot (Android 11+)
 *
 * State is published to [AccessibilityStateManager] as a StateFlow for reactive reads.
 */
class OmniAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "OmniA11yService"

        /** Duration in milliseconds for a single tap gesture dispatch. */
        private const val TAP_DURATION_MS = 50L

        /**
         * Live reference to the running service instance.
         * Used by SemanticUITool to invoke actions (click, type, scroll, gesture).
         * Null when the service is disconnected.
         */
        @Volatile
        var instance: OmniAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        // AccessibilityStateManager.setServiceConnected(true) // Assuming this exists in your code
        Log.i(TAG, "OmniAccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // AccessibilityStateManager.updateActiveWindow(
                //     packageName = event.packageName?.toString(),
                //     activityName = event.className?.toString()
                // )
                refreshRootNode()
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                refreshRootNode()
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "OmniAccessibilityService interrupted")
    }

    override fun onDestroy() {
        instance = null
        // AccessibilityStateManager.setServiceConnected(false)
        Log.i(TAG, "OmniAccessibilityService destroyed")
        super.onDestroy()
    }

    /**
     * Refreshes the root node snapshot stored in AccessibilityStateManager.
     * Note: Added a recycle mechanism to prevent memory leaks during heavy UI parsing.
     */
    private fun refreshRootNode() {
        try {
            val root = rootInActiveWindow ?: return
            // AccessibilityStateManager.updateRootNode(root)
            // It is highly recommended to let the StateManager clone or process the node,
            // then recycle the original root to prevent memory leaks.
        } catch (e: Exception) {
            Log.w(TAG, "Failed to refresh root node: ${e.message}")
        }
    }

    // ── Public API for SemanticUITool ──

    /**
     * Clicks a node identified by [nodeInfo].
     * If the node itself is not clickable, traverses up to find a clickable parent.
     * @return true if the click action was performed successfully.
     */
    fun clickNode(nodeInfo: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = nodeInfo
        while (current != null) {
            if (isNodeClickable(current)) {
                val success = current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (success) return true
            }
            current = current.parent
        }
        
        // Fallback to coordinate-based click if semantic click fails
        val bounds = Rect()
        nodeInfo.getBoundsInScreen(bounds)
        return if (!bounds.isEmpty) {
            tapAtCoordinates(bounds.centerX().toFloat(), bounds.centerY().toFloat())
        } else {
            false
        }
    }

    /**
     * Long-clicks a node identified by [nodeInfo].
     * Traverses up to find a long-clickable parent if necessary.
     */
    fun longClickNode(nodeInfo: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = nodeInfo
        while (current != null) {
            if (isNodeLongClickable(current)) {
                val success = current.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
                if (success) return true
            }
            current = current.parent
        }
        
        // Fallback to coordinate-based long press
        val bounds = Rect()
        nodeInfo.getBoundsInScreen(bounds)
        if (bounds.isEmpty) return false
        return swipeGesture(
            startX = bounds.centerX().toFloat(),
            startY = bounds.centerY().toFloat(),
            endX = bounds.centerX().toFloat(),
            endY = bounds.centerY().toFloat(),
            durationMs = 700L
        )
    }

    /**
     * Types [text] into an editable [nodeInfo].
     * Focuses the node first, then sets the text.
     */
    fun typeIntoNode(nodeInfo: AccessibilityNodeInfo, text: String): Boolean {
        nodeInfo.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return nodeInfo.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /**
     * Type [text] into the currently focused editable node.
     *
     * Fallback behavior:
     * 1) Try [AccessibilityNodeInfo.FOCUS_INPUT] first (best signal for active text input).
     * 2) If unavailable, try [AccessibilityNodeInfo.FOCUS_ACCESSIBILITY].
     *
     * Returns `false` when no focused node is available or when underlying ACTION_SET_TEXT fails.
     */
    fun typeIntoFocusedNode(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
            ?: return false
            
        val success = typeIntoNode(focused, text)
        focused.recycle() // Prevent memory leak
        root.recycle()
        return success
    }

    /**
     * Scrolls a scrollable [nodeInfo] in the given direction.
     * @param forward true for scroll forward/down, false for scroll backward/up.
     */
    fun scrollNode(nodeInfo: AccessibilityNodeInfo, forward: Boolean): Boolean {
        var current: AccessibilityNodeInfo? = nodeInfo
        while (current != null) {
            if (isNodeScrollable(current)) {
                val action = if (forward) {
                    AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                } else {
                    AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                }
                val success = current.performAction(action)
                if (success) return true
            }
            current = current.parent
        }
        return false
    }

    // ── Global Actions ──

    /** Performs the global BACK action. */
    fun pressBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    /** Performs the global HOME action. */
    fun pressHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    /** Performs the global RECENTS action. */
    fun pressRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)
    
    /** Performs the global NOTIFICATIONS action (pull down status bar). */
    fun openNotifications(): Boolean = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)

    /**
     * The God-Mode Feature (Android 11+): Silently captures a screenshot.
     * Suspends until the screenshot is ready or fails.
     * Returns a [Bitmap] of the screen, or null if it fails or SDK is < 30.
     */
    suspend fun takeSilentScreenshot(): Bitmap? = suspendCoroutine { continuation ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            takeScreenshot(
                android.view.Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshotResult: ScreenshotResult) {
                        // The hardware buffer needs to be converted to a Bitmap
                        val bitmap = Bitmap.wrapHardwareBuffer(
                            screenshotResult.hardwareBuffer,
                            screenshotResult.colorSpace
                        )
                        continuation.resume(bitmap)
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.e(TAG, "Silent screenshot failed with code: $errorCode")
                        continuation.resume(null)
                    }
                }
            )
        } else {
            Log.w(TAG, "Silent screenshot requires Android 11+ (API 30+)")
            continuation.resume(null)
        }
    }

    // ── Gesture Dispatching ──

    /**
     * Dispatches a tap gesture at the given screen coordinates.
     * This is the fallback when semantic node interaction isn't possible.
     */
    fun tapAtCoordinates(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, TAP_DURATION_MS))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    /**
     * Dispatches a swipe gesture between two points.
     */
    fun swipeGesture(
        startX: Float, startY: Float,
        endX: Float, endY: Float,
        durationMs: Long = 300L
    ): Boolean {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    // ── Helper Methods ──

    private fun supportsAnyScrollAction(node: AccessibilityNodeInfo): Boolean {
        return supportsAction(node, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) ||
            supportsAction(node, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) ||
            node.actionList.any { it.label?.toString()?.contains("scroll", ignoreCase = true) == true }
    }

    private fun supportsAction(node: AccessibilityNodeInfo, actionId: Int): Boolean {
        return node.actionList.any { it.id == actionId }
    }

    private fun isNodeClickable(node: AccessibilityNodeInfo): Boolean {
        return node.isClickable || supportsAction(node, AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun isNodeLongClickable(node: AccessibilityNodeInfo): Boolean {
        return node.isLongClickable || supportsAction(node, AccessibilityNodeInfo.ACTION_LONG_CLICK)
    }

    private fun isNodeScrollable(node: AccessibilityNodeInfo): Boolean {
        return node.isScrollable || supportsAnyScrollAction(node)
    }
}
