package com.omnidev.workspace.data.tools

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.AlarmClock
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar

// ─────────────────────────────────────────────────────────────────
//  System Assistant Tools
//
//  A collection of object-level tools that let the ReAct agent interact with
//  common Android system features: communication, planning, hardware toggles,
//  location, device info, and app management.
//
//  **Safety contract**: Like all agent tools, every invocation is gated behind
//  [com.omnidev.workspace.ui.chat.ConfirmationGate] — no action fires without
//  explicit user approval.
// ─────────────────────────────────────────────────────────────────

/**
 * Initiates phone calls, SMS messages, and WhatsApp conversations.
 *
 * Each method builds the appropriate [Intent], attaches [Intent.FLAG_ACTIVITY_NEW_TASK],
 * and fires it via [Context.startActivity].
 */
object CommunicationTool {

    fun execute(
        context: Context,
        method: String,
        target: String,
        message: String? = null
    ): ToolExecutionResult {
        return runCatching {
            val intent = when (method.lowercase()) {
                "call" -> Intent(Intent.ACTION_CALL).apply {
                    data = Uri.parse("tel:$target")
                }
                "sms" -> Intent(Intent.ACTION_SENDTO).apply {
                    data = Uri.parse("smsto:$target")
                    if (!message.isNullOrBlank()) putExtra("sms_body", message)
                }
                "whatsapp" -> {
                    val encodedMsg = Uri.encode(message.orEmpty())
                    Intent(Intent.ACTION_VIEW).apply {
                        // Explicitly targeting WhatsApp package if installed prevents chooser dialogs
                        setPackage("com.whatsapp")
                        data = Uri.parse("https://api.whatsapp.com/send?phone=$target&text=$encodedMsg")
                    }
                }
                else -> return ToolExecutionResult(
                    output = "Unknown communication method '$method'. Use call, sms, or whatsapp.",
                    isError = true
                )
            }
            
            // Fallback for WhatsApp if standard package is not found (e.g., WA Business)
            if (method.lowercase() == "whatsapp" && intent.resolveActivity(context.packageManager) == null) {
                intent.setPackage("com.whatsapp.w4b") // WhatsApp Business
                if (intent.resolveActivity(context.packageManager) == null) {
                    intent.setPackage(null) // Fallback to browser/chooser if neither is installed
                }
            }

            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(intent)
            ToolExecutionResult(output = "✅ ${method.uppercase()} intent fired to $target.")
        }.getOrElse { e ->
            ToolExecutionResult(
                output = "Failed to fire $method intent: ${e.message}",
                isError = true
            )
        }
    }

    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "communicate_tool",
            description = "Initiate a phone call, send an SMS, or open a WhatsApp conversation. " +
                "Expects a valid phone number in the 'target' field. " +
                "If you only have a person's name, you MUST use 'search_contacts' first to retrieve their number.",
            parameters = listOf(
                ToolParameter("method", "string", "Communication method: 'call', 'sms', or 'whatsapp'.", required = true),
                ToolParameter("target", "string", "Phone number of the recipient. Use 'search_contacts' first if you only have a name.", required = true),
                ToolParameter("message", "string", "Text body for SMS or WhatsApp messages.", required = false)
            )
        )
    )
}

/**
 * Attempts to silently grant a standard Android permission to this app via Shizuku.
 */
suspend fun ensurePermissionViaShizuku(permission: String, packageName: String, context: Context): Boolean {
    if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) {
        return true
    }
    val result = ShizukuCommandTool.execute("pm grant $packageName $permission")
    return result is ShizukuResult.Success
}

/**
 * Creates alarms and calendar events on behalf of the user.
 */
object PlannerTool {

    private const val PACKAGE_NAME = "com.omnidev.workspace"
    private const val PERMISSION_SET_ALARM = "com.android.alarm.permission.SET_ALARM"

