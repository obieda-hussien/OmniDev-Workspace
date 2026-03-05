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

// ────────────────────────────────────────────────────────────────────────────────
//  System Assistant Tools
//
//  A collection of object-level tools that let the ReAct agent interact with
//  common Android system features: communication, planning, hardware toggles,
//  location, device info, and app management.
//
//  **Safety contract**: Like all agent tools, every invocation is gated behind
//  [com.omnidev.workspace.ui.chat.ConfirmationGate] — no action fires without
//  explicit user approval.
// ────────────────────────────────────────────────────────────────────────────────

/**
 * Initiates phone calls, SMS messages, and WhatsApp conversations.
 *
 * Each method builds the appropriate [Intent], attaches [Intent.FLAG_ACTIVITY_NEW_TASK],
 * and fires it via [Context.startActivity].
 */
object CommunicationTool {

    /**
     * Dispatches a communication intent.
     *
     * @param context  Application context used to start the activity.
     * @param method   One of `"call"`, `"sms"`, or `"whatsapp"`.
     * @param target   Phone number (E.164 or local format).
     * @param message  Optional text body for SMS / WhatsApp.
     * @return [ToolExecutionResult] describing success or failure.
     */
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
                        data = Uri.parse(
                            "https://api.whatsapp.com/send?phone=$target&text=$encodedMsg"
                        )
                    }
                }
                else -> return ToolExecutionResult(
                    output = "Unknown communication method '$method'. Use call, sms, or whatsapp.",
                    isError = true
                )
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

    /** Tool definitions exposed to the agent schema. */
    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "communicate_tool",
            description = "Initiate a phone call, send an SMS, or open a WhatsApp conversation.",
            parameters = listOf(
                ToolParameter(
                    name = "method",
                    type = "string",
                    description = "Communication method: 'call', 'sms', or 'whatsapp'.",
                    required = true
                ),
                ToolParameter(
                    name = "target",
                    type = "string",
                    description = "Phone number of the recipient (E.164 or local format).",
                    required = true
                ),
                ToolParameter(
                    name = "message",
                    type = "string",
                    description = "Text body for SMS or WhatsApp messages.",
                    required = false
                )
            )
        )
    )
}

/**
 * Creates alarms and calendar events on behalf of the user.
 */
object PlannerTool {

    /**
     * Schedules an alarm or inserts a calendar event.
     *
     * @param context   Application context.
     * @param action    `"alarm"` or `"calendar"`.
     * @param title     Label / title for the alarm or event.
     * @param timeMillis Epoch milliseconds representing the target time.
     * @return [ToolExecutionResult] describing success or failure.
     */
    fun execute(
        context: Context,
        action: String,
        title: String,
        timeMillis: Long
    ): ToolExecutionResult {
        return runCatching {
            when (action.lowercase()) {
                "alarm" -> {
                    val cal = Calendar.getInstance().apply { this.timeInMillis = timeMillis }
                    val hour = cal.get(Calendar.HOUR_OF_DAY)
                    val minute = cal.get(Calendar.MINUTE)
                    val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                        putExtra(AlarmClock.EXTRA_HOUR, hour)
                        putExtra(AlarmClock.EXTRA_MINUTES, minute)
                        putExtra(AlarmClock.EXTRA_MESSAGE, title)
                        putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                    ToolExecutionResult(
                        output = "✅ Alarm set for %02d:%02d — \"%s\".".format(hour, minute, title)
                    )
                }
                "calendar" -> {
                    val intent = Intent(Intent.ACTION_INSERT).apply {
                        data = CalendarContract.Events.CONTENT_URI
                        putExtra(CalendarContract.Events.TITLE, title)
                        putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, timeMillis)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                    ToolExecutionResult(
                        output = "✅ Calendar event created: \"$title\"."
                    )
                }
                else -> ToolExecutionResult(
                    output = "Unknown planner action '$action'. Use 'alarm' or 'calendar'.",
                    isError = true
                )
            }
        }.getOrElse { e ->
            ToolExecutionResult(
                output = "Failed to execute planner action '$action': ${e.message}",
                isError = true
            )
        }
    }

