package com.omnidev.workspace.data.accessibility

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.omnidev.workspace.data.tools.ShizukuCommandTool
import com.omnidev.workspace.data.tools.ShizukuResult

/**
 * GodModeAccessibility — Shizuku + Accessibility hybrid layer.
 *
 * Provides two God-Mode capabilities:
 *
 * 1. **Auto-Enable**: Silently enables [OmniAccessibilityService] via Shizuku shell
 *    commands (`settings put secure enabled_accessibility_services`), eliminating the
 *    need for the user to manually navigate to Android Accessibility Settings.
 *
 * 2. **Hybrid Tap**: Uses the Accessibility tree to find the exact bounding rect of a
 *    semantic node, then dispatches a hardware-level tap via Shizuku `input tap x y`.
 *    This bypasses app-level click listeners that may block [AccessibilityNodeInfo.performAction].
 */
object GodModeAccessibility {

    private const val TAG = "GodModeA11y"
    private const val SERVICE_CLASS = "com.omnidev.workspace/.data.accessibility.OmniAccessibilityService"

    /**
     * Auto-enables [OmniAccessibilityService] via Shizuku without user interaction.
     *
     * Steps:
     * 1. Reads the current `enabled_accessibility_services` setting.
     * 2. If our service is not listed, appends it (colon-separated).
     * 3. Writes the updated list back.
     * 4. Sets `accessibility_enabled` to `1`.
     *
     * @return A human-readable status message.
     */
    suspend fun autoEnableOmniVision(): String {
        if (!ShizukuCommandTool.isAvailable()) {
            return "❌ Shizuku is not available. Cannot auto-enable Accessibility Service."
        }
        if (!ShizukuCommandTool.hasPermission()) {
            return "❌ Shizuku permission not granted. Cannot auto-enable Accessibility Service."
        }

        // Already connected?
        if (AccessibilityStateManager.isServiceConnected.value) {
            return "✅ OmniAccessibilityService is already enabled and connected."
        }

        try {
            // Step 1: Read current enabled services
            val currentResult = ShizukuCommandTool.execute(
                "settings get secure enabled_accessibility_services"
            )
            val currentServices = when (currentResult) {
                is ShizukuResult.Success -> currentResult.output.trim()
                    .let { if (it == "null" || it.isBlank()) "" else it }
                else -> ""
            }

            // Step 2: Append our service if not already listed
            val newServices = if (currentServices.contains(SERVICE_CLASS)) {
                currentServices
            } else if (currentServices.isEmpty()) {
                SERVICE_CLASS
            } else {
                "$currentServices:$SERVICE_CLASS"
            }

            // Step 3: Write updated services list
            val putResult = ShizukuCommandTool.execute(
                "settings put secure enabled_accessibility_services $newServices"
            )
            if (putResult is ShizukuResult.Failure) {
                Log.e(TAG, "Failed to set accessibility services: ${putResult.reason}")
                return "❌ Failed to enable service: ${putResult.reason}"
            }

            // Step 4: Enable accessibility globally
            val enableResult = ShizukuCommandTool.execute(
                "settings put secure accessibility_enabled 1"
            )
            if (enableResult is ShizukuResult.Failure) {
                Log.e(TAG, "Failed to enable accessibility: ${enableResult.reason}")
                return "❌ Failed to enable accessibility: ${enableResult.reason}"
            }

            Log.i(TAG, "OmniAccessibilityService auto-enabled via Shizuku")
            return "✅ OmniAccessibilityService has been auto-enabled via Shizuku. " +
                "The service will connect within seconds."
        } catch (e: Exception) {
            Log.e(TAG, "autoEnableOmniVision failed", e)
            return "❌ Auto-enable failed: ${e.message}"
        }
    }

    /**
     * Performs a hardware-level tap at the center of a semantic node's bounding rect.
     *
     * Unlike [AccessibilityNodeInfo.performAction], this uses Shizuku to execute
     * `input tap x y` — a raw hardware event that bypasses app-level click restrictions.
     *
     * @param node The target [AccessibilityNodeInfo] to tap.
     * @return A human-readable result message.
     */
    suspend fun hybridTap(node: AccessibilityNodeInfo): String {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        val centerX = bounds.centerX()
        val centerY = bounds.centerY()

        if (centerX <= 0 || centerY <= 0) {
            return "❌ Node has invalid bounds: $bounds"
        }

        return when (val result = ShizukuCommandTool.execute("input tap $centerX $centerY")) {
            is ShizukuResult.Success -> "✅ Hardware tap at ($centerX, $centerY)"
            is ShizukuResult.Failure -> "❌ Tap failed: ${result.reason}"
            is ShizukuResult.PermissionRequired -> "❌ Shizuku permission required"
            is ShizukuResult.Unavailable -> "❌ Shizuku unavailable"
        }
    }

    /**
     * Performs a hardware-level long-press at the center of a semantic node.
     * Uses `input swipe x y x y duration` to simulate a long press.
     *
     * @param node The target [AccessibilityNodeInfo] to long-press.
     * @param durationMs Duration in milliseconds for the long-press (default 800ms).
     * @return A human-readable result message.
     */
    suspend fun hybridLongPress(
        node: AccessibilityNodeInfo,
        durationMs: Int = 800
    ): String {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        val centerX = bounds.centerX()
        val centerY = bounds.centerY()

        if (centerX <= 0 || centerY <= 0) {
            return "❌ Node has invalid bounds: $bounds"
        }

        // `input swipe` with same start/end coordinates acts as a long press
        return when (val result = ShizukuCommandTool.execute(
            "input swipe $centerX $centerY $centerX $centerY $durationMs"
        )) {
            is ShizukuResult.Success -> "✅ Hardware long-press at ($centerX, $centerY) for ${durationMs}ms"
            is ShizukuResult.Failure -> "❌ Long-press failed: ${result.reason}"
            is ShizukuResult.PermissionRequired -> "❌ Shizuku permission required"
            is ShizukuResult.Unavailable -> "❌ Shizuku unavailable"
        }
    }
}
