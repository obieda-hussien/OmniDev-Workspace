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
 * 1. Auto-Enable ...
 * 2. Hybrid Tap ...
 */
object GodModeAccessibility {

    private const val TAG = "GodModeA11y"
    private const val SERVICE_CLASS = "com.omnidev.workspace/.data.accessibility.OmniAccessibilityService"

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
                is ShizukuResult.PartialSuccess -> currentResult.output.trim()
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
            is ShizukuResult.PartialSuccess ->
                "⚠️ Hardware tap partially succeeded (exit ${result.exitCode}): ${result.output}"
            is ShizukuResult.Failure -> "❌ Tap failed: ${result.reason}"
            is ShizukuResult.PermissionRequired -> "❌ Shizuku permission required"
            is ShizukuResult.Unavailable -> "❌ Shizuku unavailable"
        }
    }

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
            is ShizukuResult.PartialSuccess ->
                "⚠️ Hardware long-press partially succeeded (exit ${result.exitCode}): ${result.output}"
            is ShizukuResult.Failure -> "❌ Long-press failed: ${result.reason}"
            is ShizukuResult.PermissionRequired -> "❌ Shizuku permission required"
            is ShizukuResult.Unavailable -> "❌ Shizuku unavailable"
        }
    }
}
