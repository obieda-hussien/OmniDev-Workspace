package com.omnidev.workspace.data.tools

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.omnidev.workspace.MainActivity
import com.omnidev.workspace.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicInteger

/**
 * NotificationCaptureTool — Omni's bidirectional notification centre.
 *
 * It can both observe system notifications (with Notification Listener access)
 * and post/cancel OmniDev-owned notifications so a background agent can request
 * the user's attention without illegally starting an Activity from background.
 *
 * Security/privacy rules:
 * - Captured notification contents remain in-memory only.
 * - The tool never persists OTPs or notification text.
 * - Posting on Android 13+ requires POST_NOTIFICATIONS. We may try the existing
 *   Shizuku permission path, otherwise the user must grant the Android runtime
 *   permission normally.
 */
object NotificationCaptureTool {

    private const val MAX_CAPTURED = 150
    private const val DEFAULT_CHANNEL_ID = "omnidev_agent"
    private const val HANDOFF_CHANNEL_ID = "omnidev_handoff"
    private val nextNotificationId = AtomicInteger(43_000)

    @Volatile
    private var appContext: Context? = null

    private val capturedNotifications = ConcurrentLinkedDeque<CapturedNotification>()

    data class CapturedNotification(
        val appName: String,
        val packageName: String,
        val title: String?,
        val text: String?,
        val timestamp: Long,
        val category: String?
    )

    fun initialize(context: Context) {
        appContext = context.applicationContext
        createChannels(context.applicationContext)
    }

    // ── Listener callbacks ────────────────────────────────────────────────

    fun onNotificationPosted(context: Context, sbn: StatusBarNotification) {
        initialize(context)
        val extras = sbn.notification.extras
        val packageName = sbn.packageName

        val title = extras.getCharSequence(NotificationCompat.EXTRA_TITLE)?.toString()
        val text = extractFullText(extras)

        // Suppress the common "same notification updated repeatedly" pattern.
        val lastMatch = capturedNotifications.peekLast()
        if (lastMatch?.packageName == packageName && lastMatch.text == text && lastMatch.title == title) {
            return
        }

        val captured = CapturedNotification(
            appName = getAppName(context, packageName),
            packageName = packageName,
            title = title,
            text = text,
            timestamp = sbn.postTime,
            category = sbn.notification.category
        )
        capturedNotifications.add(captured)

        while (capturedNotifications.size > MAX_CAPTURED) {
            capturedNotifications.pollFirst()
        }
    }

    private fun extractFullText(extras: Bundle): String? {
        val bigText = extras.getCharSequence(NotificationCompat.EXTRA_BIG_TEXT)
        if (!bigText.isNullOrBlank()) return bigText.toString()

        val lines = extras.getCharSequenceArray(NotificationCompat.EXTRA_TEXT_LINES)
        if (!lines.isNullOrEmpty()) return lines.joinToString("\n")

        return extras.getCharSequence(NotificationCompat.EXTRA_TEXT)?.toString()
    }

    private fun getAppName(context: Context, packageName: String): String =
        runCatching {
            val pm = context.packageManager
            val ai = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(ai).toString()
        }.getOrDefault(packageName)

