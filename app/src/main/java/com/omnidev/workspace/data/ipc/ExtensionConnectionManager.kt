package com.omnidev.workspace.data.ipc

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import androidx.core.content.ContextCompat
import com.omnilink.sdk.ActionRequest
import com.omnilink.sdk.CapabilityManifest
import com.omnilink.sdk.IExtensionService
import com.omnilink.sdk.IOmniEventCallback
import com.omnilink.sdk.IOmniResultCallback
import com.omnilink.sdk.OmniLinkConstants
import com.omnilink.sdk.trusted.TrustedServiceResolver
import com.omnilink.sdk.trusted.TrustedServicePolicy
import com.omnilink.sdk.trusted.ProviderIdentityMode
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import org.json.JSONObject

/**
 * Typed OmniLink extension discovery and execution.
 *
 * Heavy actions always use IExtensionService.executeActionAsync. Capability discovery remains
 * synchronous because it is a small bounded metadata read. RemoteException and binder death both
 * invalidate the same connection so the next operation rebinds instead of keeping a dead handle.
 */
object ExtensionConnectionManager {
    private const val TAG = "ExtensionConnectionMgr"
    private const val MAX_BIND_RETRIES = 15
    private const val BIND_RETRY_DELAY_MS = 100L
    private const val ACTION_TIMEOUT_MS = 10 * 60 * 1000L
    private const val MAX_BINDER_REQUEST_CHARS = 300_000
    private const val LEGACY_PROTOCOL_VERSION = 1
    private const val MAX_RECENT_EVENTS = 300

