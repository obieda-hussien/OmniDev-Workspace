package com.omnidev.workspace.data.tools

import android.Manifest
import android.app.AppOpsManager
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
        "dump" to "android.permission.DUMP", "battery_stats" to "android.permission.BATTERY_STATS",
        "configuration" to "android.permission.CHANGE_CONFIGURATION", "app_ops_stats" to "android.permission.GET_APP_OPS_STATS",
        "cross_user" to "android.permission.INTERACT_ACROSS_USERS", "cross_profile" to "android.permission.INTERACT_ACROSS_PROFILES"
    )
    private val developmentKeys = listOf("secure_settings", "logs", "dump", "battery_stats", "configuration", "app_ops_stats", "cross_user")
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
        AppOpAccessPlan.entry(key)?.let { return appOpStatus(context, it) }
        if (key in DeviceAccessCatalog.entries.map { it.key } || key in setOf("root", "shizuku", "rish", "system", "device_owner", "profile_owner")) {
            return DeviceAccessCatalog.status(context, key)
        }
        val name = resolve(permission)
        if (name !in declaredPermissions(context)) return "NOT_DECLARED"
        if (permissionInfo(context, name) == null || !PermissionRequestPlan.supported(name, Build.VERSION.SDK_INT)) return "NOT_SUPPORTED"
        return if (granted(context, name)) "GRANTED" else "DENIED"
    }

    internal fun requiresPrivilegedApproval(context: Context, permission: String, backend: String): Boolean =
        backend == "root" || AppOpAccessPlan.entry(permission.lowercase().trim()) != null ||
            permission.lowercase().trim() in setOf("root", "privileged_bootstrap", "appop_bootstrap") ||
            permissionInfo(context, resolve(permission))?.let { (it.protectionLevel and PermissionInfo.PROTECTION_FLAG_DEVELOPMENT) != 0 } == true

    internal fun approvalPreview(context: Context, permission: String, backend: String): String {
        val key = permission.lowercase().trim()
        val operation = AppOpAccessPlan.entry(key)
        if (operation != null) return "Set OmniDev's ${operation.specialKey} AppOp to ${AppOpAccessPlan.mode(key)} via $backend; package ${context.packageName}, user ${android.os.Process.myUid() / 100000}. Other applications are unchanged."
        if (key == "appop_bootstrap") return "Allow OmniDev's own overlay, usage statistics, settings, shared-file and media AppOps where supported, via $backend; package ${context.packageName}, user ${android.os.Process.myUid() / 100000}."
        if (key == "privileged_bootstrap") return "Request ${developmentKeys.joinToString { resolve(it) }} for OmniDev via $backend; verify each grant."
        return "Request access: $permission; backend: $backend; target: OmniDev only."
    }

    /** root is used only when the caller explicitly chooses backend=root. */
    suspend fun requestPermission(context: Context, permission: String, backend: String = "auto"): ToolExecutionResult {
        if (backend !in setOf("auto", "root")) return failure("Unknown backend: $backend", "INVALID_ARGUMENT")
        val key = permission.lowercase().trim()
        AppOpAccessPlan.entry(key)?.let { return requestAppOp(context, key, backend) }
        if (key == "appop_bootstrap") {
            val eligible = AppOpAccessPlan.entries.filter { Build.VERSION.SDK_INT >= it.minSdk && it.permission in declaredPermissions(context) }
            val results = eligible.map { requestAppOp(context, it.key, backend) }
            val verified = eligible.isNotEmpty() && results.all { !it.isError }
            return ToolExecutionResult(results.joinToString("\n") { it.output }.ifBlank { "No eligible special-access declarations in this build." },
                isError = !verified, classification = if (verified) "SUCCESS" else "PRIVILEGE_NOT_GRANTED", backend = backend)
        }
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
            val intent = runCatching { DeviceAccessCatalog.intent(context, key) }.getOrNull()
                ?: return failure(if (key == "cross_profile") "Android requires an eligible managed profile, the same app in both profiles, and administrator authorization before it can show cross-profile consent."
                    else "No request flow for $key.", "NOT_SUPPORTED")
            return launchIntent(context, intent, key)
        }
        val name = resolve(permission)
        if (name !in declaredPermissions(context)) return failure("$name is absent from this build's merged manifest.", "NOT_DECLARED")
        val info = permissionInfo(context, name) ?: return failure("$name is not defined on this device. Install the companion app first if it defines the permission.", "NOT_SUPPORTED")
        if (!PermissionRequestPlan.supported(name, Build.VERSION.SDK_INT)) return failure("$name is not applicable on Android ${Build.VERSION.SDK_INT}.", "NOT_SUPPORTED")
        DeviceAccessCatalog.setupKey(name)?.let { specialKey ->
            if (backend == "root") AppOpAccessPlan.entries.firstOrNull { it.permission == name }?.let { return requestAppOp(context, it.key, backend) }
            return requestPermission(context, specialKey, backend)
        }
        if (granted(context, name)) return ToolExecutionResult("$name verified granted.")
        val dangerous = (info.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE) == PermissionInfo.PROTECTION_DANGEROUS
        val development = (info.protectionLevel and PermissionInfo.PROTECTION_FLAG_DEVELOPMENT) != 0
        if (!dangerous && !development) return failure("$name requires its Android protection-level entitlement (signature, privileged install, role or normal install grant); a runtime dialog or pm grant cannot provide it.", "ENTITLEMENT_REQUIRED")

        val prerequisites = PermissionRequestPlan.prerequisites(name)
        if (prerequisites.isNotEmpty() && prerequisites.none { granted(context, it) }) return pending("Grant a foreground prerequisite first: ${prerequisites.joinToString()}")

        // Do not issue implicit Shizuku approval dialogs repeatedly inside a bulk request.
        if (backend == "root" || privilegedBackendReady()) {
            val result = grantPrivileged(context, name, backend)
            if (granted(context, name)) return result
            if (!dangerous || backend == "root") return result
        } else if (development) return pending("$name needs authorized Shizuku/rish, system UID, or an explicitly selected root backend.")

        // Android 11+ background location is chosen on the app permission page.
        if (PermissionRequestPlan.usesAppDetails(name, Build.VERSION.SDK_INT)) return appSettings(context, "Choose Permissions → Location → Allow all the time.")
        // The runtime request routes health permissions to Android's Health Connect controller.
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
        val outcome = executePrivilegedCommand(command, backend)
        val verified = granted(context, name)
        return ToolExecutionResult(if (verified) "$name granted and verified through $backend." else "$name remains denied. ${outcome.take(1200)}",
            isError = !verified, classification = if (verified) "SUCCESS" else "PRIVILEGE_NOT_GRANTED", backend = backend,
            verification = if (verified) "PackageManager permission read-back" else null)
    }

    private suspend fun executePrivilegedCommand(command: String, backend: String): String = withContext(Dispatchers.IO) {
        try {
            if (backend == "root") {
                if (!TierPolicyHolder.current.allowRoot) return@withContext "Root is unavailable in this tier."
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
    }

    private fun appOpMode(context: Context, entry: AppOpAccessPlan.Entry): Int? = runCatching {
        if (Build.VERSION.SDK_INT < entry.minSdk) return null
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return null
        val operation = "android:${entry.operation.lowercase(java.util.Locale.ROOT)}"
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= 36) ops.checkOpRawNoThrow(operation, android.os.Process.myUid(), context.packageName, null)
        else if (Build.VERSION.SDK_INT >= 29) ops.unsafeCheckOpRawNoThrow(operation, android.os.Process.myUid(), context.packageName)
        else ops.checkOpNoThrow(operation, android.os.Process.myUid(), context.packageName)
    }.getOrNull()

    internal fun appOpStatus(context: Context, entry: AppOpAccessPlan.Entry): String {
        val mode = when (appOpMode(context, entry)) {
            AppOpsManager.MODE_ALLOWED -> "allowed"
            AppOpsManager.MODE_IGNORED -> "ignored"
            AppOpsManager.MODE_ERRORED -> "errored"
            AppOpsManager.MODE_DEFAULT -> "default"
            AppOpsManager.MODE_FOREGROUND -> "foreground"
            else -> "unavailable"
        }
        return "${DeviceAccessCatalog.status(context, entry.specialKey)}; appop=$mode"
    }

    private suspend fun requestAppOp(context: Context, key: String, backend: String): ToolExecutionResult {
        val entry = AppOpAccessPlan.entry(key) ?: return failure("Unknown AppOp.", "INVALID_ARGUMENT")
        if (backend == "root" && !TierPolicyHolder.current.allowRoot) return failure("Root is unavailable in this build.", "TIER_BLOCKED")
        if (backend != "root" && !privilegedBackendReady()) return pending("Authorize Shizuku/rish or use a genuine system UID; root must be explicitly selected. You can also use Android's ${entry.specialKey} setup screen.")
        val command = runCatching { AppOpAccessPlan.command(key, context.packageName, android.os.Process.myUid() / 100000,
            Build.VERSION.SDK_INT, declaredPermissions(context)) }.getOrElse { return failure(it.message.orEmpty(), "NOT_SUPPORTED") }
        com.omnidev.workspace.core.policy.OmniAuditLog.record(tier = TierPolicyHolder.current.tier,
            autoApproved = TierPolicyHolder.current.autoApproveConfirmations,
            kind = com.omnidev.workspace.core.policy.ConfirmationKind.SHIZUKU_COMMAND, preview = "Own-app special access via $backend: $command")
        val outcome = executePrivilegedCommand(command, backend)
        val mode = appOpMode(context, entry)
        val reset = AppOpAccessPlan.mode(key) == "default"
        val verified = AppOpAccessPlan.verified(key, mode, DeviceAccessCatalog.status(context, entry.specialKey) == "GRANTED")
        return ToolExecutionResult("$key: ${appOpStatus(context, entry)}. " +
            if (verified) "${if (reset) "Default mode restored" else "Special access granted"} and verified for OmniDev only."
            else "Requested mode was not verified. ${outcome.take(1000)}", isError = !verified,
            classification = if (verified) "SUCCESS" else "PRIVILEGE_NOT_GRANTED", backend = backend,
            verification = if (verified) "AppOps mode and effective special-access read-back" else null)
    }

    private suspend fun requestAllRuntime(context: Context, backend: String): ToolExecutionResult {
        if (backend == "root" && !TierPolicyHolder.current.allowRoot) return failure("Root is unavailable in this build.", "TIER_BLOCKED")
        val missing = runtimePermissions(context).filterNot { granted(context, it) }
        if (missing.isEmpty()) return ToolExecutionResult("All supported declared runtime permissions verified granted.\n${DeviceAccessCatalog.summary(context)}")
        if (backend == "root" || privilegedBackendReady()) for (name in missing.filterNot { it in PermissionRequestPlan.staged }) grantPrivileged(context, name, backend)
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
        appendLine("Own-app special-access modes:")
        for (entry in AppOpAccessPlan.entries) appendLine("${entry.key}: ${appOpStatus(context, entry)}; reset with reset_${entry.key}")
        appendLine("Manifest permission state (declaration is not a grant):")
        for (name in declared) {
            val info = permissionInfo(context, name)
            val state = when {
                info == null || !PermissionRequestPlan.supported(name, Build.VERSION.SDK_INT) -> "NOT_SUPPORTED"
                granted(context, name) -> "GRANTED"
                else -> "DENIED"
            }
            appendLine("$name: $state; route=${permissionRoute(context, name)}; protection=${info?.protectionLevel ?: "unknown"}")
        }
    }.trimEnd()

    internal fun permissionRoute(context: Context, name: String): String {
        if (!PermissionRequestPlan.supported(name, Build.VERSION.SDK_INT)) return "unsupported-on-device"
        val info = permissionInfo(context, name) ?: return "platform-or-companion-not-installed"
        if (name == "android.permission.INTERACT_ACROSS_PROFILES") return "managed-profile-consent"
        if (DeviceAccessCatalog.setupKey(name) != null) return "special-settings-or-authorized-appop"
        if ((info.protectionLevel and PermissionInfo.PROTECTION_FLAG_DEVELOPMENT) != 0) return "authorized-development-backend"
        return when (info.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE) {
            PermissionInfo.PROTECTION_NORMAL -> "install-time"
            PermissionInfo.PROTECTION_DANGEROUS -> if (name in PermissionRequestPlan.staged) "separate-background-controller" else "runtime-dialog"
            else -> "platform-signature-privileged-or-role-entitlement"
        }
    }

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
        ToolDefinition("request_permission", "Request declared access and verify privileged grants. bootstrap_max requests supported runtime permissions; background flows are separate. access_center opens setup UI. privileged_bootstrap requests development grants (secure_settings/logs/dump/battery_stats/configuration/app_ops_stats/cross_user). appop_bootstrap or appop_overlay/appop_usage_stats/appop_write_settings/appop_all_files/appop_manage_media sets allowed modes for OmniDev's own package only; reset_appop_* restores defaults. Special keys include assistant, accessibility, notification_listener, overlay, usage_stats, write_settings, notification_policy, all_files, manage_media, install_unknown_apps, exact_alarms, battery_optimization, input_method, vpn, device_admin, cross_profile, shizuku, root, termux. Owner/system roles need genuine provisioning; connected app grants are separate.",
            parameters = listOf(ToolParameter("permission", "string", "Permission, alias or setup key.", required = true),
                ToolParameter("backend", "string", "auto (authorized shell/system) or root (explicit superuser request); never silently escalates.", required = false)))
    )
}
