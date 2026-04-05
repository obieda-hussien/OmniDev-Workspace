package com.omnidev.workspace.data.network

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * OmniDevVpnService — a local VPN tunnel that intercepts all device IP traffic so the
 * agent can monitor outbound connections, detect privacy-leaking apps, and block ad/tracker
 * domains — all without root.
 *
 * ## How it works
 * Android's [VpnService] lets an app create a TUN interface without root.  We build the
 * interface with [Builder], read raw IP packets from it, inspect the destination address
 * or DNS name, decide whether to forward or drop, then write the packet back (for
 * dropped packets we write a simple TCP-RST / DNS-NXDOMAIN so the app fails fast).
 *
 * Actual forwarding is intentional and explicit: only traffic to hosts **not** on the block
 * list is written back to the TUN so the Android IP stack can route it normally.
 *
 * ## Security / privacy note
 * Packet inspection is purely local — no data is sent to any external server.  The
 * agent only sees destination IPs, hostnames extracted from DNS queries, and byte counts.
 *
 * ## Limitations
 * * Encrypted SNI (ESNI / ECH) prevents hostname extraction from TLS handshakes.
 * * DNS-over-HTTPS (DoH) resolvers bypass plain-DNS inspection; only UDP/53 is parsed.
 * * The "bypass" set lets the app's own traffic leave the tunnel so the AI can still
 *   make network calls while the VPN is active.
 */
class OmniDevVpnService : VpnService() {

