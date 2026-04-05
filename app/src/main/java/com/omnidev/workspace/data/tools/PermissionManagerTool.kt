package com.omnidev.workspace.data.tools

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.ComponentName
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.omnidev.workspace.MainActivity
import com.omnidev.workspace.data.accessibility.AccessibilityStateManager
import com.omnidev.workspace.data.accessibility.OmniAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * PermissionManagerTool — A powerful tool for the agent to audit, request, and
 * manage system permissions and special access at runtime.
 */
object PermissionManagerTool {

    /**
     * Checks the status of a specific permission or special access.
     */
    fun checkPermission(context: Context, permission: String): String {
        return when (permission.lowercase()) {
            "vpn" -> if (VpnService.prepare(context) == null) "GRANTED" else "DENIED"
            "accessibility" -> if (isAccessibilityServiceEnabled(context)) "GRANTED" else "DENIED"
            "notification_listener" -> if (isNotificationListenerEnabled(context)) "GRANTED" else "DENIED"
            "usage_stats" -> if (hasUsageStatsPermission(context)) "GRANTED" else "DENIED"
            "overlay" -> if (Settings.canDrawOverlays(context)) "GRANTED" else "DENIED"
            "write_settings" -> if (Settings.System.canWrite(context)) "GRANTED" else "DENIED"
            "all_files" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && android.os.Environment.isExternalStorageManager()) "GRANTED" else "DENIED"
            else -> {
                val status = ContextCompat.checkSelfPermission(context, permission)
                if (status == PackageManager.PERMISSION_GRANTED) "GRANTED" else "DENIED"
            }
        }
    }

    /**
     * Requests a permission or opens the relevant settings page for special access.
     */
    suspend fun requestPermission(context: Context, permission: String): ToolExecutionResult = withContext(Dispatchers.Main) {
        val intent = when (permission.lowercase()) {
            "vpn" -> VpnService.prepare(context)
            "accessibility" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            "notification_listener" -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            "usage_stats" -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            "overlay" -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
            "write_settings" -> Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}"))
            "all_files" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
            } else null
            "battery_optimization" -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
            else -> {
                // Standard runtime permission
                if (context is Activity) {
                    ActivityCompat.requestPermissions(context, arrayOf(permission), 1001)
                    return@withContext ToolExecutionResult("Requested runtime permission: $permission. User must approve on device.")
                }
                null
            }
        }

        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            ToolExecutionResult("Opened settings/dialog for: $permission. User must complete the action.")
        } else {
            ToolExecutionResult("Permission '$permission' is already granted or not applicable.", isError = true)
        }
    }

    private fun isAccessibilityServiceEnabled(context: Context): Boolean {
        if (AccessibilityStateManager.isServiceConnected.value) return true
        val expectedComponent = ComponentName(context, OmniAccessibilityService::class.java)
        val expectedShort = expectedComponent.flattenToShortString()
        val expectedFull = expectedComponent.flattenToString()
        val enabledServices = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        return enabledServices
            .split(':')
            .map { it.trim() }
            .any { it == expectedShort || it == expectedFull }
    }

    private fun isNotificationListenerEnabled(context: Context): Boolean {
        val pkgName = context.packageName
        val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
        return flat?.contains(pkgName) == true
    }

    private fun hasUsageStatsPermission(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.packageName)
        } else {
            appOps.checkOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.packageName)
        }
        return mode == android.app.AppOpsManager.MODE_ALLOWED
    }

    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "check_permission",
            description = "Check if a specific permission or special access is granted. " +
                "Supported special keys: 'vpn', 'accessibility', 'notification_listener', 'usage_stats', 'overlay', 'write_settings', 'all_files'. " +
                "Also supports standard Android permission strings (e.g., 'android.permission.CAMERA').",
            parameters = listOf(
                ToolParameter("permission", "string", "The permission name or special key to check.", required = true)
            )
        ),
        ToolDefinition(
            name = "request_permission",
            description = "Request a permission or open the relevant system settings page for special access. " +
                "Supported special keys: 'vpn', 'accessibility', 'notification_listener', 'usage_stats', 'overlay', 'write_settings', 'all_files', 'battery_optimization'.",
            parameters = listOf(
                ToolParameter("permission", "string", "The permission name or special key to request.", required = true)
            )
        )
    )
}
