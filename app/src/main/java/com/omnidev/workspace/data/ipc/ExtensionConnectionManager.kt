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
import com.omnilink.sdk.IOmniResultCallback
import com.omnilink.sdk.OmniLinkConstants
import java.util.concurrent.ConcurrentHashMap
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
        @Volatile var binder: IExtensionService? = null
    ) {
        val id: String get() = packageName + "/" + serviceClassName
    }

    private val handles = ConcurrentHashMap<String, ExtensionHandle>()
    private val serviceConnections = ConcurrentHashMap<String, ServiceConnection>()

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
        initialized.set(false)
    }

    fun refreshDiscoveredExtensions() {
        val context = appContext ?: return
        val pm = context.packageManager
        val intent = Intent(ACTION_BIND_EXTENSION)
        val resolveInfos = runCatching {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentServices(intent, PackageManager.ResolveInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentServices(intent, 0)
            }
        }.getOrElse {
            Log.w(TAG, "Failed querying extension services", it)
            emptyList()
        }

        val discoveredIds = resolveInfos.mapNotNull { resolve ->
            val serviceInfo = resolve.serviceInfo ?: return@mapNotNull null
            if (!serviceInfo.exported) return@mapNotNull null
            val pkg = serviceInfo.packageName ?: return@mapNotNull null
            val cls = serviceInfo.name ?: return@mapNotNull null
            val id = pkg + "/" + cls
            handles.putIfAbsent(id, ExtensionHandle(pkg, cls))
            id
        }.toSet()

        handles.keys.filter { it !in discoveredIds }.forEach { id ->
            unbindById(id)
            handles.remove(id)
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
            }
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
        val manifestJson = binder.getCapabilityManifest()
        val manifest = json.decodeFromString<CapabilityManifest>(manifestJson)
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

        val intent = Intent(ACTION_BIND_EXTENSION).apply {
            component = ComponentName(handle.packageName, handle.serviceClassName)
            setPackage(handle.packageName)
        }
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                handle.binder = IExtensionService.Stub.asInterface(service)
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

    private fun invalidateAndReconnect(handle: ExtensionHandle, cause: Throwable) {
        Log.w(TAG, "Remote call failed; rebinding " + handle.id, cause)
        handle.binder = null
        unbindById(handle.id)
        bindById(handle.id)
    }

    private fun unbindById(id: String) {
        val context = appContext ?: return
        val conn = serviceConnections.remove(id) ?: return
        runCatching { context.unbindService(conn) }
        handles[id]?.binder = null
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