    /** Tool definitions exposed to the agent schema. */
    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "planner_tool",
            description = "Set an alarm or create a calendar event.",
            parameters = listOf(
                ToolParameter(
                    name = "action",
                    type = "string",
                    description = "Planner action: 'alarm' or 'calendar'.",
                    required = true
                ),
                ToolParameter(
                    name = "title",
                    type = "string",
                    description = "Label for the alarm or title of the calendar event.",
                    required = true
                ),
                ToolParameter(
                    name = "timeMillis",
                    type = "string",
                    description = "Target time as epoch milliseconds (string-encoded long).",
                    required = true
                )
            )
        )
    )
}

/**
 * Toggles hardware/connectivity settings via Shizuku shell commands.
 *
 * Supported settings: Wi-Fi, Bluetooth, mobile data, airplane mode, DND.
 */
object HardwareToggleTool {

    /**
     * Toggles the given hardware [setting] to [state].
     *
     * @param setting One of `"wifi"`, `"bluetooth"`, `"data"`, `"airplane"`, `"dnd"`.
     * @param state   `"true"` to enable, `"false"` to disable.
     * @return [ToolExecutionResult] with the command output or an error.
     */
    suspend fun execute(setting: String, state: String): ToolExecutionResult =
        withContext(Dispatchers.IO) {
            val enabled = state.toBooleanStrictOrNull()
                ?: return@withContext ToolExecutionResult(
                    output = "Invalid state '$state'. Use 'true' or 'false'.",
                    isError = true
                )

            when (setting.lowercase()) {
                "wifi" -> executeShizuku("svc wifi ${if (enabled) "enable" else "disable"}")
                "bluetooth" -> executeShizuku(
                    "svc bluetooth ${if (enabled) "enable" else "disable"}"
                )
                "data" -> executeShizuku("svc data ${if (enabled) "enable" else "disable"}")
                "airplane" -> {
                    val value = if (enabled) "1" else "0"
                    executeShizuku("settings put global airplane_mode_on $value")
                }
                "dnd" -> ToolExecutionResult(
                    output = "DND cannot be toggled via shell. " +
                        "Use NotificationManager.setInterruptionFilter() with " +
                        "ACCESS_NOTIFICATION_POLICY permission instead."
                )
                else -> ToolExecutionResult(
                    output = "Unknown setting '$setting'. " +
                        "Supported: wifi, bluetooth, data, airplane, dnd.",
                    isError = true
                )
            }
        }

    private suspend fun executeShizuku(command: String): ToolExecutionResult {
        return when (val result = ShizukuCommandTool.execute(command)) {
            is ShizukuResult.Success -> ToolExecutionResult(output = "✅ ${result.output}")
            is ShizukuResult.Failure -> ToolExecutionResult(
                output = result.reason,
                isError = true
            )
            is ShizukuResult.PermissionRequired -> ToolExecutionResult(
                output = result.message,
                isError = true
            )
            is ShizukuResult.Unavailable -> ToolExecutionResult(
                output = result.message,
                isError = true
            )
        }
    }

    /** Tool definitions exposed to the agent schema. */
    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "hardware_toggle_tool",
            description = "Toggle device hardware/connectivity settings " +
                "(Wi-Fi, Bluetooth, mobile data, airplane mode, DND).",
            parameters = listOf(
                ToolParameter(
                    name = "setting",
                    type = "string",
                    description = "Setting to toggle: 'wifi', 'bluetooth', 'data', " +
                        "'airplane', or 'dnd'.",
                    required = true
                ),
                ToolParameter(
                    name = "state",
                    type = "string",
                    description = "'true' to enable or 'false' to disable.",
                    required = true
                )
            )
        )
    )
}

/**
 * Retrieves the device's last known location using [android.location.LocationManager].
 *
 * Requires `ACCESS_FINE_LOCATION` or `ACCESS_COARSE_LOCATION` permission.
 */
object LocationTool {

    /**
     * Returns the last known location (latitude, longitude, accuracy, provider).
     *
     * @param context Application context used for permission checks and system service access.
     * @return [ToolExecutionResult] with location data or an error.
     */
    @Suppress("MissingPermission")
    fun execute(context: Context): ToolExecutionResult {
        return runCatching {
            val hasFine = ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            val hasCoarse = ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasFine && !hasCoarse) {
                return ToolExecutionResult(
                    output = "Location permission not granted. " +
                        "Request ACCESS_FINE_LOCATION or ACCESS_COARSE_LOCATION first.",
                    isError = true
                )
            }

            val locationManager = context.getSystemService(Context.LOCATION_SERVICE)
                as android.location.LocationManager

            val providers = locationManager.getProviders(true)
            val location = providers.firstNotNullOfOrNull { provider ->
                locationManager.getLastKnownLocation(provider)
            }

            if (location == null) {
                ToolExecutionResult(
                    output = "No last-known location available from any provider.",
                    isError = true
                )
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
        }.getOrElse { e ->
            ToolExecutionResult(
                output = "Failed to retrieve location: ${e.message}",
                isError = true
            )
        }
    }

