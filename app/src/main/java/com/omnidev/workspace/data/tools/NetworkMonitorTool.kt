package com.omnidev.workspace.data.tools

import android.content.Context
import android.net.VpnService
import com.omnidev.workspace.data.network.OmniDevVpnService
import com.omnidev.workspace.data.network.DnsMonitorPacket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** DNS-only monitor and best-effort domain filtering; not a full traffic capture. */
object NetworkMonitorTool {

    // ──────────────────────────────────────────────────────────────────────
    // Modern Ad/Tracker/Telemetry Domains (Updated for 2026)
    // ──────────────────────────────────────────────────────────────────────
    private val AD_TRACKER_DOMAINS = listOf(
        // Google & DoubleClick
        "doubleclick.net", "googleadservices.com", "googlesyndication.com",
        "googletagmanager.com", "google-analytics.com", "adservice.google.com",
        // Meta / Facebook / Instagram
        "graph.facebook.com", "connect.facebook.net", "an.facebook.com", "pixel.facebook.com",
        // Bytedance / TikTok
        "p16-tiktokcdn-com.akamaized.net", "log.byteoversea.com", "mon.zijieapi.com",
        // Advertising & Analytics
        "ads.twitter.com", "ads.linkedin.com", "adsystem.amazon.com",
        "adnxs.com", "appnexus.com", "criteo.com", "taboola.com", "outbrain.com",
        "amplitude.com", "mixpanel.com", "appsflyer.com", "adjust.com", "branch.io",
        // Telemetry
        "telemetry.sdk.unity3d.com", "firebase.google.com", "crashlytics.com",
        "metrics.icloud.com", "tracking.miui.com"
    )

