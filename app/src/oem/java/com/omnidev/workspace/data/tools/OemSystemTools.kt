package com.omnidev.workspace.data.tools

import android.content.Context
import android.os.Bundle
import com.omnidev.workspace.core.policy.ConfirmationGate
import com.omnidev.workspace.core.policy.ConfirmationKind
import com.omnidev.workspace.data.oem.OemSystemClient

object OemSystemTools {
    fun definitions() = listOf(ToolDefinition("oem_system", "Use the ROM's isolated Omni System Service. No generic shell or lock bypass. Changes require confirmation; status reports missing ROM integration.", listOf(
        ToolParameter("action", "string", "System action", true, allowedValues = listOf("status", "settings_read", "settings_write", "volume_read", "volume_set", "wifi_read", "wifi_set")),
        ToolParameter("key", "string", "screen_brightness, screen_brightness_mode, screen_off_timeout, accelerometer_rotation", false, requiredForActions = listOf("settings_read", "settings_write")),
        ToolParameter("value", "integer", "Requested setting or volume value", false, requiredForActions = listOf("settings_write", "volume_set")),
        ToolParameter("stream", "integer", "3 for media, 4 for alarm", false, requiredForActions = listOf("volume_read", "volume_set")),
        ToolParameter("enabled", "boolean", "Requested Wi-Fi state", false, requiredForActions = listOf("wifi_set"))
    )))
    suspend fun execute(context: Context, args: Map<String, String>, gate: ConfirmationGate?): ToolExecutionResult {
        val action = args["action"] ?: return ToolExecutionResult("Missing action", true)
        if (action in setOf("settings_write", "volume_set", "wifi_set") &&
            gate?.request(ConfirmationKind.ASSISTANT_ACTION, "Change system state: $action\n${args.filterKeys { it != "action" }}", null) != true)
            return ToolExecutionResult("System action denied. No change was made.", true, classification = "USER_DENIED")
        val bundle = Bundle()
        try {
            args["key"]?.let { bundle.putString("key", it) }
            args["value"]?.let { bundle.putInt("value", it.toInt()) }
            args["stream"]?.let { bundle.putInt("stream", it.toInt()) }
            args["enabled"]?.let { bundle.putBoolean("enabled", it.toBooleanStrict()) }
            val result = OemSystemClient(context.applicationContext).execute(action, bundle)
            val ok = result.getBoolean("ok")
            val summary = result.keySet().sorted().joinToString("\n") { "$it: ${result.get(it)}" }
            return ToolExecutionResult(summary, !ok, classification = result.getString("code"), backend = "oem-system-binder", verification = if (ok) summary else null)
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (error: Exception) { return ToolExecutionResult("OEM integration unavailable: ${error.message}", true, classification = "ROM_NOT_PROVISIONED", backend = "oem-system-binder", retryable = false) }
    }
}