    /** Tool definitions exposed to the agent schema. */
    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "get_current_location",
            description = "Get the device's last known GPS location " +
                "(latitude, longitude, accuracy, provider).",
            parameters = emptyList()
        )
    )
}

/**
 * Reads device information: battery level, storage usage, network state,
 * and general build metadata.
 */
object DeviceInfoTool {

    /**
     * Gathers device information filtered by [infoType].
     *
     * @param context  Application context.
     * @param infoType One of `"battery"`, `"storage"`, `"network"`, or `"all"` (default).
     * @return [ToolExecutionResult] with the requested information.
     */
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
                    appendLine()
                    appendLine("── Battery ──")
                    appendLine(getBatteryInfo(context))
                    appendLine()
                    appendLine("── Storage ──")
                    appendLine(getStorageInfo())
                    appendLine()
                    appendLine("── Network ──")
                    append(getNetworkInfo(context))
                }
                else -> return ToolExecutionResult(
                    output = "Unknown infoType '$infoType'. " +
                        "Use 'battery', 'storage', 'network', or 'all'.",
                    isError = true
                )
            }
            ToolExecutionResult(output = output)
        }.getOrElse { e ->
            ToolExecutionResult(
                output = "Failed to read device info: ${e.message}",
                isError = true
            )
        }
    }

    private fun getBatteryInfo(context: Context): String {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = bm.isCharging
        return "Level: $level%  |  Charging: $charging"
    }

    private fun getStorageInfo(): String = buildString {
        val internal = StatFs(Environment.getDataDirectory().path)
        val intTotal = internal.totalBytes / (1024 * 1024)
        val intFree = internal.availableBytes / (1024 * 1024)
        appendLine("Internal: ${intFree} MB free / ${intTotal} MB total")

        // getExternalStorageDirectory is deprecated but intentionally used here for
        // top-level storage stats — scoped-storage APIs don't expose whole-device numbers.
        @Suppress("DEPRECATION")
        val extDir = Environment.getExternalStorageDirectory()
        if (extDir.exists()) {
            val external = StatFs(extDir.path)
            val extTotal = external.totalBytes / (1024 * 1024)
            val extFree = external.availableBytes / (1024 * 1024)
            append("External: ${extFree} MB free / ${extTotal} MB total")
        } else {
            append("External: not available")
        }
    }

    private fun getNetworkInfo(context: Context): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork
            ?: return "No active network."
        val caps = cm.getNetworkCapabilities(network)
            ?: return "Active network has no capabilities."
        return buildString {
            appendLine("Connected:   true")
            appendLine("Wi-Fi:       ${caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)}")
            appendLine("Cellular:    ${caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)}")
            append("VPN:         ${caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)}")
        }
    }

    /** Tool definitions exposed to the agent schema. */
    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "get_device_info",
            description = "Retrieve device information: battery, storage, network, or all.",
            parameters = listOf(
                ToolParameter(
                    name = "infoType",
                    type = "string",
                    description = "Category of info: 'battery', 'storage', 'network', or 'all'.",
                    required = false
                )
            )
        )
    )
}

/**
 * Lists installed apps, queries app details, and performs privileged package
 * operations (force-stop, clear cache) via [ShizukuCommandTool].
 */
object AppManagerTool {

