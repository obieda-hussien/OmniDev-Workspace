package com.omnidev.workspace.data.accessibility

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.omnidev.workspace.data.ipc.PrivilegedExecutionManager

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
        if (!PrivilegedExecutionManager.isShizukuReady()) {
            return "❌ Shizuku is not available or not authorized. Cannot auto-enable Accessibility Service."
        }

        // Already connected?
        if (AccessibilityStateManager.isServiceConnected.value) {
            return "✅ OmniAccessibilityService is already enabled and connected."
        }

        try {
            // Step 1: Read current enabled services via unified privileged manager
            val currentServices = PrivilegedExecutionManager.executeCommand(
                "settings get secure enabled_accessibility_services"
            ).fold(
                onSuccess = { it.trim().let { s -> if (s == "null" || s.isBlank()) "" else s } },
                onFailure = {
                    Log.w(TAG, "Failed to read enabled_accessibility_services via privileged backend: ${it.message}")
                    ""
                }
            )

            // Step 2: Append our service if not already listed
            val newServices = if (currentServices.contains(SERVICE_CLASS)) {
                currentServices
            } else if (currentServices.isEmpty()) {
                SERVICE_CLASS
            } else {
                "$currentServices:$SERVICE_CLASS"
            }

            // Step 3: Write updated services list via unified privileged manager
            val putResult = PrivilegedExecutionManager.executeCommand(
                "settings put secure enabled_accessibility_services $newServices"
            )
            val putOk = putResult.fold(onSuccess = { true }, onFailure = {
                Log.e(TAG, "Failed to set accessibility services via privileged backend: ${it.message}")
                false
            })
            if (!putOk) {
                return "❌ Failed to enable service: ${putResult.exceptionOrNull()?.message}"
            }

            // Step 4: Enable accessibility globally via privileged manager
            val enableResult = PrivilegedExecutionManager.executeCommand(
                "settings put secure accessibility_enabled 1"
            )
            val enableOk = enableResult.fold(onSuccess = { true }, onFailure = {
                Log.e(TAG, "Failed to enable accessibility via privileged backend: ${it.message}")
                false
            })
            if (!enableOk) {
                return "❌ Failed to enable accessibility: ${enableResult.exceptionOrNull()?.message}"
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

        val tapCmd = "input tap $centerX $centerY"
        val tapResult = PrivilegedExecutionManager.executeCommand(tapCmd)
        return tapResult.fold(onSuccess = {
            "✅ Hardware tap at ($centerX, $centerY)"
        }, onFailure = { err ->
            val msg = err.message ?: "unknown error"
            when {
                msg.contains("permission", ignoreCase = true) -> "❌ Shizuku permission required (and fallback failed): $msg"
                msg.contains("shizuku", ignoreCase = true) || msg.contains("not running", ignoreCase = true) ->
                    "❌ Shizuku unavailable and fallback failed: $msg"
                else -> "❌ Tap failed: $msg"
            }
        })
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
        val swipeCmd = "input swipe $centerX $centerY $centerX $centerY $durationMs"
        val swipeResult = PrivilegedExecutionManager.executeCommand(swipeCmd)
        return swipeResult.fold(onSuccess = {
            "✅ Hardware long-press at ($centerX, $centerY) for ${durationMs}ms"
        }, onFailure = { err ->
            val msg = err.message ?: "unknown error"
            when {
                msg.contains("permission", ignoreCase = true) -> "❌ Shizuku permission required (and fallback failed): $msg"
                msg.contains("shizuku", ignoreCase = true) || msg.contains("not running", ignoreCase = true) ->
                    "❌ Shizuku unavailable and fallback failed: $msg"
                else -> "❌ Long-press failed: $msg"
            }
        })
    }
}
