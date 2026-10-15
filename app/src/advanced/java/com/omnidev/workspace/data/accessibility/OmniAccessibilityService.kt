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
import android.view.accessibility.AccessibilityWindowInfo
import com.omnidev.workspace.data.assistant.AssistantRuntime
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
        private val TEACHING_EVENT_TYPES = setOf(
            AccessibilityEvent.TYPE_VIEW_CLICKED, AccessibilityEvent.TYPE_VIEW_LONG_CLICKED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED, AccessibilityEvent.TYPE_VIEW_SCROLLED
        )

        /**
         * Live reference to the running service instance.
         * Used by SemanticUITool to invoke actions (click, type, scroll, gesture).
         * Null when the service is disconnected.
         */
        @Volatile
        var instance: OmniAccessibilityService? = null
            private set
    }

    // Remote accessibility reads can block for Android's full IPC timeout. They must never
    // run on main, and cancellation must never create a replacement worker per event.
    private val observationDispatcher = Executors.newSingleThreadExecutor { task ->
        Thread(task, "Omni-accessibility-observer").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val observationScope = CoroutineScope(SupervisorJob() + observationDispatcher)
    private val refreshLoop = ConflatedRefreshLoop(observationScope) { refreshRootNode() }
    private data class TeachingEvent(val session: Long, val event: AccessibilityEvent)
    private val teachingOverflow = AtomicLong(-1L)
    private val teachingEvents = Channel<TeachingEvent>(32, onUndeliveredElement = { it.event.recycle() })
    private var teachingWorkerStarted = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        AccessibilityStateManager.setServiceConnected(true)
        if (!teachingWorkerStarted) observationScope.launch {
            for ((session, event) in teachingEvents) {
                try {
                    val hub = com.omnidev.workspace.data.routines.RoutineLearningHub.get(this@OmniAccessibilityService)
                    val overflowSession = teachingOverflow.getAndSet(-1L)
                    if (overflowSession >= 0) {
                        // Missing actions must not silently become a replayable demonstration.
                        hub.stopTeachingSession(overflowSession)
                        Log.w(TAG, "Teaching stopped because the event buffer overflowed")
                    }
                    hub.onAccessibilityEvent(event, session)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Teaching event could not be recorded", e)
                } finally { event.recycle() }
            }
        }
        teachingWorkerStarted = true
        refreshLoop.request()
        Log.i(TAG, "OmniAccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        // No event.source, tree IPC, singleton initialization or disk access in this callback.
        val session = if (event.eventType in TEACHING_EVENT_TYPES)
            com.omnidev.workspace.data.routines.RoutineLearningHub.activeTeachingSession() else null
        if (session != null) {
            val copy = AccessibilityEvent.obtain(event)
            if (teachingEvents.trySend(TeachingEvent(session, copy)).isFailure) {
                copy.recycle()
                teachingOverflow.set(session)
            }
        }
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // Activities and dialogs within one package also invalidate in-flight scans.
                // Invalidate even for our overlay; only foreground attribution is filtered.
                AccessibilityStateManager.updateRootNode(null)
                SemanticUITool.clearSnapshot()
                val pkg = event.packageName?.toString()
                if (!(AssistantRuntime.targetingScreen && pkg == packageName)) {
                    AccessibilityStateManager.updateActiveWindow(pkg, event.className?.toString())
                }
                refreshLoop.request()
            }
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                AccessibilityStateManager.updateRootNode(null)
                SemanticUITool.clearSnapshot()
                refreshLoop.request()
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> refreshLoop.request()
        }
    }

    override fun onInterrupt() {
        AccessibilityStateManager.updateRootNode(null)
        SemanticUITool.clearSnapshot()
        Log.w(TAG, "OmniAccessibilityService interrupted")
    }

    override fun onDestroy() {
        instance = null
        refreshLoop.close()
        teachingEvents.cancel()
        observationScope.cancel()
        // close() does not join a blocked Binder thread on main.
        observationDispatcher.close()
        AccessibilityStateManager.setServiceConnected(false)
        SemanticUITool.clearSnapshot()
        Log.i(TAG, "OmniAccessibilityService destroyed")
        super.onDestroy()
    }

    /** Fetch and analyze a privately owned root on the single observer; publish metadata on main. */
    private suspend fun refreshRootNode() {
        val generation = AccessibilityStateManager.snapshotGeneration()
        var root: AccessibilityNodeInfo? = null
        try {
            root = assistantTargetRoot()
            val pkg = root?.packageName?.toString()
            val denied = com.omnidev.workspace.data.admin.DeviceAccessGuard.check(this, pkg) != null
            if (denied) { root?.recycle(); root = null }
            val analysis = root?.let(AccessibilityScreenAnalyzer::analyze)
            withContext(Dispatchers.Main) {
                if (instance !== this@OmniAccessibilityService) return@withContext
                if (com.omnidev.workspace.data.admin.DeviceAccessGuard.check(this@OmniAccessibilityService, pkg) != null) {
                    AccessibilityStateManager.updateRootNode(null)
                    SemanticUITool.clearSnapshot()
                } else if (AccessibilityStateManager.publishRootNode(root, analysis, generation)) {
                    if (root != null && AssistantRuntime.targetingScreen)
                        AccessibilityStateManager.updateActiveWindow(pkg, null)
                    root = null // Ownership transferred to AccessibilityStateManager.
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to refresh root node", e)
        } finally { root?.recycle() }
    }

    /** Retrieve the application underneath our assistant window, which owns foreground focus. */
    private fun assistantTargetRoot(): AccessibilityNodeInfo? {
        if (!AssistantRuntime.targetingScreen) return rootInActiveWindow
        val snapshot = windows
        try {
            for (window in snapshot.sortedByDescending { it.layer }) {
                if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                val root = window.root ?: continue
                if (root.packageName?.toString() != packageName || AssistantRuntime.nativeHost) return root
                root.recycle()
            }
        } finally { snapshot.forEach { it.recycle() } }
        return rootInActiveWindow?.let { root ->
            if (AssistantRuntime.get(this).state.value.visible && root.packageName?.toString() == packageName) { root.recycle(); null } else root
        }
    }

    /** Caller owns and recycles the fresh foreground root. */
    fun routineRoot(): AccessibilityNodeInfo? = assistantTargetRoot()?.let { root ->
        if (com.omnidev.workspace.data.admin.DeviceConsentStore(this).locked() ||
            com.omnidev.workspace.data.admin.DeviceAccessGuard.check(this, root.packageName?.toString()) != null) { root.recycle(); null } else root
    }

    // ── Public API for SemanticUITool ──

    /**
     * Clicks a node identified by [nodeInfo].
     * If the node itself is not clickable, traverses up to find a clickable parent.
     * @return true if the click action was performed successfully.
     */
    fun clickNode(nodeInfo: AccessibilityNodeInfo): Boolean {
        if (com.omnidev.workspace.data.admin.DeviceAccessGuard.check(this, nodeInfo.packageName?.toString(), mutation = true) != null) return false
        var current: AccessibilityNodeInfo? = nodeInfo
        while (current != null) {
            if (isNodeClickable(current)) {
                val success = current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (success) return true
            }
            current = current.parent
        }
        
        // Fallback to coordinate-based click if semantic click fails
        if (AssistantRuntime.targetingScreen && AssistantRuntime.get(this).state.value.visible) return false
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
        if (com.omnidev.workspace.data.admin.DeviceAccessGuard.check(this, nodeInfo.packageName?.toString(), mutation = true) != null) return false
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
        if (com.omnidev.workspace.data.admin.DeviceAccessGuard.check(this, nodeInfo.packageName?.toString(), mutation = true) != null) return false
        if (nodeInfo.isPassword) return false
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
        val root = assistantTargetRoot() ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
            ?: run { root.recycle(); return false }

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
        if (com.omnidev.workspace.data.admin.DeviceAccessGuard.check(this, nodeInfo.packageName?.toString(), mutation = true) != null) return false
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

    private fun guardedGlobalAction(action: Int): Boolean =
        com.omnidev.workspace.data.admin.DeviceAccessGuard.check(this, mutation = true) == null && performGlobalAction(action)

    // ── Global Actions ──

    /** Performs the global BACK action. */
    fun pressBack(): Boolean = guardedGlobalAction(GLOBAL_ACTION_BACK)

    /** Performs the global HOME action. */
    fun pressHome(): Boolean = guardedGlobalAction(GLOBAL_ACTION_HOME)

    /** Performs the global RECENTS action. */
    fun pressRecents(): Boolean = guardedGlobalAction(GLOBAL_ACTION_RECENTS)
    
    /** Performs the global NOTIFICATIONS action (pull down status bar). */
    fun openNotifications(): Boolean = guardedGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)

    /**
     * The God-Mode Feature (Android 11+): Silently captures a screenshot.
     * Suspends until the screenshot is ready or fails.
     * Returns a [Bitmap] of the screen, or null if it fails or SDK is < 30.
     */
    suspend fun takeSilentScreenshot(): Bitmap? = suspendCoroutine { continuation ->
        if (com.omnidev.workspace.data.admin.DeviceAccessGuard.check(this, screenshot = true) != null) {
            continuation.resume(null); return@suspendCoroutine
        }
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
                        screenshotResult.hardwareBuffer.close()
                        if (com.omnidev.workspace.data.admin.DeviceAccessGuard.check(this@OmniAccessibilityService, screenshot = true) != null) {
                            bitmap?.recycle(); continuation.resume(null)
                        } else continuation.resume(bitmap)
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
        if (com.omnidev.workspace.data.admin.DeviceAccessGuard.check(this, mutation = true) != null) return false
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
        if (com.omnidev.workspace.data.admin.DeviceAccessGuard.check(this, mutation = true) != null) return false
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
