package com.omnidev.workspace.data.tools

import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Captures and reads device notifications via [android.service.notification.NotificationListenerService].
 *
 * Notifications are stored in a bounded, thread-safe in-memory list and can be
 * queried by the ReAct agent through the `read_notifications` tool. The list is
 * capped at [MAX_CAPTURED] entries to prevent unbounded memory growth.
 */
object NotificationCaptureTool {

    private const val MAX_CAPTURED = 100

    private val capturedNotifications = CopyOnWriteArrayList<CapturedNotification>()

    /**
     * Lightweight snapshot of a single notification for agent consumption.
     */
    data class CapturedNotification(
        val packageName: String,
        val title: String?,
        val text: String?,
        val timestamp: Long,
        val category: String?
    )

    // ── Listener callbacks ───────────────────────────────────────────────

    /**
     * Called by the [NotificationListenerService] implementation when a new
     * notification is posted. Extracts relevant fields and stores the snapshot.
     */
    fun onNotificationPosted(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras
        val captured = CapturedNotification(
            packageName = sbn.packageName,
            title = extras?.getCharSequence(NotificationCompat.EXTRA_TITLE)?.toString(),
            text = extras?.getCharSequence(NotificationCompat.EXTRA_TEXT)?.toString(),
            timestamp = sbn.postTime,
            category = sbn.notification.category
        )
        capturedNotifications.add(captured)

        // Trim oldest entries when the list exceeds the cap.
        // Remove excess items in a single pass to avoid O(n²) on CopyOnWriteArrayList.
        val excess = capturedNotifications.size - MAX_CAPTURED
        if (excess > 0) {
            val toRemove = capturedNotifications.take(excess)
            capturedNotifications.removeAll(toRemove.toSet())
        }
    }

    /**
     * Called when a notification is removed. Currently a no-op; the captured
     * snapshot is retained so the agent can still reference dismissed messages.
     */
    @Suppress("UNUSED_PARAMETER")
    fun onNotificationRemoved(sbn: StatusBarNotification) {
        // Intentional no-op — captured notifications are kept for agent queries.
    }

    // ── Tool schema ──────────────────────────────────────────────────────

    /**
     * Returns the tool definitions exposed to the agent for notification reading.
     */
    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "read_notifications",
            description = "Read captured device notifications. Requires NotificationListenerService " +
                    "permission. Returns recent notifications with sender, content, and timestamp.",
            parameters = listOf(
                ToolParameter(
                    name = "packageFilter",
                    type = "string",
                    description = "Filter by package name (e.g., com.whatsapp)",
                    required = false
                ),
                ToolParameter(
                    name = "lastMinutes",
                    type = "string",
                    description = "Only show notifications from the last N minutes (default: 60)",
                    required = false
                ),
                ToolParameter(
                    name = "limit",
                    type = "string",
                    description = "Max number of notifications to return (default: 20)",
                    required = false
                )
            )
        )
    )

    // ── Execution ────────────────────────────────────────────────────────

    /**
     * Queries the in-memory notification list with optional filters.
     *
     * @param packageFilter If non-null, only notifications from this package are returned.
     * @param lastMinutes   Time window in minutes; notifications older than this are excluded.
     * @param limit         Maximum number of results to return.
     */
    suspend fun execute(
        packageFilter: String? = null,
        lastMinutes: Int = 60,
        limit: Int = 20
    ): ToolExecutionResult = withContext(Dispatchers.IO) {
        runCatching {
            val cutoff = System.currentTimeMillis() - (lastMinutes * 60_000L)
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

            val filtered = capturedNotifications
                .filter { it.timestamp >= cutoff }
                .let { list ->
                    if (packageFilter != null) list.filter { it.packageName == packageFilter }
                    else list
                }
                .sortedByDescending { it.timestamp }
                .take(limit)

            if (filtered.isEmpty()) {
                return@withContext ToolExecutionResult(
                    output = "No notifications found" +
                            (if (packageFilter != null) " for package '$packageFilter'" else "") +
                            " in the last $lastMinutes minute(s)."
                )
            }

            val sb = StringBuilder()
            sb.appendLine("📬 ${filtered.size} notification(s) found:\n")
            filtered.forEachIndexed { index, n ->
                sb.appendLine("─── ${index + 1} ───")
                sb.appendLine("Package:   ${n.packageName}")
                sb.appendLine("Title:     ${n.title ?: "(none)"}")
                sb.appendLine("Text:      ${n.text ?: "(none)"}")
                sb.appendLine("Time:      ${dateFormat.format(Date(n.timestamp))}")
                sb.appendLine("Category:  ${n.category ?: "(none)"}")
                sb.appendLine()
            }

            ToolExecutionResult(output = sb.toString().trimEnd())
        }.getOrElse { e ->
            ToolExecutionResult(
                output = "Error reading notifications: ${e.message}",
                isError = true
            )
        }
    }

    /**
     * Dispatches a tool call from the agent loop to the appropriate handler.
     */
    suspend fun executeTool(
        name: String,
        arguments: Map<String, String>
    ): ToolExecutionResult {
        if (name != "read_notifications") {
            return ToolExecutionResult(
                output = "Unknown tool: $name",
                isError = true
            )
        }
        val packageFilter = arguments["packageFilter"]?.takeIf { it.isNotBlank() }
        val lastMinutes = arguments["lastMinutes"]?.toIntOrNull() ?: 60
        val limit = arguments["limit"]?.toIntOrNull() ?: 20
        return execute(packageFilter, lastMinutes, limit)
    }
}