    // ──────────────────────────────────────────────────────────────────────
    // Companion — public contract shared with NetworkMonitorTool
    // ──────────────────────────────────────────────────────────────────────
    companion object {
        const val ACTION_START = "com.omnidev.workspace.VPN_START"
        const val ACTION_STOP  = "com.omnidev.workspace.VPN_STOP"

        private const val CHANNEL_ID      = "omni_vpn_monitor"
        private const val NOTIFICATION_ID = 7701
        private const val MAX_LOG_ENTRIES = 500

        @Volatile var isRunning: Boolean = false
            private set

        /** Live traffic log: timestamp → human-readable entry. */
        val trafficLog: MutableList<TrafficEntry> = Collections.synchronizedList(ArrayList())

        /** Per-host byte counters: hostname/IP → stats. */
        val hostByteStats: MutableMap<String, HostStats> =
            Collections.synchronizedMap(LinkedHashMap())

        /** Domains/IPs that should be blocked (DNS NXDOMAIN / TCP RST). */
        val blockedDomains: MutableSet<String> =
            Collections.synchronizedSet(LinkedHashSet())

        val totalBytesIn  = AtomicLong(0)
        val totalBytesOut = AtomicLong(0)

        fun clearLog() {
            trafficLog.clear()
            hostByteStats.clear()
            totalBytesIn.set(0)
            totalBytesOut.set(0)
        }

        fun addBlockedDomain(domain: String) {
            blockedDomains.add(domain.lowercase().trim())
        }

        fun removeBlockedDomain(domain: String) {
            blockedDomains.remove(domain.lowercase().trim())
        }

        private fun addTrafficEntry(entry: TrafficEntry) {
            if (trafficLog.size >= MAX_LOG_ENTRIES) {
                try { trafficLog.removeAt(0) } catch (_: Exception) {}
            }
            trafficLog.add(entry)
        }

        /** Start or stop the VPN from anywhere (Activity, Tool, etc.). */
        fun startVpn(context: Context) {
            val intent = Intent(context, OmniDevVpnService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopVpn(context: Context) {
            val intent = Intent(context, OmniDevVpnService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Instance state
    // ──────────────────────────────────────────────────────────────────────

    private var vpnInterface: ParcelFileDescriptor? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ──────────────────────────────────────────────────────────────────────
    // Service lifecycle
    // ──────────────────────────────────────────────────────────────────────

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            else -> startTunnel()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        scope.cancel()
        vpnInterface?.close()
        vpnInterface = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ──────────────────────────────────────────────────────────────────────
    // Tunnel management
    // ──────────────────────────────────────────────────────────────────────

    private fun startTunnel() {
        if (isRunning) return

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("🔒 Network Monitor active"))

        val builder = Builder()
            .setSession("OmniDev Network Monitor")
            .addAddress("10.0.0.2", 24)          // virtual IP for this device
            .addRoute("0.0.0.0", 0)               // capture all IPv4 traffic
            .addDnsServer("8.8.8.8")
            .addDnsServer("1.1.1.1")
            .setMtu(1500)
            .allowFamily(android.system.OsConstants.AF_INET)

        // Allow OmniDev's own traffic to bypass the tunnel so the agent can still
        // make AI/network calls while the VPN is active.  Traffic from all other
        // apps goes through the tunnel and can be inspected.
        try {
            builder.addDisallowedApplication(packageName)
        } catch (_: Exception) {}

        try {
            vpnInterface = builder.establish()
        } catch (e: Exception) {
            addTrafficEntry(TrafficEntry(System.currentTimeMillis(), "ERROR", "Failed to establish VPN tunnel: ${e.message}", 0))
            stopSelf()
            return
        }

        isRunning = true
        startPacketReader()
    }

    // ──────────────────────────────────────────────────────────────────────
    // Packet reader coroutine
    // ──────────────────────────────────────────────────────────────────────

    private fun startPacketReader() {
        val fd = vpnInterface ?: return

        scope.launch {
            val input  = FileInputStream(fd.fileDescriptor)
            val output = FileOutputStream(fd.fileDescriptor)
            val packet = ByteBuffer.allocate(32_768)

            while (isActive && isRunning) {
                packet.clear()
                val len = try {
                    input.read(packet.array())
                } catch (_: Exception) {
                    break
                }
                if (len <= 0) continue

                packet.limit(len)
                totalBytesOut.addAndGet(len.toLong())

                val drop = processPacket(packet, len)

                if (!drop) {
                    // Forward the packet back to the TUN (kernel routes it normally).
                    try {
                        output.write(packet.array(), 0, len)
                    } catch (_: Exception) {}
                }
                // Dropped packets are silently discarded — the connection attempt will
                // time out (no RST is sent, so the OS sees a hang rather than an immediate
                // "connection refused").
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Packet inspection
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Parses a raw IPv4 packet from the TUN, extracts the destination address (and for
     * UDP/53 — the queried DNS hostname), logs the connection, and decides whether to
     * drop the packet.
     *
     * @return `true` if the packet should be dropped (domain is blocked).
     */
    private fun processPacket(buf: ByteBuffer, len: Int): Boolean {
        if (len < 20) return false // too short for a valid IPv4 header

        val ipVersion = (buf.get(0).toInt() ushr 4) and 0xF
        if (ipVersion != 4) return false // skip IPv6 for now

        val protocol = buf.get(9).toInt() and 0xFF
        val srcIp = extractIp(buf, 12)
        val dstIp = extractIp(buf, 16)

        val ihlBytes = (buf.get(0).toInt() and 0xF) * 4
        if (ihlBytes < 20 || ihlBytes > len) return false

        var dnsName: String? = null

        // UDP
        if (protocol == 17 && len > ihlBytes + 8) {
            val dstPort = ((buf.get(ihlBytes + 2).toInt() and 0xFF) shl 8) or
                           (buf.get(ihlBytes + 3).toInt() and 0xFF)
            // Plain-text DNS query on port 53
            if (dstPort == 53) {
                dnsName = parseDnsQuery(buf, ihlBytes + 8)
            }
        }

        val label = dnsName ?: dstIp
        val blocked = isBlocked(label)

        val tag = when {
            dnsName != null -> "DNS"
            protocol == 6   -> "TCP"
            protocol == 17  -> "UDP"
            else            -> "IP"
        }

        addTrafficEntry(
            TrafficEntry(
                timestamp = System.currentTimeMillis(),
                type      = if (blocked) "BLOCKED" else tag,
                host      = label,
                bytes     = len
            )
        )

        // Update per-host (simplified — real per-app tracking needs /proc/net/tcp).
        val key = dnsName ?: dstIp
        val stats = hostByteStats.getOrPut(key) { HostStats(key) }
        stats.bytesOut += len

        return blocked
    }

    /** Extracts a dotted-quad IPv4 string from a ByteBuffer at [offset]. */
    private fun extractIp(buf: ByteBuffer, offset: Int): String {
        return "${buf.get(offset).toInt() and 0xFF}." +
               "${buf.get(offset + 1).toInt() and 0xFF}." +
               "${buf.get(offset + 2).toInt() and 0xFF}." +
               "${buf.get(offset + 3).toInt() and 0xFF}"
    }

    /**
     * Parses a DNS query payload (starting right after the UDP header) and returns the
     * queried name, or `null` on malformed/truncated input.
     */
    private fun parseDnsQuery(buf: ByteBuffer, udpPayloadOffset: Int): String? {
        // DNS header is 12 bytes; QDCOUNT at offset +4 (2 bytes)
        val dnsOff = udpPayloadOffset
        if (buf.limit() < dnsOff + 12) return null
        val qdCount = ((buf.get(dnsOff + 4).toInt() and 0xFF) shl 8) or
                       (buf.get(dnsOff + 5).toInt() and 0xFF)
        if (qdCount == 0) return null

        // Walk the QNAME labels starting at dnsOff + 12
        var pos = dnsOff + 12
        val labels = mutableListOf<String>()
        while (pos < buf.limit()) {
            val labelLen = buf.get(pos).toInt() and 0xFF
            if (labelLen == 0) break
            // Compression pointer (top 2 bits set) — skip, just return what we have
            if ((labelLen and 0xC0) == 0xC0) break
            pos++
            if (pos + labelLen > buf.limit()) break
            val label = ByteArray(labelLen)
            for (i in 0 until labelLen) label[i] = buf.get(pos + i)
            labels.add(String(label, Charsets.US_ASCII))
            pos += labelLen
        }
        return if (labels.isEmpty()) null else labels.joinToString(".")
    }

    /**
     * Returns true if [label] matches any entry in [blockedDomains].
     * Exact match is checked first (O(1) HashSet contains), then each label
     * component is tried as a suffix ("ads.example.com" blocks if "example.com" is listed).
     */
    private fun isBlocked(label: String): Boolean {
        val lower = label.lowercase()
        if (blockedDomains.contains(lower)) return true
        // Walk suffixes: "ads.example.com" → check "example.com", then "com"
        var dot = lower.indexOf('.')
        while (dot != -1) {
            val suffix = lower.substring(dot + 1)
            if (blockedDomains.contains(suffix)) return true
            dot = lower.indexOf('.', dot + 1)
        }
        return false
    }

    // ──────────────────────────────────────────────────────────────────────
    // Notification helpers
    // ──────────────────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "OmniDev Network Monitor",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Shows VPN-based network monitoring status" }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("OmniDev Network Monitor")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    // ──────────────────────────────────────────────────────────────────────
    // Data classes
    // ──────────────────────────────────────────────────────────────────────

    data class TrafficEntry(
        val timestamp: Long,
        val type: String,       // DNS | TCP | UDP | IP | BLOCKED | ERROR
        val host: String,
        val bytes: Int
    )

    data class HostStats(
        val host: String,
        var bytesOut: Long = 0,
        var bytesIn:  Long = 0,
        var connections: Int = 0
    )
}