    private fun shellEscape(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$").replace("`", "\\`")

    suspend fun execute(
        context: Context,
        action: String,
        title: String,
        timeMillis: Long
    ): ToolExecutionResult {
        when (action.lowercase()) {
            "alarm" -> {
                val cal = Calendar.getInstance().apply { this.timeInMillis = timeMillis }
                val hour = cal.get(Calendar.HOUR_OF_DAY)
                val minute = cal.get(Calendar.MINUTE)

                ensurePermissionViaShizuku(PERMISSION_SET_ALARM, PACKAGE_NAME, context)
                val canSkipUi = ContextCompat.checkSelfPermission(context, PERMISSION_SET_ALARM) == PackageManager.PERMISSION_GRANTED

                fun tryLaunchAlarmIntent(skipUi: Boolean): Boolean = runCatching {
                    val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                        putExtra(AlarmClock.EXTRA_HOUR, hour)
                        putExtra(AlarmClock.EXTRA_MINUTES, minute)
                        putExtra(AlarmClock.EXTRA_MESSAGE, title)
                        putExtra(AlarmClock.EXTRA_SKIP_UI, skipUi)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                    true
                }.getOrDefault(false)

                val intentResult = if (canSkipUi) tryLaunchAlarmIntent(skipUi = true) else false
                val intentUiFallbackResult = if (intentResult) true else tryLaunchAlarmIntent(skipUi = false)

                if (intentUiFallbackResult) {
                    return ToolExecutionResult(
                        output = if (intentResult) "✅ Alarm set for %02d:%02d — \"%s\"." else "✅ Opened alarm app with pre-filled time %02d:%02d — \"%s\".".format(hour, minute, title)
                    )
                }

                // Fallback: ADB shell
                val safeTitle = shellEscape(title)
                val adbCmd = "am start -a android.intent.action.SET_ALARM" +
                        " --ei android.intent.extra.alarm.HOUR $hour" +
                        " --ei android.intent.extra.alarm.MINUTES $minute" +
                        " --es android.intent.extra.alarm.MESSAGE \"$safeTitle\"" +
                        " --ez android.intent.extra.alarm.SKIP_UI true"
                        
                return when (val r = ShizukuCommandTool.execute(adbCmd)) {
                    is ShizukuResult.Success ->
                        ToolExecutionResult(
                            output = "✅ Alarm set for %02d:%02d via Android shell — \"%s\".".format(hour, minute, title),
                            classification = "SUCCESS",
                            backend = "shizuku-user-service"
                        )
                    is ShizukuResult.PartialSuccess -> ToolExecutionResult(
                        output = "Alarm command exited ${r.exitCode}: ${r.output}",
                        isError = true,
                        classification = "NON_ZERO_EXIT",
                        exitCode = r.exitCode,
                        backend = "shizuku-user-service"
                    )
                    is ShizukuResult.Failure -> ToolExecutionResult(output = "Failed to set alarm: ${r.reason}", isError = true)
                    else -> ToolExecutionResult(output = "Could not set alarm — Intent failed and Shizuku is unavailable.", isError = true)
                }
            }
            "calendar" -> {
                ensurePermissionViaShizuku(android.Manifest.permission.READ_CALENDAR, PACKAGE_NAME, context)
                ensurePermissionViaShizuku(android.Manifest.permission.WRITE_CALENDAR, PACKAGE_NAME, context)

                val intentResult = runCatching {
                    val intent = Intent(Intent.ACTION_INSERT).apply {
                        data = CalendarContract.Events.CONTENT_URI
                        putExtra(CalendarContract.Events.TITLE, title)
                        putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, timeMillis)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                    true
                }.getOrDefault(false)

                return if (intentResult) {
                    ToolExecutionResult(output = "✅ Calendar event created: \"$title\".")
                } else {
                    ToolExecutionResult("Failed to open calendar. Ensure a calendar app is installed.", isError = true)
                }
            }
            else -> return ToolExecutionResult("Unknown planner action '$action'. Use 'alarm' or 'calendar'.", isError = true)
        }
    }

    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "planner_tool",
            description = "Set an alarm or create a calendar event.",
            parameters = listOf(
                ToolParameter("action", "string", "Planner action: 'alarm' or 'calendar'.", required = true),
                ToolParameter("title", "string", "Label for the alarm or title of the calendar event.", required = true),
                ToolParameter("timeMillis", "string", "Target time as epoch milliseconds (string-encoded long).", required = true)
            )
        )
    )
}

/**
 * Toggles hardware/connectivity settings via Shizuku shell commands.
 */
object HardwareToggleTool {

