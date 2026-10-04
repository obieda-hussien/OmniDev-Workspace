package com.omnidev.workspace.data.tools

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.omnidev.workspace.core.policy.TierPolicyHolder
import com.omnidev.workspace.core.privileged.PrivilegedExecutionFacadeHolder
import com.omnidev.workspace.core.privileged.PrivilegedResult
import com.omnidev.workspace.data.ipc.PrivilegedExecutionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Discover the permissions in the merged APK and on this device, including companion apps. */
object PermissionManagerTool {
    private const val REQUEST_CODE = 0x4F60
    private val aliases = mapOf(
        "camera" to Manifest.permission.CAMERA, "microphone" to Manifest.permission.RECORD_AUDIO,
        "mic" to Manifest.permission.RECORD_AUDIO, "location" to Manifest.permission.ACCESS_FINE_LOCATION,
        "fine_location" to Manifest.permission.ACCESS_FINE_LOCATION, "coarse_location" to Manifest.permission.ACCESS_COARSE_LOCATION,
        "background_location" to PermissionRequestPlan.BACKGROUND_LOCATION,
        "background_sensors" to PermissionRequestPlan.BACKGROUND_SENSORS,
        "background_health" to PermissionRequestPlan.BACKGROUND_HEALTH,
        "contacts" to Manifest.permission.READ_CONTACTS, "calendar" to Manifest.permission.READ_CALENDAR,
        "phone" to Manifest.permission.CALL_PHONE, "sms" to Manifest.permission.READ_SMS,
        "notifications" to "android.permission.POST_NOTIFICATIONS", "termux" to "com.termux.permission.RUN_COMMAND",
        "secure_settings" to "android.permission.WRITE_SECURE_SETTINGS", "logs" to "android.permission.READ_LOGS",
        "dump" to "android.permission.DUMP", "battery_stats" to "android.permission.BATTERY_STATS"
    )
    private val developmentKeys = listOf("secure_settings", "logs", "dump", "battery_stats")
    private fun resolve(key: String) = aliases[key.lowercase().trim()] ?: key.trim()
    private fun granted(context: Context, name: String) = ContextCompat.checkSelfPermission(context, name) == PackageManager.PERMISSION_GRANTED

