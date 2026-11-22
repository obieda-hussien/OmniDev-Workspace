package com.omnidev.workspace.data.network

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * DNS-only monitor. Route one virtual IPv4 resolver, not ordinary internet traffic.
 * Forward valid UDP DNS over protected sockets bound to the existing underlying network.
 * Unsupported protocols, upstream failures and network changes tear down the tunnel so
 * Android can restore its normal resolver. Never reinject an outbound packet as inbound.
 * Private DNS, DoH, direct IP connections and IPv6 traffic are not inspected.
 */
class OmniDevVpnService : VpnService() {
    companion object {
        const val ACTION_START = "com.omnidev.workspace.VPN_START"
        const val ACTION_STOP = "com.omnidev.workspace.VPN_STOP"
        const val MODE = "DNS-only (IPv4 UDP/53)"
        private const val CHANNEL_ID = "omni_vpn_monitor"
        private const val NOTIFICATION_ID = 7701
        private const val MAX_LOG_ENTRIES = 500

        @Volatile var isRunning = false
            private set
        @Volatile var lastError: String? = null
            private set

        val trafficLog: MutableList<TrafficEntry> = Collections.synchronizedList(ArrayList())
        val hostByteStats: MutableMap<String, HostStats> = Collections.synchronizedMap(LinkedHashMap())
        val blockedDomains: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet())
        val totalBytesIn = AtomicLong(0)
        val totalBytesOut = AtomicLong(0)

        fun clearLog() {
            trafficLog.clear()
            hostByteStats.clear()
            totalBytesIn.set(0)
            totalBytesOut.set(0)
        }

        fun addBlockedDomain(domain: String) {
            DnsMonitorPacket.normalizeDomain(domain)?.let { blockedDomains.add(it) }
        }

        fun removeBlockedDomain(domain: String) {
            DnsMonitorPacket.normalizeDomain(domain)?.let { blockedDomains.remove(it) }
        }

        private fun addTrafficEntry(entry: TrafficEntry) = synchronized(trafficLog) {
            if (trafficLog.size >= MAX_LOG_ENTRIES) trafficLog.removeAt(0)
            trafficLog.add(entry)
        }