    suspend fun execute(setting: String, state: String): ToolExecutionResult =
        withContext(Dispatchers.IO) {
            val enabled = state.toBooleanStrictOrNull()
                ?: return@withContext ToolExecutionResult("Invalid state '$state'. Use 'true' or 'false'.", isError = true)

            when (setting.lowercase()) {
                "wifi" -> executeShizuku("svc wifi ${if (enabled) "enable" else "disable"}")
                "bluetooth" -> executeShizuku("svc bluetooth ${if (enabled) "enable" else "disable"}")
                "data" -> executeShizuku("svc data ${if (enabled) "enable" else "disable"}")
                "airplane" -> {
                    // FIX: Modern Android requires the broadcast to actually apply the radio change
                    val value = if (enabled) "1" else "0"
                    val boolState = if (enabled) "true" else "false"
                    executeShizuku("settings put global airplane_mode_on $value && am broadcast -a android.intent.action.AIRPLANE_MODE --ez state $boolState")
                }
                "location" -> {
                    // FIX: Modern Android 10+ uses `cmd location` instead of the deprecated settings key
                    executeShizuku("cmd location set-location-enabled ${if (enabled) "true" else "false"}")
                }
                "dnd" -> ToolExecutionResult(
                    output = "DND cannot be toggled purely via shell. Use NotificationManager API instead.",
                    isError = true
                )
                else -> ToolExecutionResult(
                    output = "Unknown setting '$setting'. Supported: wifi, bluetooth, data, airplane, location, dnd.",
                    isError = true
                )
            }
        }

    private suspend fun executeShizuku(command: String): ToolExecutionResult {
        return when (val result = ShizukuCommandTool.execute(command)) {
            is ShizukuResult.Success -> ToolExecutionResult(
                output = "✅ State changed successfully.",
                classification = "SUCCESS",
                backend = "shizuku-user-service"
            )
            is ShizukuResult.PartialSuccess -> ToolExecutionResult(
                output = "State command exited ${result.exitCode}: ${result.output}",
                isError = true,
                classification = "NON_ZERO_EXIT",
                exitCode = result.exitCode,
                backend = "shizuku-user-service"
            )
            is ShizukuResult.Failure -> ToolExecutionResult(
                output = result.reason, isError = true, backend = "shizuku-user-service"
            )
            is ShizukuResult.PermissionRequired -> ToolExecutionResult(
                output = result.message, isError = true, classification = "SHIZUKU_PERMISSION_REQUIRED",
                backend = "shizuku-user-service", persistentFailure = true
            )
            is ShizukuResult.Unavailable -> ToolExecutionResult(
                output = result.message, isError = true, classification = "SHIZUKU_UNAVAILABLE",
                backend = "shizuku-user-service", persistentFailure = true
            )
        }
    }

    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "hardware_toggle_tool",
            description = "Toggle device hardware/connectivity settings (Wi-Fi, Bluetooth, mobile data, airplane mode, location).",
            parameters = listOf(
                ToolParameter("setting", "string", "Setting to toggle: 'wifi', 'bluetooth', 'data', 'airplane', 'location'.", required = true),
                ToolParameter("state", "string", "'true' to enable or 'false' to disable.", required = true)
            )
        )
    )
}

/**
 * Retrieves the device's last known location.
 */
object LocationTool {

    @Suppress("MissingPermission")
    fun execute(context: Context): ToolExecutionResult {
        return runCatching {
            val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

            if (!hasFine && !hasCoarse) {
                return ToolExecutionResult("Location permission not granted.", isError = true)
            }

            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager
                ?: return ToolExecutionResult("Location service unavailable.", isError = true)

            val providers = locationManager.getProviders(true)
            val location = providers.firstNotNullOfOrNull { provider ->
                locationManager.getLastKnownLocation(provider)
            }

            if (location == null) {
                ToolExecutionResult("No last-known location available.", isError = true)
            } else {
                ToolExecutionResult(
                    output = buildString {
                        appendLine("Latitude:  ${location.latitude}")
                        appendLine("Longitude: ${location.longitude}")
                        appendLine("Accuracy:  ${location.accuracy} m")
                        append("Provider:  ${location.provider}")
                    }
                )
            }
        }.getOrElse { e -> ToolExecutionResult("Failed to retrieve location: ${e.message}", isError = true) }
    }

    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(name = "get_current_location", description = "Get device's last GPS location.", parameters = emptyList())
    )
}

/**
 * Reads device information: battery level, storage usage, network state, and metadata.
 */
object DeviceInfoTool {

