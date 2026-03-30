package com.omnidev.workspace.data.tools

import android.content.Context
import com.omnidev.workspace.data.network.OmniDevVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * VPNControlTool — Provides the agent with full control over the local VPN tunnel,
 * allowing it to start/stop the monitor, manage blocklists, and analyze live traffic.
 */
object VPNControlTool {

    suspend fun execute(context: Context, action: String, arguments: Map<String, String>): ToolExecutionResult = withContext(Dispatchers.IO) {
        when (action.lowercase()) {
            "start" -> {
                if (OmniDevVpnService.isRunning) return@withContext ToolExecutionResult("VPN is already running.")
                OmniDevVpnService.startVpn(context)
                ToolExecutionResult("✅ VPN start command issued. Check 'status' in a moment.")
            }
            "stop" -> {
                if (!OmniDevVpnService.isRunning) return@withContext ToolExecutionResult("VPN is not running.")
                OmniDevVpnService.stopVpn(context)
                ToolExecutionResult("✅ VPN stop command issued.")
            }
            "status" -> {
                val status = JSONObject().apply {
                    put("isRunning", OmniDevVpnService.isRunning)
                    put("totalBytesIn", OmniDevVpnService.totalBytesIn.get())
                    put("totalBytesOut", OmniDevVpnService.totalBytesOut.get())
                    put("blockedCount", OmniDevVpnService.blockedDomains.size)
                    put("logSize", OmniDevVpnService.trafficLog.size)
                }
                ToolExecutionResult(status.toString(2))
            }
            "get_logs" -> {
                val limit = arguments["limit"]?.toIntOrNull() ?: 50
                val logs = OmniDevVpnService.trafficLog.takeLast(limit)
                val array = JSONArray()
                logs.forEach { entry ->
                    array.put(JSONObject().apply {
                        put("time", entry.timestamp)
                        put("type", entry.type)
                        put("host", entry.host)
                        put("bytes", entry.bytes)
                    })
                }
                ToolExecutionResult(array.toString(2))
            }
            "block_domain" -> {
                val domain = arguments["domain"] ?: return@withContext ToolExecutionResult("Missing 'domain' argument.", isError = true)
                OmniDevVpnService.addBlockedDomain(domain)
                ToolExecutionResult("✅ Domain '$domain' added to blocklist.")
            }
            "unblock_domain" -> {
                val domain = arguments["domain"] ?: return@withContext ToolExecutionResult("Missing 'domain' argument.", isError = true)
                OmniDevVpnService.removeBlockedDomain(domain)
                ToolExecutionResult("✅ Domain '$domain' removed from blocklist.")
            }
            "clear_logs" -> {
                OmniDevVpnService.clearLog()
                ToolExecutionResult("✅ VPN logs and stats cleared.")
            }
            else -> ToolExecutionResult("Unknown VPN action '$action'.", isError = true)
        }
    }

    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "vpn_control",
            description = "Control the local VPN network monitor and blocklist. Actions: 'start', 'stop', 'status', 'get_logs', 'block_domain', 'unblock_domain', 'clear_logs'.",
            parameters = listOf(
                ToolParameter("action", "string", "The action to perform.", required = true),
                ToolParameter("domain", "string", "Domain to block/unblock (required for block/unblock actions).", required = false),
                ToolParameter("limit", "string", "Max log entries to return (default 50).", required = false)
            )
        )
    )
}
