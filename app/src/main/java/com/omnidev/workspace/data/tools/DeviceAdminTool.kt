package com.omnidev.workspace.data.tools

import android.content.Context
import android.content.Intent
import com.omnidev.workspace.core.policy.TierPolicyHolder
import com.omnidev.workspace.data.admin.*
import com.omnidev.workspace.ui.assistant.DeviceAccessActivity
import com.omnidev.workspace.ui.assistant.DeviceUnlockActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object DeviceAdminTool {
    fun definition() = ToolDefinition("device_admin",
        "Device administration with separate authenticated user consent. Never ask for a PIN in chat or pass credentials as arguments. " +
            "wake_screen wakes only; request_unlock uses Android authentication; unlock_with_saved_pin uses one locally authorized PIN attempt " +
            "on a supported standard SystemUI keypad (Admin only). Open consent_settings for user setup. Android protected screens remain protected.",
        listOf(ToolParameter("action", "string", "status, consent_settings, wake_screen, request_unlock, unlock_with_saved_pin, lock_screen, audit_log, security_report, set_password_min_length, set_lock_timeout, request_activation", true),
            ToolParameter("value", "string", "Non-secret numeric policy value only", required = false)))

    suspend fun execute(context: Context, args: Map<String, String>): ToolExecutionResult = withContext(Dispatchers.Main.immediate) {
        val action = args["action"]?.trim()?.lowercase() ?: "status"
        val consent = DeviceConsentStore(context)
        fun result(text: String, error: Boolean = false) = ToolExecutionResult(text, isError = error)
        fun denied(scope: DeviceConsentPolicy.Scope) = result("DENIED: enable ${scope.title} in Device access. Model requests and Admin auto-approval cannot grant it.", true)
        if (args.keys.any { it.lowercase() in setOf("pin", "password", "credential", "code", "secret") } ||
            (action in setOf("request_unlock", "unlock_with_saved_pin", "wake_screen") && args.keys.any { it != "action" }))
            return@withContext result("Do not pass unlock credentials to tools. Enter them only in the local Device access UI.", true)
        when (action) {
            "status" -> result(buildString {
                appendLine("Device Admin: ${OmniDeviceAdminReceiver.isAdminActive(context)}; Device Owner: ${OmniDeviceAdminReceiver.isDeviceOwner(context)}")
                appendLine("Locked: ${consent.locked()}; Screen interactive: ${context.getSystemService(android.os.PowerManager::class.java)?.isInteractive == true}")
                DeviceConsentPolicy.Scope.entries.forEach { appendLine("${it.name}: ${consent.enabled(it)}") }
                append("PIN stored locally: ${DevicePinVault(context).exists()}; One attempt authorized: ${consent.pinArmed()}")
            })
            "consent_settings" -> {
                runCatching { context.startActivity(Intent(context, DeviceAccessActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    .fold({ result("USER_ACTION_REQUIRED: device consent settings opened. Only the user can authorize access.") },
                        { result("USER_ACTION_REQUIRED: open Device access from Omni Settings.", true) })
            }
            "wake_screen", "request_unlock" -> {
                val scope = if (action == "wake_screen") DeviceConsentPolicy.Scope.WAKE else DeviceConsentPolicy.Scope.UNLOCK
                if (!consent.enabled(scope)) return@withContext denied(scope)
                val output = DeviceUnlockActivity.request(context, action == "request_unlock")
                OmniDeviceAdminReceiver.recordDeviceAccess(action.uppercase(), output.startsWith("AWAKE") || output.startsWith("UNLOCKED"))
                result(output, output.startsWith("DENIED") || output.startsWith("USER_ACTION_REQUIRED"))
            }
            "unlock_with_saved_pin" -> {
                if (TierPolicyHolder.current.tier != "ADMIN") return@withContext result("Local PIN unlock is available only in Admin.", true)
                val output = LocalPinUnlock.attempt(context)
                OmniDeviceAdminReceiver.recordDeviceAccess("LOCAL_PIN_UNLOCK", output.startsWith("UNLOCKED"))
                result(output, !output.startsWith("UNLOCKED"))
            }
            "lock_screen" -> {
                val success = OmniDeviceAdminReceiver.lockScreen(context)
                result(if (success) "Screen locked." else "Lock denied or Device Admin inactive.", !success)
            }
            "set_password_min_length" -> {
                val value = args["value"]?.toIntOrNull()?.takeIf { it in 4..16 }
                    ?: return@withContext result("Minimum password length must be 4..16.", true)
                val success = OmniDeviceAdminReceiver.setMinPasswordLength(context, value)
                result(if (success) "Minimum password length applied: $value." else "Policy unavailable; Android 11+ requires owner authority.", !success)
            }
            "set_lock_timeout" -> {
                val value = args["value"]?.toLongOrNull()?.takeIf { it in 5_000..86_400_000 }
                    ?: return@withContext result("Lock timeout must be 5000..86400000 milliseconds.", true)
                val success = OmniDeviceAdminReceiver.setMaxScreenLockTimeout(context, value)
                result(if (success) "Maximum lock timeout applied: $value ms." else "Policy unavailable or denied by Android.", !success)
            }
            "security_report" -> result(OmniDeviceAdminReceiver.getSecurityReport(context))
            "audit_log" -> result(OmniDeviceAdminReceiver.getAuditLog(30))
            "request_activation" -> {
                OmniDeviceAdminReceiver.requestAdminActivation(context)
                result("USER_ACTION_REQUIRED: approve Device Admin in Android. This does not grant owner authority or unlock consent.")
            }
            else -> result("Unsupported device_admin action.", true)
        }
    }
}