    fun execute(context: Context, infoType: String = "all"): ToolExecutionResult {
        return runCatching {
            val output = when (infoType.lowercase()) {
                "battery" -> getBatteryInfo(context)
                "storage" -> getStorageInfo()
                "network" -> getNetworkInfo(context)
                "all" -> buildString {
                    appendLine("── Device ──")
                    appendLine("Model:    ${Build.MODEL}")
                    appendLine("Brand:    ${Build.BRAND}")
                    appendLine("Android:  ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
                    appendLine("Build:    ${Build.DISPLAY}")
                    appendLine("\n── Battery ──\n${getBatteryInfo(context)}")
                    appendLine("\n── Storage ──\n${getStorageInfo()}")
                    appendLine("\n── Network ──\n${getNetworkInfo(context)}")
                }
                else -> return ToolExecutionResult("Unknown infoType '$infoType'.", isError = true)
            }
            ToolExecutionResult(output = output)
        }.getOrElse { e -> ToolExecutionResult("Failed to read device info: ${e.message}", isError = true) }
    }

    private fun getBatteryInfo(context: Context): String {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return "Unavailable."
        return "Level: ${bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)}%  |  Charging: ${bm.isCharging}"
    }

    private fun getStorageInfo(): String = buildString {
        val internal = StatFs(Environment.getDataDirectory().path)
        appendLine("Internal: ${internal.availableBytes / (1024 * 1024)} MB free / ${internal.totalBytes / (1024 * 1024)} MB total")
        @Suppress("DEPRECATION")
        val extDir = Environment.getExternalStorageDirectory()
        if (extDir.exists()) {
            val external = StatFs(extDir.path)
            append("External: ${external.availableBytes / (1024 * 1024)} MB free / ${external.totalBytes / (1024 * 1024)} MB total")
        }
    }

    private fun getNetworkInfo(context: Context): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return "Unavailable."
        val network = cm.activeNetwork ?: return "No active network."
        val caps = cm.getNetworkCapabilities(network) ?: return "No capabilities."
        return buildString {
            appendLine("Connected: true")
            appendLine("Wi-Fi:     ${caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)}")
            appendLine("Cellular:  ${caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)}")
            append("VPN:       ${caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)}")
        }
    }

    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "get_device_info",
            description = "Retrieve device information: battery, storage, network, or all.",
            parameters = listOf(ToolParameter("infoType", "string", "Category: 'battery', 'storage', 'network', or 'all'.", required = false))
        )
    )
}

/**
 * Lists installed apps, queries app details, and performs privileged package operations.
 */
object AppManagerTool {

    suspend fun execute(
        context: Context,
        action: String,
        packageName: String? = null,
        includeSystem: Boolean = false
    ): ToolExecutionResult = withContext(Dispatchers.IO) {
        runCatching {
            when (action.lowercase()) {
                "list_installed" -> listInstalled(context, includeSystem)
                "app_info" -> requirePackage(packageName) ?: getAppInfo(context, packageName!!)
                "launch_app" -> requirePackage(packageName) ?: launchApp(context, packageName!!)
                "force_stop" -> requirePackage(packageName) ?: forceStop(packageName!!)
                "clear_data" -> requirePackage(packageName) ?: clearData(packageName!!)
                else -> ToolExecutionResult("Unknown action.", isError = true)
            }
        }.getOrElse { e -> ToolExecutionResult("App manager error: ${e.message}", isError = true) }
    }

    private fun requirePackage(packageName: String?): ToolExecutionResult? {
        return if (packageName.isNullOrBlank()) ToolExecutionResult("packageName is required.", isError = true) else null
    }

