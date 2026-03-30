package com.omnidev.workspace.data.tools

import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * NotificationCaptureTool — The Agent's ear for system-wide notifications.
 *
 * This version includes:
 * 1. Deep Extraction: Captures BigText and multiple lines.
 * 2. Smart Deduplication: Prevents spam from progress bars or media players.
 * 3. App Name Resolution: Converts package IDs to human-readable names.
 * 4. Advanced Search: Allows filtering by keywords (e.g., finding OTPs).
 */
object NotificationCaptureTool {

    private const val MAX_CAPTURED = 150
    
    // Using a ConcurrentLinkedDeque for efficient O(1) removal of oldest entries
    private val capturedNotifications = ConcurrentLinkedDeque<CapturedNotification>()

    data class CapturedNotification(
        val appName: String,
        val packageName: String,
        val title: String?,
        val text: String?,
        val timestamp: Long,
        val category: String?
    )

    // ── Listener Callbacks ───────────────────────────────────────────────

    /**
     * Called when a new notification is posted. Handles metadata extraction 
     * and applies deduplication logic.
     */
    fun onNotificationPosted(context: Context, sbn: StatusBarNotification) {
        val extras = sbn.notification.extras
        val packageName = sbn.packageName
        
        val title = extras.getCharSequence(NotificationCompat.EXTRA_TITLE)?.toString()
        val text = extractFullText(extras)
        
        // Smart Deduplication: If the text and title match the last one from this app, skip it.
        // This prevents the log from filling up with progress bar updates or media playback pulses.
        val lastMatch = capturedNotifications.peekLast()
        if (lastMatch?.packageName == packageName && lastMatch.text == text && lastMatch.title == title) {
            return
        }

        val appName = getAppName(context, packageName)
        
        val captured = CapturedNotification(
            appName = appName,
            packageName = packageName,
            title = title,
            text = text,
            timestamp = sbn.postTime,
            category = sbn.notification.category
        )

        capturedNotifications.add(captured)

        // Maintain bounded size
        while (capturedNotifications.size > MAX_CAPTURED) {
            capturedNotifications.pollFirst()
        }
    }

    private fun extractFullText(extras: Bundle): String? {
        // Try BigText first (long messages)
        val bigText = extras.getCharSequence(NotificationCompat.EXTRA_BIG_TEXT)
        if (!bigText.isNullOrBlank()) return bigText.toString()

        // Try TextLines (Inbox style like Gmail)
        val lines = extras.getCharSequenceArray(NotificationCompat.EXTRA_TEXT_LINES)
        if (!lines.isNullOrEmpty()) return lines.joinToString("\n")

        // Fallback to standard text
        return extras.getCharSequence(NotificationCompat.EXTRA_TEXT)?.toString()
    }

    private fun getAppName(context: Context, packageName: String): String {
        return try {
            val pm = context.packageManager
            val ai = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(ai).toString()
        } catch (e: Exception) {
            packageName
        }
    }

    // ── Tool Definition ──────────────────────────────────────────────────

    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "read_notifications",
            description = "Read recently captured device notifications. Essential for observing background events, " +
                    "capturing verification codes (OTP), or monitoring app alerts. Returns sender app, content, and time.",
            parameters = listOf(
                ToolParameter("packageFilter", "string", "Filter by package name (e.g., 'com.whatsapp').", false),
                ToolParameter("query", "string", "Keyword search within notification title or text (e.g., 'code').", false),
                ToolParameter("lastMinutes", "string", "Time window in minutes (default: 60).", false),
                ToolParameter("limit", "string", "Max results to return (default: 20).", false)
            )
        )
    )

    // ── Execution Logic ──────────────────────────────────────────────────

    suspend fun execute(
        packageFilter: String? = null,
        query: String? = null,
        lastMinutes: Int = 60,
        limit: Int = 20
    ): ToolExecutionResult = withContext(Dispatchers.IO) {
        val cutoff = System.currentTimeMillis() - (lastMinutes * 60_000L)
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
            .take(limit)

        if (filtered.isEmpty()) {
            return@withContext ToolExecutionResult(
                output = "No recent notifications found matching filters."
            )
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

    /**
     * Dispatcher for the CompositeToolManager routing.
     */
    suspend fun executeTool(name: String, arguments: Map<String, String>): ToolExecutionResult {
        if (name != "read_notifications") {
            return ToolExecutionResult("Unknown tool: $name", isError = true)
        }
        
        return execute(
            packageFilter = arguments["packageFilter"]?.takeIf { it.isNotBlank() },
            query = arguments["query"]?.takeIf { it.isNotBlank() },
            lastMinutes = arguments["lastMinutes"]?.toIntOrNull() ?: 60,
            limit = arguments["limit"]?.toIntOrNull() ?: 20
        )
    }
}