    internal fun declaredPermissions(context: Context): Set<String> = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions?.toSet().orEmpty()
    }.getOrDefault(emptySet())

    internal fun permissionInfo(context: Context, name: String): PermissionInfo? = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPermissionInfo(name, 0)
    }.getOrNull()

    internal fun runtimePermissions(context: Context): List<String> = declaredPermissions(context).filter { name ->
        val info = permissionInfo(context, name)
        info != null && (info.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE) == PermissionInfo.PROTECTION_DANGEROUS &&
            PermissionRequestPlan.supported(name, Build.VERSION.SDK_INT)
    }.sorted()

    fun checkPermission(context: Context, permission: String): String {
        val key = permission.lowercase().trim()
        if (key in setOf("all", "audit_all", "capabilities")) return auditAll(context)
        if (key in DeviceAccessCatalog.entries.map { it.key } || key in setOf("root", "shizuku", "rish", "system", "device_owner", "profile_owner")) {
            return DeviceAccessCatalog.status(context, key)
        }
        val name = resolve(permission)
        if (name !in declaredPermissions(context)) return "NOT_DECLARED"
        if (permissionInfo(context, name) == null || !PermissionRequestPlan.supported(name, Build.VERSION.SDK_INT)) return "NOT_SUPPORTED"
        return if (granted(context, name)) "GRANTED" else "DENIED"
    }

    internal fun requiresPrivilegedApproval(context: Context, permission: String, backend: String): Boolean =
        backend == "root" || permission.lowercase().trim() in setOf("root", "privileged_bootstrap") ||
            permissionInfo(context, resolve(permission))?.let { (it.protectionLevel and PermissionInfo.PROTECTION_FLAG_DEVELOPMENT) != 0 } == true

    /** root is used only when the caller explicitly chooses backend=root. */
    suspend fun requestPermission(context: Context, permission: String, backend: String = "auto"): ToolExecutionResult {
        if (backend !in setOf("auto", "root")) return failure("Unknown backend: $backend", "INVALID_ARGUMENT")
        val key = permission.lowercase().trim()
        if (key == "access_center") {
            val handedOff = withContext(Dispatchers.Main) {
                com.omnidev.workspace.data.assistant.AssistantRuntime.openAccessCenter?.invoke() == true
            }
            if (handedOff) return pending("Device access opened; finish setup to return to the assistant conversation.")
            return launchIntent(context, Intent(context, com.omnidev.workspace.ui.assistant.DeviceAccessActivity::class.java), key)
        }
        if (key in setOf("all", "all_runtime", "bootstrap_max")) return requestAllRuntime(context, backend)
        if (key == "privileged_bootstrap") {
            val results = developmentKeys.map { "$it: ${requestPermission(context, it, backend).output}" }
            val verified = developmentKeys.all { granted(context, resolve(it)) }
            return ToolExecutionResult(results.joinToString("\n"), isError = !verified,
                classification = if (verified) "SUCCESS" else "PRIVILEGE_NOT_GRANTED", backend = backend)
        }
        if (key == "root") {
            if (!TierPolicyHolder.current.allowRoot) return failure("Root is unavailable in this build.", "TIER_BLOCKED")
            val ready = withContext(Dispatchers.IO) { PrivilegedExecutionManager.isRootAvailable(forceProbe = true) }
            return ToolExecutionResult(if (ready) "Root uid=0 verified." else "Root unavailable or superuser approval is pending. Allow OmniDev in your root manager and retry.",
                isError = !ready, classification = if (ready) "SUCCESS" else "USER_ACTION_REQUIRED", backend = "root")
        }
        if (key == "shizuku") {
            if (!TierPolicyHolder.current.allowShizuku) return failure("Shizuku is unavailable in this build.", "TIER_BLOCKED")
            if (!ShizukuCommandTool.isAvailable()) return pending("Start Shizuku via wireless debugging, ADB or root, then return.")
            if (ShizukuCommandTool.hasPermission()) return ToolExecutionResult("Shizuku grant verified.")
            return withContext(Dispatchers.Main) {
                runCatching { rikka.shizuku.Shizuku.requestPermission(0x4F61) }.fold(
                    onSuccess = { pending("Approve OmniDev in the Shizuku permission dialog, then recheck.") },
                    onFailure = { failure("Shizuku request failed: ${it.message}", "SHIZUKU_PERMISSION_REQUIRED") })
            }
        }
        if (key in setOf("device_owner", "profile_owner", "system", "rish") && DeviceAccessCatalog.status(context, key) == "GRANTED") return ToolExecutionResult("$key authority verified.")
        if (key in setOf("device_owner", "profile_owner", "system", "rish")) return pending(
            when (key) {
                "device_owner", "profile_owner" -> "Owner authority requires Android managed-device provisioning; Device Admin activation does not grant it. Current: ${DeviceAccessCatalog.status(context, key)}"
                "system" -> "System authority requires a genuine platform-signed/privileged installation and ROM entitlements. Current: ${DeviceAccessCatalog.status(context, key)}"
                else -> "Configure rish in OmniDev's execution settings. Current: ${DeviceAccessCatalog.status(context, key)}"
            })
        val entry = DeviceAccessCatalog.entries.firstOrNull { it.key == key }
        if (entry != null) {
            val status = DeviceAccessCatalog.status(context, key)
            if (status == "GRANTED") return ToolExecutionResult("$key verified granted.")
            if (status in setOf("TIER_BLOCKED", "NOT_SUPPORTED", "NOT_DECLARED")) return failure("$key: $status", status)
            val intent = DeviceAccessCatalog.intent(context, key) ?: return failure("No request flow for $key.", "NOT_SUPPORTED")
            return launchIntent(context, intent, key)
        }
        val name = resolve(permission)
        if (name !in declaredPermissions(context)) return failure("$name is absent from this build's merged manifest.", "NOT_DECLARED")
        val info = permissionInfo(context, name) ?: return failure("$name is not defined on this device. Install the companion app first if it defines the permission.", "NOT_SUPPORTED")
        if (!PermissionRequestPlan.supported(name, Build.VERSION.SDK_INT)) return failure("$name is not applicable on Android ${Build.VERSION.SDK_INT}.", "NOT_SUPPORTED")
        if (granted(context, name)) return ToolExecutionResult("$name verified granted.")
        val dangerous = (info.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE) == PermissionInfo.PROTECTION_DANGEROUS
        val development = (info.protectionLevel and PermissionInfo.PROTECTION_FLAG_DEVELOPMENT) != 0
        if (!dangerous && !development) return failure("$name requires its Android protection-level entitlement (signature, privileged install, role or normal install grant); a runtime dialog or pm grant cannot provide it.", "ENTITLEMENT_REQUIRED")

        // Do not issue implicit Shizuku approval dialogs repeatedly inside a bulk request.
        if (backend == "root" || privilegedBackendReady()) {
            val result = grantPrivileged(context, name, backend)
            if (granted(context, name)) return result
            if (!dangerous || backend == "root") return result
        } else if (development) return pending("$name needs authorized Shizuku/rish, system UID, or an explicitly selected root backend.")

        val prerequisites = PermissionRequestPlan.prerequisites(name)
        if (prerequisites.isNotEmpty() && prerequisites.none { granted(context, it) }) return pending("Grant a foreground prerequisite first: ${prerequisites.joinToString()}")
        // Android 11+ background location is chosen on the app permission page.
        if (name == PermissionRequestPlan.BACKGROUND_LOCATION && Build.VERSION.SDK_INT >= 30) return appSettings(context, "Choose Permissions → Location → Allow all the time.")
        if (name == PermissionRequestPlan.BACKGROUND_HEALTH) return appSettings(context, "Grant background health access through the device's health permission controller.")
        val batch = if (name in PermissionRequestPlan.staged) listOf(name) else PermissionRequestPlan.foregroundBatch(listOf(name), Build.VERSION.SDK_INT)
        val started = PermissionRequestBridge.requestRuntimePermissions(batch.filter { it in declaredPermissions(context) }.toTypedArray(), REQUEST_CODE)
        return pending(if (started) "Android permission dialog requested for ${batch.joinToString()}. Recheck after user approval. If Android no longer prompts, use the Access center's App permissions button."
            else "Open the Access center in OmniDev to request ${batch.joinToString()} from a foreground Activity.")
    }

    private fun privilegedBackendReady(): Boolean {
        val policy = TierPolicyHolder.current
        return (policy.allowShizuku && (PrivilegedExecutionManager.isShizukuReady() || PrivilegedExecutionManager.isRishReady())) ||
            (policy.allowSystemIntegration && android.os.Process.myUid() % 100000 == 1000)
    }
    private suspend fun grantPrivileged(context: Context, name: String, backend: String): ToolExecutionResult {
        // Neither packageName nor permission names are interpolated as unquoted shell code.
        fun quote(value: String) = "'${value.replace("'", "'\\''")}'"
        val command = "pm grant --user ${android.os.Process.myUid() / 100000} ${quote(context.packageName)} ${quote(name)}"
        com.omnidev.workspace.core.policy.OmniAuditLog.record(
            tier = TierPolicyHolder.current.tier,
            autoApproved = TierPolicyHolder.current.autoApproveConfirmations,
            kind = com.omnidev.workspace.core.policy.ConfirmationKind.SHIZUKU_COMMAND,
            preview = "Permission grant via $backend: $command"
        )
        val outcome = try {
            if (backend == "root") {
                if (!TierPolicyHolder.current.allowRoot) return failure("Root is unavailable in this tier.", "TIER_BLOCKED")
                PrivilegedExecutionManager.executeRootCommand(command).fold({ it }, { it.message.orEmpty() })
            } else if (TierPolicyHolder.current.allowShizuku &&
                (PrivilegedExecutionManager.isShizukuReady() || PrivilegedExecutionManager.isRishReady())) {
                PrivilegedExecutionManager.executeCommand(command).fold({ it }, { it.message.orEmpty() })
            } else when (val result = PrivilegedExecutionFacadeHolder.current.execute(command)) {
                is PrivilegedResult.Success -> result.output
                is PrivilegedResult.Partial -> "${result.error} (exit ${result.exitCode})"
                is PrivilegedResult.Denied -> result.reason
                is PrivilegedResult.Failure -> result.error
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { error.message.orEmpty() }
        val verified = granted(context, name)
        return ToolExecutionResult(if (verified) "$name granted and verified through $backend." else "$name remains denied. ${outcome.take(1200)}",
            isError = !verified, classification = if (verified) "SUCCESS" else "PRIVILEGE_NOT_GRANTED", backend = backend,
            verification = if (verified) "PackageManager permission read-back" else null)
    }

    private suspend fun requestAllRuntime(context: Context, backend: String): ToolExecutionResult {
        if (backend == "root" && !TierPolicyHolder.current.allowRoot) return failure("Root is unavailable in this build.", "TIER_BLOCKED")
        val missing = runtimePermissions(context).filterNot { granted(context, it) }
        if (missing.isEmpty()) return ToolExecutionResult("All supported declared runtime permissions verified granted.\n${DeviceAccessCatalog.summary(context)}")
        if (backend == "root" || privilegedBackendReady()) for (name in missing) grantPrivileged(context, name, backend)
        val unresolved = missing.filterNot { granted(context, it) }
        val foreground = PermissionRequestPlan.foregroundBatch(unresolved, Build.VERSION.SDK_INT).filter { it in declaredPermissions(context) }
        val staged = unresolved.filter { it in PermissionRequestPlan.staged }
        val started = foreground.isNotEmpty() && PermissionRequestBridge.requestRuntimePermissions(foreground.toTypedArray(), REQUEST_CODE)
        return ToolExecutionResult(buildString {
            appendLine("Runtime bootstrap: ${missing.size - unresolved.size}/${missing.size} missing grants verified this pass.")
            if (foreground.isNotEmpty()) appendLine(if (started) "Android dialog requested; ${foreground.size} permissions still require read-back after approval." else "Open the Access center to show the runtime dialog.")
            if (staged.isNotEmpty()) appendLine("Separate background steps still required: ${staged.joinToString()}. Grant foreground access first, then request each background permission individually.")
            appendLine("Special access and protected permissions are separate; open request_permission(permission='access_center').")
            append(DeviceAccessCatalog.summary(context))
        }, isError = unresolved.isNotEmpty(), classification = if (unresolved.isEmpty()) "SUCCESS" else "USER_ACTION_REQUIRED", backend = backend)
    }

    private fun auditAll(context: Context): String = buildString {
        val declared = declaredPermissions(context).sorted()
        appendLine("OmniDev access audit (${declared.size} declarations; ${runtimePermissions(context).size} supported runtime permissions)")
        appendLine(DeviceAccessCatalog.summary(context))
        appendLine("Manifest permission state (declaration is not a grant):")
        for (name in declared) {
            val info = permissionInfo(context, name)
            val state = when {
                info == null || !PermissionRequestPlan.supported(name, Build.VERSION.SDK_INT) -> "NOT_SUPPORTED"
                granted(context, name) -> "GRANTED"
                else -> "DENIED"
            }
            appendLine("$name: $state; protection=${info?.protectionLevel ?: "unknown"}")
        }
    }.trimEnd()

    private suspend fun appSettings(context: Context, reason: String) = launchIntent(context,
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")), reason)
    private fun pending(message: String) = ToolExecutionResult("USER_ACTION_REQUIRED: $message", isError = true,
        classification = "USER_ACTION_REQUIRED", backend = "android-access", retryable = false)
    private fun failure(message: String, classification: String) = ToolExecutionResult(message, isError = true,
        classification = classification, backend = "android-access", retryable = false)
    private suspend fun launchIntent(context: Context, intent: Intent, label: String): ToolExecutionResult = withContext(Dispatchers.Main) {
        runCatching {
            (PermissionRequestBridge.foregroundActivity() ?: context).startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            pending("Opened $label. Android is waiting for user action; recheck on return.")
        }.getOrElse { failure("Could not open $label: ${it.message}. Use Android Settings → Apps → OmniDev → Special app access.", "ANDROID_SETTINGS_LAUNCH_FAILED") }
    }
    fun getToolDefinitions() = listOf(
        ToolDefinition("check_permission", "Read actual permission/backend status. permission='all' returns all declarations with protection levels, special access, root cache, Shizuku/rish, genuine system UID and owner states. No passive root prompt. Also accepts Android/companion permission names and aliases.",
            parameters = listOf(ToolParameter("permission", "string", "Permission, alias or all.", required = true))),
        ToolDefinition("request_permission", "Request declared access and verify privileged grants. bootstrap_max requests supported runtime permissions; background flows are separate. access_center opens setup UI. privileged_bootstrap grants development permissions (secure_settings/logs/dump/battery_stats) with an authorized backend. Special keys include assistant, accessibility, notification_listener, overlay, usage_stats, write_settings, notification_policy, all_files, manage_media, install_unknown_apps, exact_alarms, battery_optimization, input_method, vpn, device_admin, shizuku, root, termux. Owner/system roles need genuine provisioning; connected app grants are separate.",
            parameters = listOf(ToolParameter("permission", "string", "Permission, alias or setup key.", required = true),
                ToolParameter("backend", "string", "auto (authorized shell/system) or root (explicit superuser request); never silently escalates.", required = false)))
    )
}