    private fun listInstalled(context: Context, includeSystem: Boolean): ToolExecutionResult {
        val pm = context.packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { includeSystem || (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
            .sortedBy { pm.getApplicationLabel(it).toString().lowercase() }

        // FIX: Truncate output to avoid LLM Context Window explosion
        val maxAppsToShow = 150 
        val output = buildString {
            appendLine("Installed applications (${apps.size}):")
            if (apps.size > maxAppsToShow) appendLine("Showing first $maxAppsToShow. Narrow search if needed.")
            appendLine()
            for (app in apps.take(maxAppsToShow)) {
                val label = pm.getApplicationLabel(app)
                val isSys = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                appendLine("• $label (${app.packageName})${if (isSys) " [system]" else ""}")
            }
        }.trim()
        return ToolExecutionResult(output = output)
    }

    private fun launchApp(context: Context, packageName: String): ToolExecutionResult {
        val pm = context.packageManager
        val launchIntent = pm.getLaunchIntentForPackage(packageName)
            ?: return ToolExecutionResult("No launch intent found for '$packageName'.", isError = true)
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(launchIntent)
            ToolExecutionResult("✅ Launched $packageName.")
        } catch (e: Exception) {
            ToolExecutionResult("Failed to launch: ${e.message}", isError = true)
        }
    }

    @Suppress("DEPRECATION")
    private fun getAppInfo(context: Context, packageName: String): ToolExecutionResult {
        val pm = context.packageManager
        val appInfo = pm.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
        val pkgInfo = pm.getPackageInfo(packageName, 0)
        val label = pm.getApplicationLabel(appInfo)
        
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pkgInfo.longVersionCode else pkgInfo.versionCode.toLong()

        val output = buildString {
            appendLine("App:        $label")
            appendLine("Package:    $packageName")
            appendLine("Version:    ${pkgInfo.versionName} (code $versionCode)")
            appendLine("System app: ${(appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0}")
            appendLine("Enabled:    ${appInfo.enabled}")
        }.trim()
        return ToolExecutionResult(output = output)
    }

    private suspend fun forceStop(packageName: String): ToolExecutionResult {
        return when (val result = ShizukuCommandTool.execute("am force-stop $packageName")) {
            is ShizukuResult.Success -> ToolExecutionResult(
                output = "✅ Force-stopped $packageName.",
                classification = "SUCCESS",
                backend = "shizuku-user-service"
            )
            is ShizukuResult.PartialSuccess -> ToolExecutionResult(
                output = "Force-stop exited ${result.exitCode}: ${result.output}",
                isError = true,
                classification = "NON_ZERO_EXIT",
                exitCode = result.exitCode,
                backend = "shizuku-user-service"
            )
            is ShizukuResult.Failure -> ToolExecutionResult(
                result.reason, true, classification = "ANDROID_COMMAND_FAILED", backend = "shizuku-user-service"
            )
            is ShizukuResult.PermissionRequired -> ToolExecutionResult(
                result.message, true, classification = "SHIZUKU_PERMISSION_REQUIRED",
                backend = "shizuku-user-service", persistentFailure = true
            )
            is ShizukuResult.Unavailable -> ToolExecutionResult(
                result.message, true, classification = "SHIZUKU_UNAVAILABLE",
                backend = "shizuku-user-service", persistentFailure = true
            )
        }
    }

    private suspend fun clearData(packageName: String): ToolExecutionResult {
        return when (val result = ShizukuCommandTool.execute("pm clear $packageName")) {
            is ShizukuResult.Success -> ToolExecutionResult(
                output = "✅ Cleared all data for $packageName.",
                classification = "SUCCESS",
                backend = "shizuku-user-service"
            )
            is ShizukuResult.PartialSuccess -> ToolExecutionResult(
                output = "pm clear exited ${result.exitCode}: ${result.output}",
                isError = true,
                classification = "NON_ZERO_EXIT",
                exitCode = result.exitCode,
                backend = "shizuku-user-service"
            )
            is ShizukuResult.Failure -> ToolExecutionResult(
                result.reason, true, classification = "ANDROID_COMMAND_FAILED", backend = "shizuku-user-service"
            )
            is ShizukuResult.PermissionRequired -> ToolExecutionResult(
                result.message, true, classification = "SHIZUKU_PERMISSION_REQUIRED",
                backend = "shizuku-user-service", persistentFailure = true
            )
            is ShizukuResult.Unavailable -> ToolExecutionResult(
                result.message, true, classification = "SHIZUKU_UNAVAILABLE",
                backend = "shizuku-user-service", persistentFailure = true
            )
        }
    }

    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "app_manager_tool",
            description = "Manage installed applications. CRITICAL: Use 'launch_app' to open any app by package name.",
            parameters = listOf(
                ToolParameter("action", "string", "Action: 'list_installed', 'app_info', 'launch_app', 'force_stop', or 'clear_data'.", required = true),
                ToolParameter("packageName", "string", "Target package name.", required = false),
                ToolParameter("includeSystem", "boolean", "If 'list_installed' should include system apps (default false).", required = false)
            )
        )
    )
}