    /**
     * Executes an app-management action.
     *
     * @param context     Application context.
     * @param action      One of `"list_installed"`, `"app_info"`, `"force_stop"`, `"clear_data"`.
     * @param packageName Required for `app_info`, `force_stop`, and `clear_cache`.
     * @return [ToolExecutionResult] with output or an error.
     */
    suspend fun execute(
        context: Context,
        action: String,
        packageName: String? = null
    ): ToolExecutionResult = withContext(Dispatchers.IO) {
        runCatching {
            when (action.lowercase()) {
                "list_installed" -> listInstalled(context)
                "app_info" -> {
                    requirePackage(packageName)
                        ?: getAppInfo(context, packageName!!)
                }
                "force_stop" -> {
                    requirePackage(packageName)
                        ?: forceStop(packageName!!)
                }
                "clear_data" -> {
                    requirePackage(packageName)
                        ?: clearData(packageName!!)
                }
                else -> ToolExecutionResult(
                    output = "Unknown action '$action'. " +
                        "Use list_installed, app_info, force_stop, or clear_data.",
                    isError = true
                )
            }
        }.getOrElse { e ->
            ToolExecutionResult(
                output = "App manager error: ${e.message}",
                isError = true
            )
        }
    }

    /** Returns an error result when [packageName] is missing, or null if present. */
    private fun requirePackage(packageName: String?): ToolExecutionResult? {
        if (packageName.isNullOrBlank()) {
            return ToolExecutionResult(
                output = "packageName is required for this action.",
                isError = true
            )
        }
        return null
    }

    private fun listInstalled(context: Context): ToolExecutionResult {
        val pm = context.packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .sortedBy { pm.getApplicationLabel(it).toString().lowercase() }

        val output = buildString {
            appendLine("Installed applications (${apps.size}):")
            appendLine()
            for (app in apps) {
                val label = pm.getApplicationLabel(app)
                val isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                val tag = if (isSystem) " [system]" else ""
                appendLine("• $label  (${app.packageName})$tag")
            }
        }.trim()
        return ToolExecutionResult(output = output)
    }

    @Suppress("DEPRECATION")
    private fun getAppInfo(context: Context, packageName: String): ToolExecutionResult {
        val pm = context.packageManager
        val appInfo = pm.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
        val pkgInfo = pm.getPackageInfo(packageName, 0)
        val label = pm.getApplicationLabel(appInfo)
        val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pkgInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            pkgInfo.versionCode.toLong()
        }

        val output = buildString {
            appendLine("App:        $label")
            appendLine("Package:    $packageName")
            appendLine("Version:    ${pkgInfo.versionName} (code $versionCode)")
            appendLine("System app: $isSystem")
            appendLine("Enabled:    ${appInfo.enabled}")
            appendLine("Source:     ${appInfo.sourceDir}")
            if (appInfo.dataDir != null) {
                append("Data dir:   ${appInfo.dataDir}")
            }
        }.trim()
        return ToolExecutionResult(output = output)
    }

    private suspend fun forceStop(packageName: String): ToolExecutionResult {
        return when (val result = ShizukuCommandTool.execute("am force-stop $packageName")) {
            is ShizukuResult.Success -> ToolExecutionResult(
                output = "✅ Force-stopped $packageName."
            )
            is ShizukuResult.Failure -> ToolExecutionResult(
                output = result.reason,
                isError = true
            )
            is ShizukuResult.PermissionRequired -> ToolExecutionResult(
                output = result.message,
                isError = true
            )
            is ShizukuResult.Unavailable -> ToolExecutionResult(
                output = result.message,
                isError = true
            )
        }
    }

    private suspend fun clearData(packageName: String): ToolExecutionResult {
        return when (val result = ShizukuCommandTool.execute("pm clear $packageName")) {
            is ShizukuResult.Success -> ToolExecutionResult(
                output = "✅ Cleared all data for $packageName."
            )
            is ShizukuResult.Failure -> ToolExecutionResult(
                output = result.reason,
                isError = true
            )
            is ShizukuResult.PermissionRequired -> ToolExecutionResult(
                output = result.message,
                isError = true
            )
            is ShizukuResult.Unavailable -> ToolExecutionResult(
                output = result.message,
                isError = true
            )
        }
    }

    /** Tool definitions exposed to the agent schema. */
    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "app_manager_tool",
            description = "Manage installed applications: list them, get details, " +
                "force-stop, or clear all app data.",
            parameters = listOf(
                ToolParameter(
                    name = "action",
                    type = "string",
                    description = "Action to perform: 'list_installed', 'app_info', " +
                        "'force_stop', or 'clear_data'.",
                    required = true
                ),
                ToolParameter(
                    name = "packageName",
                    type = "string",
                    description = "Target package name (required for app_info, " +
                        "force_stop, clear_data).",
                    required = false
                )
            )
        )
    )
}
