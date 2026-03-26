package com.omnidev.workspace.data.tools

import android.content.Context
import android.net.VpnService
import com.omnidev.workspace.data.network.OmniDevVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * NetworkMonitorTool — gives the AI agent full control over a local VPN-based traffic
 * inspector.  Uses [OmniDevVpnService] which intercepts all IP packets through Android's
 * [VpnService] API (no root required).
 *
 * ### Supported actions (`network_monitor` tool)
 * | Action              | Description                                                        |
 * |---------------------|--------------------------------------------------------------------|
 * | `start`             | Start the VPN tunnel and begin traffic monitoring                  |
 * | `stop`              | Stop the VPN tunnel and monitoring                                 |
 * | `status`            | Current status (running/stopped), total bytes, blocked count       |
 * | `get_traffic_log`   | Recent traffic events (host, type, bytes, timestamp)               |
 * | `get_app_stats`     | Per-host/app byte counters sorted by most traffic                  |
 * | `block_domain`      | Add a domain or IP to the block list (drops all matching packets)  |
 * | `unblock_domain`    | Remove a domain or IP from the block list                          |
 * | `get_blocked_domains` | List all currently blocked domains/IPs                           |
 * | `clear_log`         | Clear the traffic log and reset byte counters                      |
 * | `block_ads`         | Block a built-in list of common ad/tracker networks quickly        |
 */
object NetworkMonitorTool {

    // ──────────────────────────────────────────────────────────────────────
    // Built-in ad/tracker domains (a curated subset of common offenders)
    // ──────────────────────────────────────────────────────────────────────
    private val AD_TRACKER_DOMAINS = listOf(
        // Google Ads / DoubleClick
        "doubleclick.net", "googleadservices.com", "googlesyndication.com",
        "googletagmanager.com", "googletagservices.com", "google-analytics.com",
        "googleads.g.doubleclick.net", "adservice.google.com",
        // Facebook tracking
        "graph.facebook.com", "connect.facebook.net", "an.facebook.com",
        // Advertising networks
        "ads.twitter.com", "ads.linkedin.com", "adsystem.amazon.com",
        "advertising.com", "adtech.de", "adbrite.com",
        "adnxs.com", "appnexus.com",
        "rubiconproject.com", "openx.net",
        "moatads.com", "moat.com",
        "criteo.com", "criteo.net",
        "quantserve.com", "scorecardresearch.com",
        "adsrvr.org",
        // Telemetry / surveillance
        "analytics.yahoo.com", "pixel.facebook.com",
        "bat.bing.com", "c.bing.com",
        "cdn.taboola.com", "trc.taboola.com",
        "outbrain.com",
        // Common analytics SDKs
        "amplitude.com", "mixpanel.com", "segment.com", "segment.io",
        "appsflyer.com", "adjust.com", "branch.io",
        "firebase.google.com",
    )

