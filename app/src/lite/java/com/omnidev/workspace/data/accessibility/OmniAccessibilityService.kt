package com.omnidev.workspace.data.accessibility

/** Not a Service. Lite cannot register or instantiate an accessibility backend. */
@Suppress("UNUSED_PARAMETER")
class OmniAccessibilityService private constructor() : android.content.ContextWrapper(null) {
    val rootInActiveWindow: android.view.accessibility.AccessibilityNodeInfo? get() = null
    val windows: List<android.view.accessibility.AccessibilityWindowInfo> get() = emptyList()
    fun routineRoot(): android.view.accessibility.AccessibilityNodeInfo? = null
    fun clickNode(nodeInfo: android.view.accessibility.AccessibilityNodeInfo) = false
    fun longClickNode(nodeInfo: android.view.accessibility.AccessibilityNodeInfo) = false
    fun typeIntoNode(nodeInfo: android.view.accessibility.AccessibilityNodeInfo, text: String) = false
    fun typeIntoFocusedNode(text: String) = false
    fun scrollNode(nodeInfo: android.view.accessibility.AccessibilityNodeInfo, forward: Boolean) = false
    fun pressBack() = false
    fun pressHome() = false
    fun pressRecents() = false
    fun openNotifications() = false
    suspend fun takeSilentScreenshot(): android.graphics.Bitmap? = null
    fun tapAtCoordinates(x: Float, y: Float) = false
    fun swipeGesture(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long = 300L) = false
    companion object { val instance: OmniAccessibilityService? get() = null }
}