    // ── Tool definition ───────────────────────────────────────────────────

    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "read_notifications",
            description = """
Bidirectional Android notification centre.

operation=read (default): read recent captured device notifications/OTPs.
operation=post: post an OmniDev notification to the user. Use category=handoff when the autonomous agent requires human input such as a password, OTP, CAPTCHA, passkey, biometric step, account consent, or destructive confirmation. After posting a handoff notification, STOP automated interaction with that sensitive step until the user completes it.
operation=cancel: cancel one OmniDev-owned notification by notificationId.
operation=clear_own: cancel all OmniDev-owned notifications.

Never put passwords, authentication tokens, recovery codes, or other secrets in a posted notification body.
""".trimIndent(),
            parameters = listOf(
                ToolParameter("operation", "string", "read | post | cancel | clear_own (default: read)", false),
                ToolParameter("packageFilter", "string", "read: exact package name filter", false),
                ToolParameter("query", "string", "read: keyword search in title/text", false),
                ToolParameter("lastMinutes", "string", "read: time window in minutes (default 60)", false),
                ToolParameter("limit", "string", "read: maximum results (default 20)", false),
                ToolParameter("title", "string", "post: notification title", false),
                ToolParameter("text", "string", "post: notification body; never include secrets", false),
                ToolParameter("notificationId", "string", "post/cancel: optional integer id", false),
                ToolParameter("category", "string", "post: normal | progress | handoff (default normal)", false),
                ToolParameter("ongoing", "string", "post: true for persistent notification", false)
            )
        )
    )

    // ── Read execution ────────────────────────────────────────────────────

    suspend fun execute(
        packageFilter: String? = null,
        query: String? = null,
        lastMinutes: Int = 60,
        limit: Int = 20
    ): ToolExecutionResult = withContext(Dispatchers.IO) {
        val safeMinutes = lastMinutes.coerceIn(1, 7 * 24 * 60)
        val safeLimit = limit.coerceIn(1, 100)
        val cutoff = System.currentTimeMillis() - (safeMinutes * 60_000L)
        val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

        val filtered = capturedNotifications.asSequence()
            .filter { it.timestamp >= cutoff }
            .filter { n -> packageFilter == null || n.packageName == packageFilter }
            .filter { n ->
                query == null ||
                    (n.text?.contains(query, ignoreCase = true) == true) ||
                    (n.title?.contains(query, ignoreCase = true) == true)
            }
            .toList()
            .sortedByDescending { it.timestamp }
            .take(safeLimit)

        if (filtered.isEmpty()) {
            return@withContext ToolExecutionResult("No recent notifications found matching filters.")
        }

        val sb = StringBuilder()
        sb.appendLine("📬 Found ${filtered.size} recent notification(s):")
        filtered.forEachIndexed { index, n ->
            sb.appendLine("\n[${index + 1}] ${n.appName} (${n.packageName}) @ ${dateFormat.format(Date(n.timestamp))}")
            if (!n.title.isNullOrBlank()) sb.appendLine("   Title: ${n.title}")
            if (!n.text.isNullOrBlank()) sb.appendLine("   Text:  ${n.text}")
            if (!n.category.isNullOrBlank()) sb.appendLine("   Cat:   ${n.category}")
        }
        ToolExecutionResult(output = sb.toString().trimEnd())
    }

    suspend fun executeTool(name: String, arguments: Map<String, String>): ToolExecutionResult {
        if (name != "read_notifications") {
            return ToolExecutionResult("Unknown tool: $name", isError = true)
        }

        return when (arguments["operation"]?.trim()?.lowercase().orEmpty().ifBlank { "read" }) {
            "read" -> execute(
                packageFilter = arguments["packageFilter"]?.takeIf { it.isNotBlank() },
                query = arguments["query"]?.takeIf { it.isNotBlank() },
                lastMinutes = arguments["lastMinutes"]?.toIntOrNull() ?: 60,
                limit = arguments["limit"]?.toIntOrNull() ?: 20
            )
            "post" -> postNotification(
                title = arguments["title"].orEmpty().ifBlank { "OmniDev" },
                text = arguments["text"].orEmpty().ifBlank { "Omni needs your attention." },
                requestedId = arguments["notificationId"]?.toIntOrNull(),
                category = arguments["category"].orEmpty().ifBlank { "normal" },
                ongoing = arguments["ongoing"]?.toBooleanStrictOrNull() ?: false
            )
            "cancel" -> {
                val id = arguments["notificationId"]?.toIntOrNull()
                    ?: return ToolExecutionResult("notificationId is required for operation=cancel", isError = true)
                cancelNotification(id)
            }
            "clear_own" -> cancelAllOwnNotifications()
            else -> ToolExecutionResult(
                "Unknown notification operation. Use read, post, cancel, or clear_own.",
                isError = true
            )
        }
    }

    private suspend fun postNotification(
        title: String,
        text: String,
        requestedId: Int?,
        category: String,
        ongoing: Boolean
    ): ToolExecutionResult {
        val context = appContext
            ?: return ToolExecutionResult("Notification tool is not initialized yet. Open OmniDev once and retry.", isError = true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            // ADMIN/PRO builds with Shizuku may grant this without forcing a UI
            // round-trip. Normal builds safely fall back to Android's permission UI.
            val granted = runCatching {
                ensurePermissionViaShizuku(Manifest.permission.POST_NOTIFICATIONS, context.packageName, context)
            }.getOrDefault(false)
            if (!granted && ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return ToolExecutionResult(
                    "USER_ACTION_REQUIRED: Android POST_NOTIFICATIONS permission is not granted. " +
                        "Ask the user to grant notification permission in OmniDev settings, then retry.",
                    isError = true
                )
            }
        }

        createChannels(context)
        val isHandoff = category.equals("handoff", ignoreCase = true)
        val channelId = if (isHandoff) HANDOFF_CHANNEL_ID else DEFAULT_CHANNEL_ID
        val id = requestedId ?: nextNotificationId.getAndIncrement()

        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("browser_handoff_notification_id", id)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            id,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title.take(120))
            .setContentText(text.take(250))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text.take(2_000)))
            .setContentIntent(pendingIntent)
            .setAutoCancel(!ongoing)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(!isHandoff)
            .setPriority(if (isHandoff) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(if (isHandoff) NotificationCompat.CATEGORY_RECOMMENDATION else NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)

        if (isHandoff) {
            builder
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .addAction(0, "Open OmniDev", pendingIntent)
        }

        return runCatching {
            NotificationManagerCompat.from(context).notify(id, builder.build())
            ToolExecutionResult(
                "✅ Notification posted (id=$id, category=${if (isHandoff) "handoff" else "normal"})." +
                    if (isHandoff) " USER_ACTION_REQUIRED: stop the sensitive browser step until the user takes over." else ""
            )
        }.getOrElse { error ->
            ToolExecutionResult("Failed to post notification: ${error.message}", isError = true)
        }
    }

    private fun cancelNotification(id: Int): ToolExecutionResult {
        val context = appContext ?: return ToolExecutionResult("Notification tool is not initialized.", isError = true)
        NotificationManagerCompat.from(context).cancel(id)
        return ToolExecutionResult("✅ Notification $id cancelled.")
    }

    private fun cancelAllOwnNotifications(): ToolExecutionResult {
        val context = appContext ?: return ToolExecutionResult("Notification tool is not initialized.", isError = true)
        NotificationManagerCompat.from(context).cancelAll()
        return ToolExecutionResult("✅ All OmniDev-owned notifications cancelled.")
    }

    private fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        manager.createNotificationChannel(
            NotificationChannel(
                DEFAULT_CHANNEL_ID,
                "OmniDev Agent",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Agent progress and user-visible results"
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                HANDOFF_CHANNEL_ID,
                "OmniDev Human Handoff",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Urgent requests for password, OTP, CAPTCHA, passkey, consent or other human-only steps"
                enableVibration(true)
            }
        )
    }
}