    private val TS_FMT = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    // ──────────────────────────────────────────────────────────────────────
    // Tool definitions
    // ──────────────────────────────────────────────────────────────────────

    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "network_monitor",
            description = """
Monitor and control all network traffic leaving the device using a local VPN tunnel.
No data is sent to any external server — inspection is 100% on-device.

Actions and parameters:
• start              — Start VPN-based monitoring. The user must grant VPN permission
                       on the first run (Android will show a system dialog automatically).
• stop               — Stop monitoring and close the VPN tunnel.
• status             — Running state, total bytes captured, number of blocked packets.
• get_traffic_log    — Recent traffic events. Optional: limit (default 50, max 200),
                       filter_type (DNS|TCP|UDP|BLOCKED|ALL, default ALL),
                       filter_host (substring match, e.g. "facebook").
• get_app_stats      — Per-host byte totals, sorted by most traffic.
                       Optional: top_n (default 20).
• block_domain       — domain: hostname or IP to block (e.g. "doubleclick.net").
                       All sub-domains are blocked automatically.
• unblock_domain     — domain: remove from block list.
• get_blocked_domains— List all currently blocked domains/IPs.
• clear_log          — Reset traffic log and byte counters.
• block_ads          — Instantly block ${AD_TRACKER_DOMAINS.size} known ad/tracker/analytics domains.

Note: To intercept traffic the first time, Android will show a VPN connection dialog
that the user must accept.  Subsequent starts do not require this dialog.
""".trimIndent(),
            parameters = listOf(
                ToolParameter("action", "string", "Action to perform (see above).", required = true),
                ToolParameter("domain", "string", "Domain or IP for block_domain / unblock_domain.", required = false),
                ToolParameter("limit", "string", "Max log entries for get_traffic_log (default 50).", required = false),
                ToolParameter("filter_type", "string", "Entry type filter: DNS|TCP|UDP|BLOCKED|ALL.", required = false),
                ToolParameter("filter_host", "string", "Hostname substring filter for get_traffic_log.", required = false),
                ToolParameter("top_n", "string", "Number of top hosts for get_app_stats (default 20).", required = false),
            )
        )
    )

    // ──────────────────────────────────────────────────────────────────────
    // Execution
    // ──────────────────────────────────────────────────────────────────────

    suspend fun execute(context: Context, action: String, args: Map<String, String>): ToolExecutionResult =
        withContext(Dispatchers.Main) {
            when (action.lowercase().trim()) {
                "start"               -> doStart(context)
                "stop"                -> doStop(context)
                "status"              -> doStatus()
                "get_traffic_log"     -> doGetTrafficLog(args)
                "get_app_stats"       -> doGetAppStats(args)
                "block_domain"        -> doBlockDomain(args)
                "unblock_domain"      -> doUnblockDomain(args)
                "get_blocked_domains" -> doGetBlockedDomains()
                "clear_log"           -> doClearLog()
                "block_ads"           -> doBlockAds()
                else -> ToolExecutionResult(
                    "Unknown action '$action'. Valid actions: start, stop, status, get_traffic_log, " +
                    "get_app_stats, block_domain, unblock_domain, get_blocked_domains, clear_log, block_ads.",
                    isError = true
                )
            }
        }

    // ──────────────────────────────────────────────────────────────────────
    // Action implementations
    // ──────────────────────────────────────────────────────────────────────

    private fun doStart(context: Context): ToolExecutionResult {
        if (OmniDevVpnService.isRunning) {
            return ToolExecutionResult("✅ Network Monitor is already running.")
        }

        // Check if VPN permission has been granted.  If not, the user needs to accept
        // the system VPN consent dialog from the UI — the tool cannot do this silently.
        val intent = VpnService.prepare(context)
        if (intent != null) {
            // Intent != null means permission is NOT yet granted.
            return ToolExecutionResult(
                "⚠️ VPN permission not yet granted.\n\n" +
                "The user must accept the VPN connection dialog before the monitor can start.\n" +
                "Please ask the user to go to Settings → Network → VPN → OmniDev and grant permission, " +
                "or tap 'Start VPN Monitor' in the app's Security panel.\n\n" +
                "After granting permission, call network_monitor(action=start) again.",
                isError = true
            )
        }

        OmniDevVpnService.startVpn(context)
        return ToolExecutionResult(
            "✅ Network Monitor started.\n" +
            "The VPN tunnel is now intercepting all outgoing traffic.\n" +
            "Use get_traffic_log to see captured connections, block_ads to block trackers."
        )
    }

    private fun doStop(context: Context): ToolExecutionResult {
        OmniDevVpnService.stopVpn(context)
        return ToolExecutionResult("🔴 Network Monitor stopped. VPN tunnel closed.")
    }

    private fun doStatus(): ToolExecutionResult {
        val running   = OmniDevVpnService.isRunning
        val outMB     = "%.2f MB".format(OmniDevVpnService.totalBytesOut.get() / 1_048_576.0)
        val inMB      = "%.2f MB".format(OmniDevVpnService.totalBytesIn.get()  / 1_048_576.0)
        val logSize   = OmniDevVpnService.trafficLog.size
        val blocked   = OmniDevVpnService.trafficLog.count { it.type == "BLOCKED" }
        val domains   = OmniDevVpnService.blockedDomains.size

        val sb = StringBuilder()
        sb.appendLine("🔒 Network Monitor Status")
        sb.appendLine("────────────────────────")
        sb.appendLine("State        : ${if (running) "✅ RUNNING" else "🔴 STOPPED"}")
        sb.appendLine("Traffic out  : $outMB")
        sb.appendLine("Traffic in   : $inMB")
        sb.appendLine("Log entries  : $logSize")
        sb.appendLine("Blocked pkts : $blocked")
        sb.appendLine("Block rules  : $domains domains/IPs")
        return ToolExecutionResult(sb.toString().trimEnd())
    }

    private fun doGetTrafficLog(args: Map<String, String>): ToolExecutionResult {
        val limit      = (args["limit"]?.toIntOrNull() ?: 50).coerceIn(1, 200)
        val typeFilter = args["filter_type"]?.uppercase()?.trim() ?: "ALL"
        val hostFilter = args["filter_host"]?.lowercase()?.trim()

        val entries = OmniDevVpnService.trafficLog.toList()

        val filtered = entries
            .filter { e ->
                (typeFilter == "ALL" || e.type.equals(typeFilter, ignoreCase = true)) &&
                (hostFilter == null || e.host.lowercase().contains(hostFilter))
            }
            .takeLast(limit)

        if (filtered.isEmpty()) {
            return ToolExecutionResult("No traffic log entries match the filter (${entries.size} total).")
        }

        val sb = StringBuilder()
        sb.appendLine("📡 Traffic Log (${filtered.size} entries, filter=${typeFilter}${if (hostFilter != null) ", host=$hostFilter" else ""})")
        sb.appendLine("─────────────────────────────────────────────────")
        filtered.forEach { e ->
            val ts    = TS_FMT.format(Date(e.timestamp))
            val bytes = formatBytes(e.bytes.toLong())
            val icon  = when (e.type) {
                "DNS"     -> "🔍"
                "BLOCKED" -> "🚫"
                "TCP"     -> "🌐"
                "UDP"     -> "📦"
                "ERROR"   -> "❌"
                else      -> "•"
            }
            sb.appendLine("$icon [$ts] ${e.type.padEnd(7)} ${bytes.padStart(8)}  ${e.host}")
        }
        return ToolExecutionResult(sb.toString().trimEnd())
    }

    private fun doGetAppStats(args: Map<String, String>): ToolExecutionResult {
        val topN = (args["top_n"]?.toIntOrNull() ?: 20).coerceIn(1, 100)

        val stats = OmniDevVpnService.hostByteStats.values.toList()
            .sortedByDescending { it.bytesOut }
            .take(topN)

        if (stats.isEmpty()) {
            return ToolExecutionResult("No traffic stats collected yet. Start monitoring first.")
        }

        val sb = StringBuilder()
        sb.appendLine("📊 Top $topN Hosts by Outbound Traffic")
        sb.appendLine("─────────────────────────────────────────────")
        stats.forEachIndexed { i, s ->
            val out = formatBytes(s.bytesOut)
            val blocked = OmniDevVpnService.blockedDomains.any { d ->
                s.host.lowercase() == d || s.host.lowercase().endsWith(".$d")
            }
            val icon = if (blocked) "🚫" else "  "
            sb.appendLine("${(i + 1).toString().padStart(3)}. $icon ${out.padStart(10)}  ${s.host}")
        }
        return ToolExecutionResult(sb.toString().trimEnd())
    }

    private fun doBlockDomain(args: Map<String, String>): ToolExecutionResult {
        val domain = args["domain"]?.lowercase()?.trim()
            ?: return ToolExecutionResult("Missing required parameter: domain", isError = true)

        if (domain.isBlank()) {
            return ToolExecutionResult("Domain cannot be blank.", isError = true)
        }

        OmniDevVpnService.addBlockedDomain(domain)
        return ToolExecutionResult("🚫 '$domain' added to block list. All sub-domains will be blocked too.")
    }

    private fun doUnblockDomain(args: Map<String, String>): ToolExecutionResult {
        val domain = args["domain"]?.lowercase()?.trim()
            ?: return ToolExecutionResult("Missing required parameter: domain", isError = true)

        val removed = OmniDevVpnService.blockedDomains.remove(domain)
        return if (removed) {
            ToolExecutionResult("✅ '$domain' removed from block list.")
        } else {
            ToolExecutionResult("ℹ️ '$domain' was not in the block list.", isError = false)
        }
    }

    private fun doGetBlockedDomains(): ToolExecutionResult {
        val domains = OmniDevVpnService.blockedDomains.toList().sorted()
        if (domains.isEmpty()) {
            return ToolExecutionResult("Block list is empty. Use block_domain or block_ads to add entries.")
        }
        val sb = StringBuilder()
        sb.appendLine("🚫 Blocked Domains/IPs (${domains.size} rules)")
        sb.appendLine("─────────────────────────────────────────────")
        domains.forEach { sb.appendLine("  • $it") }
        return ToolExecutionResult(sb.toString().trimEnd())
    }

    private fun doClearLog(): ToolExecutionResult {
        OmniDevVpnService.clearLog()
        return ToolExecutionResult("✅ Traffic log cleared and byte counters reset.")
    }

    private fun doBlockAds(): ToolExecutionResult {
        var added = 0
        AD_TRACKER_DOMAINS.forEach { d ->
            if (OmniDevVpnService.blockedDomains.add(d)) added++
        }
        return ToolExecutionResult(
            "🚫 Ad/tracker block list applied.\n" +
            "Added $added new rules (${AD_TRACKER_DOMAINS.size} total ad/tracker domains now blocked).\n" +
            "Blocked networks include: Google Ads, Facebook tracking, Criteo, Taboola, Outbrain, " +
            "Amplitude, Mixpanel, AppsFlyer, Adjust, Branch, and more.\n\n" +
            "Note: HTTPS connections can only be blocked at the IP/DNS level — the app will see " +
            "a connection timeout. Unencrypted HTTP traffic is dropped immediately."
        )
    }

    // ──────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_048_576L -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1_024L     -> "%.1f KB".format(bytes / 1_024.0)
        else                -> "$bytes B"
    }
}