        fun startVpn(context: Context) {
            val intent = Intent(context, OmniDevVpnService::class.java).apply { action = ACTION_START }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun stopVpn(context: Context) {
            context.stopService(Intent(context, OmniDevVpnService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var vpnInterface: ParcelFileDescriptor? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var activeSocket: DatagramSocket? = null
    @Volatile private var stopping = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startTunnel()
            ACTION_STOP -> stopTunnel()
            else -> stopTunnel()
        }
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        stopTunnel("VPN permission revoked")
        super.onRevoke()
    }

    override fun onDestroy() {
        releaseTunnel()
        scope.cancel()
        super.onDestroy()
    }

    @Synchronized private fun releaseTunnel() {
        stopping = true
        isRunning = false
        activeSocket?.close()
        activeSocket = null
        runCatching { vpnInterface?.close() }
        vpnInterface = null
        networkCallback?.let { callback ->
            runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(callback) }
        }
        networkCallback = null
    }

    @Synchronized private fun stopTunnel(reason: String? = null) {
        if (stopping) return
        if (reason != null) {
            lastError = reason
            addTrafficEntry(TrafficEntry(System.currentTimeMillis(), "ERROR", reason, 0))
        }
        releaseTunnel()
        stopSelf()
    }

    @Synchronized private fun startTunnel() {
        if (isRunning || stopping) return
        lastError = null
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("DNS monitor: starting"))
        try {
            // Network-bound DatagramSocket and underlying-network selection require API 23.
            check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) { "DNS monitoring requires Android 6 or newer" }
            check(prepare(this) == null) { "VPN consent required" }
            val manager = getSystemService(ConnectivityManager::class.java)
            val network = checkNotNull(manager.activeNetwork) { "No underlying network" }
            val caps = checkNotNull(manager.getNetworkCapabilities(network))
            check(!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) { "Another VPN is active" }
            val link = checkNotNull(manager.getLinkProperties(network))
            check(!privateDns(link)) { "Private DNS is active; monitoring would downgrade encrypted DNS" }
            val upstream = link.dnsServers.distinct().take(3)
            check(upstream.isNotEmpty()) { "No underlying DNS resolver" }

            // Do not intercept other IPv4 or IPv6 destinations, including encrypted DNS.
            val builder = Builder().setSession("OmniDev DNS Monitor")
                .addAddress(DnsMonitorPacket.CLIENT, 32)
                .addRoute(DnsMonitorPacket.RESOLVER, 32)
                .addDnsServer(DnsMonitorPacket.RESOLVER)
                .allowFamily(OsConstants.AF_INET6)
                .setMtu(DnsMonitorPacket.MTU)
                .setBlocking(false)
                .addDisallowedApplication(packageName)
                .setUnderlyingNetworks(arrayOf(network))

            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onLost(lost: Network) {
                    if (lost == network) stopTunnel("Underlying network disconnected")
                }
                override fun onLinkPropertiesChanged(changed: Network, properties: LinkProperties) {
                    if (changed == network && (privateDns(properties) || properties.dnsServers.distinct().take(3) != upstream)) {
                        stopTunnel("Underlying DNS changed; restart monitor if needed")
                    }
                }
            }
            networkCallback = callback
            manager.registerNetworkCallback(NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), callback)
            val fd = checkNotNull(builder.establish()) { "VPN establishment denied" }
            vpnInterface = fd
            isRunning = true
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification("DNS-only monitoring active"))
            startPacketReader(fd, network, upstream)
        } catch (e: Exception) {
            stopTunnel("DNS monitor could not start: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun privateDns(link: LinkProperties): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && (link.isPrivateDnsActive || link.privateDnsServerName != null)

    private fun startPacketReader(fd: ParcelFileDescriptor, network: Network, upstream: List<InetAddress>) {
        scope.launch {
            val descriptor = fd.fileDescriptor
            val poll = StructPollfd().apply {
                this.fd = descriptor
                events = OsConstants.POLLIN.toShort()
            }
            val packet = ByteArray(DnsMonitorPacket.MTU + 1)
            try {
                while (isActive && isRunning && !stopping) {
                    if (Os.poll(arrayOf(poll), 500) == 0) continue
                    if (poll.revents.toInt() and OsConstants.POLLIN == 0) error("DNS tunnel unavailable")
                    val length = Os.read(descriptor, packet, 0, packet.size)
                    if (length <= 0) error("DNS tunnel closed")
                    val query = DnsMonitorPacket.parse(packet, length)
                        ?: error("Unsupported DNS packet (including TCP); restoring system DNS")
                    val blocked = synchronized(blockedDomains) { DnsMonitorPacket.blocked(query.domain, blockedDomains) }
                    val answer = if (blocked) DnsMonitorPacket.refused(query) else forward(query, network, upstream)
                    val reply = DnsMonitorPacket.reply(query, answer)
                    check(Os.write(descriptor, reply, 0, reply.size) == reply.size) { "Incomplete DNS reply" }
                    totalBytesOut.addAndGet(length.toLong())
                    totalBytesIn.addAndGet(reply.size.toLong())
                    addTrafficEntry(TrafficEntry(System.currentTimeMillis(), if (blocked) "BLOCKED" else "DNS", query.domain, length))
                    synchronized(hostByteStats) {
                        if (!hostByteStats.containsKey(query.domain) && hostByteStats.size >= MAX_LOG_ENTRIES) {
                            hostByteStats.keys.iterator().apply { next(); remove() }
                        }
                        val stat = hostByteStats.getOrPut(query.domain) { HostStats(query.domain) }
                        stat.bytesOut += length
                        stat.bytesIn += reply.size
                        stat.connections++
                    }
                }
            } catch (e: Exception) {
                if (!stopping) stopTunnel("DNS monitoring stopped: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /** Connected UDP socket validates source address/port. Never use a public fallback DNS. */
    private fun forward(query: DnsMonitorPacket.Query, network: Network, upstream: List<InetAddress>): ByteArray {
        for (server in upstream) {
            if (stopping) error("Monitor stopped")
            try {
                DatagramSocket().use { socket ->
                    activeSocket = socket
                    check(!stopping && protect(socket)) { "Could not protect DNS socket" }
                    network.bindSocket(socket)
                    socket.soTimeout = 1500
                    socket.connect(server, 53)
                    socket.send(DatagramPacket(query.dns, query.dns.size))
                    val data = ByteArray(65535)
                    val response = DatagramPacket(data, data.size)
                    socket.receive(response)
                    val reply = data.copyOf(response.length)
                    if (DnsMonitorPacket.validReply(query, reply)) return reply
                }
            } catch (_: Exception) {
                // Try the next resolver already configured by the underlying network.
            } finally {
                activeSocket = null
            }
        }
        error("No usable UDP DNS reply (timeout, truncation or unsupported size)")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "OmniDev DNS Monitor", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("OmniDev DNS Monitor")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    data class TrafficEntry(val timestamp: Long, val type: String, val host: String, val bytes: Int)
    data class HostStats(val host: String, var bytesOut: Long = 0, var bytesIn: Long = 0, var connections: Int = 0)
}