    const val ACTION_BIND_EXTENSION = OmniLinkConstants.ACTION_EXTENSION_BIND

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        classDiscriminator = "type"
    }

    @Volatile
    private var appContext: Context? = null

    private val initialized = AtomicBoolean(false)
    private val receiverRegistered = AtomicBoolean(false)

    data class ExtensionHandle(
        val packageName: String,
        val serviceClassName: String,
        @Volatile var binder: IExtensionService? = null,
        @Volatile var eventCallback: IOmniEventCallback? = null
    ) {
        val id: String get() = packageName + "/" + serviceClassName
    }

    private val handles = ConcurrentHashMap<String, ExtensionHandle>()
    private val serviceConnections = ConcurrentHashMap<String, ServiceConnection>()
    private val eventQueues = ConcurrentHashMap<String, ConcurrentLinkedDeque<String>>()

    private val packageChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            Log.i(TAG, "Package change detected: " + intent?.action)
            refreshDiscoveredExtensions()
        }
    }

    fun initialize(context: Context) {
        val localContext = context.applicationContext
        appContext = localContext
        if (initialized.compareAndSet(false, true)) registerPackageReceiver(localContext)
        refreshDiscoveredExtensions()
    }

    fun shutdown() {
        val context = appContext
        if (context != null && receiverRegistered.compareAndSet(true, false)) {
            runCatching { context.unregisterReceiver(packageChangeReceiver) }
        }
        unbindAll()
        eventQueues.clear()
        initialized.set(false)
    }

    fun refreshDiscoveredExtensions() {
        val context = appContext ?: return
        // No package/action-name trust: authenticate the provider before discovery and binding.
        val verified = TrustedServiceResolver(context).query(
            TrustedServicePolicy(
                action = ACTION_BIND_EXTENSION,
                requiredPermission = OmniLinkConstants.PERMISSION_BIND_EXTENSION,
                identityMode = ProviderIdentityMode.SAME_SIGNER
            )
        )
        verified.rejected.forEach {
            Log.w(TAG, "Rejected unverified extension: " + it.packageName + "/" +
                it.serviceClassName + " reason=" + it.reason)
        }
        val discoveredIds = verified.verified.map { service ->
            val id = service.packageName + "/" + service.serviceClassName
            handles.putIfAbsent(id, ExtensionHandle(service.packageName, service.serviceClassName))
            id
        }.toSet()

        handles.keys.filter { it !in discoveredIds }.forEach { id ->
            unbindById(id)
            handles.remove(id)
            eventQueues.remove(id)
        }
        discoveredIds.forEach(::bindById)
    }

    suspend fun listExtensions(forceRefresh: Boolean = true): List<JSONObject> =
        withContext(Dispatchers.IO) {
            if (forceRefresh) refreshDiscoveredExtensions()
            handles.values.sortedBy { it.id }.map { handle ->
                JSONObject()
                    .put("id", handle.id)
                    .put("package", handle.packageName)
                    .put("service", handle.serviceClassName)
                    .put("connected", handle.binder != null)
                    .put("recent_event_count", eventQueues[handle.id]?.size ?: 0)
            }
        }

    suspend fun getRecentEvents(extensionId: String, limit: Int = 100): List<String> =
        withContext(Dispatchers.IO) {
            val queue = eventQueues[extensionId] ?: return@withContext emptyList()
            queue.toList().takeLast(limit.coerceIn(1, MAX_RECENT_EVENTS))
        }

    suspend fun getExtensionManifest(extensionId: String): String = withContext(Dispatchers.IO) {
        val handle = handles[extensionId] ?: return@withContext errorJson(
            "Extension not found: " + extensionId
        )
        val binder = ensureBound(handle) ?: return@withContext errorJson(
            "Extension is not currently connected: " + extensionId
        )
        try {
            binder.getCapabilityManifest().ifBlank { "{}" }
        } catch (remote: RemoteException) {
            invalidateAndReconnect(handle, remote)
            errorJson("Failed to fetch manifest: " + (remote.message ?: "remote process died"))
        } catch (error: Exception) {
            errorJson("Failed to fetch manifest: " + (error.message ?: "unknown"))
        }
    }

    suspend fun executeAction(
        extensionId: String,
        actionName: String,
        jsonPayload: String
    ): String = withContext(Dispatchers.IO) {
        val handle = handles[extensionId] ?: return@withContext errorJson(
            "Extension not found: " + extensionId
        )
        val binder = ensureBound(handle) ?: return@withContext errorJson(
            "Extension is not currently connected: " + extensionId
        )

        val payload: JsonElement = runCatching {
            json.parseToJsonElement(jsonPayload.ifBlank { "{}" })
        }.getOrElse { buildJsonObject {} }
        val requestJson = json.encodeToString(ActionRequest(actionName, payload))
        if (requestJson.length > MAX_BINDER_REQUEST_CHARS) {
            return@withContext errorJson(
                "Action payload is too large for safe Binder transport (" +
                    requestJson.length + " chars; max " + MAX_BINDER_REQUEST_CHARS +
                    "). Use smaller targeted operations."
            )
        }

        try {
            val protocol = negotiateProtocol(binder)
            withTimeout(ACTION_TIMEOUT_MS) {
                executeAsync(binder, protocol, requestJson)
            }.ifBlank { "{}" }
        } catch (remote: RemoteException) {
            invalidateAndReconnect(handle, remote)
            errorJson("Action execution failed: " + (remote.message ?: "remote process died"))
        } catch (error: Exception) {
            errorJson("Action execution failed: " + (error.message ?: "unknown"))
        }
    }

    private fun negotiateProtocol(binder: IExtensionService): Int {
        val manifest = runCatching {
            json.decodeFromString<CapabilityManifest>(binder.getCapabilityManifest())
        }.getOrElse { error ->
            // Protocol-v1/v2 services predate getCapabilityManifest(). Because the new AIDL method
            // is appended (never inserted), their existing executeAction transaction IDs remain
            // compatible. Explicit legacy actions can therefore continue on protocol 1.
            Log.i(TAG, "Extension has no v3 manifest; using legacy protocol 1", error)
            return LEGACY_PROTOCOL_VERSION
        }

        val preferred = minOf(
            OmniLinkConstants.CURRENT_PROTOCOL_VERSION,
            manifest.maxSupportedVersion
        )
        if (preferred < manifest.minSupportedVersion) {
            throw IllegalStateException(
                "No compatible OmniLink protocol. Workspace=" +
                    OmniLinkConstants.CURRENT_PROTOCOL_VERSION +
                    ", extension=" + manifest.minSupportedVersion + ".." + manifest.maxSupportedVersion
            )
        }
        return preferred
    }

    private suspend fun executeAsync(
        binder: IExtensionService,
        protocolVersion: Int,
        requestJson: String
    ): String = suspendCancellableCoroutine { continuation ->
        val callback = object : IOmniResultCallback.Stub() {
            override fun onResult(resultJson: String) {
                if (continuation.isActive) continuation.resume(resultJson)
            }
        }
        try {
            binder.executeActionAsync(protocolVersion, requestJson, callback)
        } catch (error: RemoteException) {
            if (continuation.isActive) continuation.resumeWithException(error)
        } catch (error: Exception) {
            if (continuation.isActive) continuation.resumeWithException(error)
        }
    }

    private fun bindById(id: String) {
        val context = appContext ?: return
        val handle = handles[id] ?: return
        if (handle.binder != null || serviceConnections.containsKey(id)) return
        if (!isVerifiedProvider(context, handle)) {
            Log.w(TAG, "Refusing to bind unverified provider " + id)
            return
        }

        val intent = Intent(ACTION_BIND_EXTENSION).apply {
            component = ComponentName(handle.packageName, handle.serviceClassName)
            setPackage(handle.packageName)
        }
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                if (name != ComponentName(handle.packageName, handle.serviceClassName) ||
                    !isVerifiedProvider(context, handle)
                ) {
                    Log.w(TAG, "Provider identity changed before bind: " + handle.id)
                    unbindById(handle.id)
                    return
                }
                val typed = IExtensionService.Stub.asInterface(service)
                handle.binder = typed
                if (typed != null) registerEventStream(handle, typed)
                Log.i(TAG, "Connected extension: " + handle.id)
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                // The binding itself remains active after a transient service-process death;
                // Android will reconnect this ServiceConnection when the service comes back.
                // Starting a second bind here leaks/duplicates connections.
                handle.binder = null
                Log.w(TAG, "Disconnected extension; awaiting system reconnect: " + handle.id)
            }

            override fun onBindingDied(name: ComponentName?) {
                handle.binder = null
                Log.w(TAG, "Binding died extension; rebinding: " + handle.id)
                unbindById(handle.id)
                bindById(handle.id)
            }

            override fun onNullBinding(name: ComponentName?) {
                handle.binder = null
                Log.w(TAG, "Null binding extension: " + handle.id)
                unbindById(handle.id)
            }
        }

        val didBind = runCatching {
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }.getOrElse {
            Log.w(TAG, "Failed binding extension: " + handle.id, it)
            false
        }
        if (didBind) serviceConnections[id] = connection
        else handle.binder = null
    }

    private fun isVerifiedProvider(context: Context, handle: ExtensionHandle): Boolean =
        TrustedServiceResolver(context).query(
            TrustedServicePolicy(
                action = ACTION_BIND_EXTENSION,
                requiredPermission = OmniLinkConstants.PERMISSION_BIND_EXTENSION,
                identityMode = ProviderIdentityMode.SAME_SIGNER
            )
        ).verified.any {
            it.packageName == handle.packageName &&
                it.serviceClassName == handle.serviceClassName
        }

    private fun registerEventStream(handle: ExtensionHandle, binder: IExtensionService) {
        if (handle.eventCallback != null) return
        val callback = object : IOmniEventCallback.Stub() {
            override fun onEvent(eventJson: String) {
                val queue = eventQueues.getOrPut(handle.id) { ConcurrentLinkedDeque() }
                queue.addLast(eventJson.take(64_000))
                while (queue.size > MAX_RECENT_EVENTS) {
                    queue.pollFirst()
                }
            }
        }
        val registered = runCatching { binder.registerEventListener(callback) }
            .getOrElse {
                Log.w(TAG, "Failed registering extension event stream: " + handle.id, it)
                false
            }
        if (registered) {
            handle.eventCallback = callback
        }
    }

    private fun invalidateAndReconnect(handle: ExtensionHandle, cause: Throwable) {
        Log.w(TAG, "Remote call failed; rebinding " + handle.id, cause)
        handle.binder = null
        unbindById(handle.id)
        bindById(handle.id)
    }

    private fun unbindById(id: String) {
        val context = appContext ?: return
        val handle = handles[id]
        val callback = handle?.eventCallback
        val binder = handle?.binder
        if (callback != null && binder != null) {
            runCatching { binder.unregisterEventListener(callback) }
        }
        if (handle != null) handle.eventCallback = null

        val conn = serviceConnections.remove(id) ?: return
        runCatching { context.unbindService(conn) }
        handle?.binder = null
    }

    private fun unbindAll() {
        serviceConnections.keys.toList().forEach(::unbindById)
    }

    private fun registerPackageReceiver(context: Context) {
        if (!receiverRegistered.compareAndSet(false, true)) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(
            context,
            packageChangeReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private suspend fun ensureBound(handle: ExtensionHandle): IExtensionService? {
        handle.binder?.let { return it }
        bindById(handle.id)
        repeat(MAX_BIND_RETRIES) {
            handle.binder?.let { return it }
            delay(BIND_RETRY_DELAY_MS)
        }
        return null
    }

    private fun errorJson(message: String): String =
        JSONObject().put("ok", false).put("error", message).toString()
}