    private val TS_FMT = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    // ──────────────────────────────────────────────────────────────────────
    // Tool Definitions
    // ──────────────────────────────────────────────────────────────────────

    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "network_monitor",
            description = """
Monitor plaintext IPv4 UDP DNS using a local VPN. Ordinary TCP/UDP, IPv6, Private DNS and DoH are not monitored. DNS requests use the existing network resolver; never a public fallback. Unsupported traffic or resolver failures stop the monitor to restore normal connectivity.

Actions:
• start              — Start monitoring (Requires user VPN consent).
• stop               — Stop monitoring and close tunnel.
• status             — Get current state, DNS-only byte counts, last error, and block rules.
• get_traffic_log    — Get recent events. Params: limit (default 50), filter_type (DNS|BLOCKED|ERROR), filter_host (substring).
• get_app_stats      — Get top hosts by traffic. Params: top_n (default 20).
• block_domain       — Add domain to block list (e.g., "doubleclick.net").
• unblock_domain     — Remove domain from block list.
• get_blocked_domains— List all blocked rules.
• clear_log          — Reset stats and clear log.
• block_ads          — Instantly block ${AD_TRACKER_DOMAINS.size} known tracker domains.
""".trimIndent(),
            parameters = listOf(
                ToolParameter("action", "string", "Action to perform.", required = true),
                ToolParameter("domain", "string", "DNS domain for best-effort blocking (not IP).", required = false),
                ToolParameter("limit", "string", "Max log entries (default 50).", required = false),
                ToolParameter("filter_type", "string", "DNS|TCP|UDP|BLOCKED|ALL.", required = false),
                ToolParameter("filter_host", "string", "Host substring filter.", required = false),
                ToolParameter("top_n", "string", "Number of top hosts for stats.", required = false),
            )
        )
    )

    // ──────────────────────────────────────────────────────────────────────
    // Execution Router
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
                else -> ToolExecutionResult("Unknown action '$action'.", isError = true)
            }
        }

    // ──────────────────────────────────────────────────────────────────────
    // Action Implementations
    // ──────────────────────────────────────────────────────────────────────

    private fun doStart(context: Context): ToolExecutionResult {
        if (OmniDevVpnService.isRunning) {
            return ToolExecutionResult("✅ Network Monitor is already active.")
        }

        // Check for VPN permission
        val intent = VpnService.prepare(context)
        if (intent != null) {
            return ToolExecutionResult(
                "⚠️ VPN Permission Required.\n\n" +
                "Android requires explicit user consent to start a VPN. Please ask the user to " +
                "accept the system dialog on their screen, or enable it via Security settings.",
                isError = true
            )
        }

        OmniDevVpnService.startVpn(context)
        return ToolExecutionResult("DNS monitor start requested. Check status for confirmed activation; only plaintext UDP DNS is inspected.")
    }

    private fun doStop(context: Context): ToolExecutionResult {
        OmniDevVpnService.stopVpn(context)
        return ToolExecutionResult("DNS monitor stop requested. Check status to confirm shutdown.")
    }

    private fun doStatus(): ToolExecutionResult {
        val running   = OmniDevVpnService.isRunning
        // Use Locale.US to ensure LLM-friendly decimal points
        val outMB     = "%.2f MB".format(Locale.US, OmniDevVpnService.totalBytesOut.get() / 1_048_576.0)
        val inMB      = "%.2f MB".format(Locale.US, OmniDevVpnService.totalBytesIn.get()  / 1_048_576.0)
        val blocked   = synchronized(OmniDevVpnService.trafficLog) { OmniDevVpnService.trafficLog.count { it.type == "BLOCKED" } }

        return ToolExecutionResult(buildString {
            appendLine("🔒 Network Monitor Status")
            appendLine("────────────────────────")
            appendLine("State        : ${if (running) "✅ RUNNING" else "🔴 STOPPED"}")
            appendLine("Mode         : ${OmniDevVpnService.MODE}")
            appendLine("DNS Out      : $outMB")
            appendLine("DNS In       : $inMB")
            OmniDevVpnService.lastError?.let { appendLine("Last error   : $it") }
            appendLine("Coverage     : No IPv6, TCP, direct-IP, Private DNS or DoH inspection")
            appendLine("Log Size     : ${OmniDevVpnService.trafficLog.size} entries")
            appendLine("Blocked Pkts : $blocked")
            appendLine("Active Rules : ${OmniDevVpnService.blockedDomains.size} domains")
        }.trimEnd())
    }

    private fun doGetTrafficLog(args: Map<String, String>): ToolExecutionResult {
        val limit      = (args["limit"]?.toIntOrNull() ?: 50).coerceIn(1, 200)
        val typeFilter = args["filter_type"]?.uppercase()?.trim() ?: "ALL"
        val hostFilter = args["filter_host"]?.lowercase()?.trim()

        // Optimization: Filter using sequences to avoid massive list copying
        val filtered = synchronized(OmniDevVpnService.trafficLog) { OmniDevVpnService.trafficLog.toList() }.asSequence()
            .filter { e ->
                (typeFilter == "ALL" || e.type.equals(typeFilter, ignoreCase = true)) &&
                (hostFilter == null || e.host.lowercase().contains(hostFilter))
            }
            .toList()
            .takeLast(limit)

        if (filtered.isEmpty()) {
            return ToolExecutionResult("No traffic log entries found matching your criteria.")
        }

        return ToolExecutionResult(buildString {
            appendLine("📡 Traffic Log (${filtered.size} entries)")
            appendLine("─────────────────────────────────────────────────")
            filtered.forEach { e ->
                val ts    = TS_FMT.format(Date(e.timestamp))
                val bytes = formatBytes(e.bytes.toLong())
                val icon  = when (e.type) {
                    "DNS"     -> "🔍"
                    "BLOCKED" -> "🚫"
                    "TCP"     -> "🌐"
                    "UDP"     -> "📦"
                    else      -> "•"
                }
                appendLine("$icon [$ts] ${e.type.padEnd(7)} ${bytes.padStart(8)}  ${e.host}")
            }
        }.trimEnd())
    }

    private fun doGetAppStats(args: Map<String, String>): ToolExecutionResult {
        val topN = (args["top_n"]?.toIntOrNull() ?: 20).coerceIn(1, 100)

        val stats = synchronized(OmniDevVpnService.hostByteStats) { OmniDevVpnService.hostByteStats.values.map { it.copy() } }.asSequence()
            .sortedByDescending { it.bytesOut }
            .take(topN)
            .toList()

        if (stats.isEmpty()) {
            return ToolExecutionResult("No traffic statistics collected yet.")
        }

        return ToolExecutionResult(buildString {
            appendLine("📊 Top $topN Hosts by Outbound DNS")
            appendLine("─────────────────────────────────────────────")
            stats.forEachIndexed { i, s ->
                val out = formatBytes(s.bytesOut)
                val isBlocked = synchronized(OmniDevVpnService.blockedDomains) { OmniDevVpnService.blockedDomains.any { d ->
                    s.host.lowercase() == d || s.host.lowercase().endsWith(".$d")
                } }
                val icon = if (isBlocked) "🚫" else "  "
                appendLine("${(i + 1).toString().padStart(3)}. $icon ${out.padStart(10)}  ${s.host}")
            }
        }.trimEnd())
    }

    private fun doBlockDomain(args: Map<String, String>): ToolExecutionResult {
        val domain = args["domain"]?.let(DnsMonitorPacket::normalizeDomain)
            ?: return ToolExecutionResult("Expected a valid DNS domain (not an IP address).", isError = true)

        if (domain.isBlank()) return ToolExecutionResult("Domain cannot be empty.", isError = true)

        OmniDevVpnService.addBlockedDomain(domain)
        return ToolExecutionResult("🚫 Domain '$domain' (and its sub-domains) added to DNS block list. Only observed plaintext DNS is filtered.")
    }

    private fun doUnblockDomain(args: Map<String, String>): ToolExecutionResult {
        val domain = args["domain"]?.lowercase()?.trim()
            ?: return ToolExecutionResult("Missing 'domain' parameter.", isError = true)

        val removed = OmniDevVpnService.blockedDomains.remove(domain)
        return if (removed) {
            ToolExecutionResult("✅ Domain '$domain' removed from block list.")
        } else {
            ToolExecutionResult("ℹ️ Domain '$domain' was not blocked.")
        }
    }

    private fun doGetBlockedDomains(): ToolExecutionResult {
        val domains = synchronized(OmniDevVpnService.blockedDomains) { OmniDevVpnService.blockedDomains.toList().sorted() }
        if (domains.isEmpty()) return ToolExecutionResult("Block list is currently empty.")
        
        return ToolExecutionResult(buildString {
            appendLine("🚫 Blocked Domains (${domains.size} rules)")
            appendLine("─────────────────────────────────────────────")
            domains.forEach { appendLine("  • $it") }
        }.trimEnd())
    }

    private fun doClearLog(): ToolExecutionResult {
        OmniDevVpnService.clearLog()
        return ToolExecutionResult("✅ Traffic log and byte statistics have been reset.")
    }

    private fun doBlockAds(): ToolExecutionResult {
        var added = 0
        AD_TRACKER_DOMAINS.forEach { d ->
            if (OmniDevVpnService.blockedDomains.add(d)) added++
        }
        return ToolExecutionResult(
            "🚫 Ad-blocking list applied.\n" +
            "Added $added new domains to the block list. Total domains now blocked: ${OmniDevVpnService.blockedDomains.size}.\n" +
            "Includes: Google Ads, Meta/Facebook tracking, ByteDance, TikTok, and common analytics SDKs."
        )
    }

    // ──────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_048_576L -> "%.1f MB".format(Locale.US, bytes / 1_048_576.0)
        bytes >= 1_024L     -> "%.1f KB".format(Locale.US, bytes / 1_024.0)
        else                -> "$bytes B"
    }
}
