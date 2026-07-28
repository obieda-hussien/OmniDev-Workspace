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
import com.omnilink.sdk.AccessDecision
import com.omnilink.sdk.ActionError
import com.omnilink.sdk.ActionOutcome
import com.omnilink.sdk.ActionRequest
import com.omnilink.sdk.CallerContext
import com.omnilink.sdk.CapabilityManifest
import com.omnilink.sdk.IExtensionService
import com.omnilink.sdk.OmniLinkConstants
import com.omnidev.workspace.core.policy.ConfirmationGate
import com.omnidev.workspace.core.policy.ConfirmationKind
import com.omnidev.workspace.core.policy.TierPolicyHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Dynamic discovery and binding manager for Omni-Link extension services.
 *
 * Reconnects with exponential backoff on process death or RemoteException, caches CapabilityManifest
 * after binding, and enforces pre-flight checks and the client-side confirmation gate.
 */
object ExtensionConnectionManager {
    private const val TAG = "ExtensionConnectionMgr"
    private const val MAX_BIND_RETRIES = 15
    private const val BIND_RETRY_DELAY_MS = 100L

    @Volatile
    private var appContext: Context? = null

    private val initialized = AtomicBoolean(false)
    private val receiverRegistered = AtomicBoolean(false)

    // Workspace client-side access control and audit logging adapters
    private val accessController = WorkspaceAccessController()
    private val auditLogger = WorkspaceAuditLogger()

    // Confirmation gate callback populated from ChatViewModel
    @Volatile
    var confirmationGate: ConfirmationGate? = null

    data class ExtensionHandle(
        val packageName: String,
        val serviceClassName: String,
        @Volatile var binder: IExtensionService? = null,
        @Volatile var manifest: CapabilityManifest? = null
    ) {
        val id: String get() = "$packageName/$serviceClassName"
    }

    internal val handles = ConcurrentHashMap<String, ExtensionHandle>()
    private val serviceConnections = ConcurrentHashMap<String, ServiceConnection>()
    private val reconnectJobs = ConcurrentHashMap<String, kotlinx.coroutines.Job>()

    private val json = Json { ignoreUnknownKeys = true }

    private val packageChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            Log.i(TAG, "Package change detected: ${intent?.action}")
            refreshDiscoveredExtensions()
        }
    }

    fun initialize(context: Context) {
        val localContext = context.applicationContext
        appContext = localContext
        if (initialized.compareAndSet(false, true)) {
            registerPackageReceiver(localContext)
        }
        refreshDiscoveredExtensions()
    }

    fun shutdown() {
        val context = appContext
        if (context != null && receiverRegistered.compareAndSet(true, false)) {
            runCatching { context.unregisterReceiver(packageChangeReceiver) }
        }
        reconnectJobs.values.forEach { it.cancel() }
        reconnectJobs.clear()
        unbindAll()
        initialized.set(false)
    }

    fun refreshDiscoveredExtensions() {
        val context = appContext ?: return
        val pm = context.packageManager
        val intent = Intent(OmniLinkConstants.ACTION_EXTENSION_BIND)
        val resolveInfos = runCatching {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentServices(intent, PackageManager.ResolveInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentServices(intent, 0)
            }
        }.getOrElse {
            Log.w(TAG, "Failed querying extension services: ${it.message}")
            emptyList()
        }

        val discoveredIds = resolveInfos.mapNotNull { resolve ->
            val serviceInfo = resolve.serviceInfo ?: return@mapNotNull null
            if (!serviceInfo.exported) return@mapNotNull null
            val pkg = serviceInfo.packageName ?: return@mapNotNull null
            val cls = serviceInfo.name ?: return@mapNotNull null
            val id = "$pkg/$cls"
            handles.putIfAbsent(id, ExtensionHandle(packageName = pkg, serviceClassName = cls))
            id
        }.toSet()

        val stale = handles.keys.filter { it !in discoveredIds }
        for (id in stale) {
            unbindById(id)
            handles.remove(id)
        }

        discoveredIds.forEach { bindById(it) }
    }

    suspend fun listExtensions(forceRefresh: Boolean = true): List<JSONObject> = withContext(Dispatchers.IO) {
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
        val handle = handles[extensionId]
            ?: return@withContext ActionOutcome.Failure(ActionError("not_found", "Extension not found: $extensionId")).toJsonString()
        val binder = ensureBound(handle)
            ?: return@withContext ActionOutcome.Failure(ActionError("not_connected", "Extension is not currently connected: $extensionId")).toJsonString()

        val request = ActionRequest("_manifest", Json.parseToJsonElement("{}"))
        val requestJson = Json.encodeToString(ActionRequest.serializer(), request)

        runCatching {
            binder.executeAction(1, requestJson)
        }.getOrElse { t ->
            Log.w(TAG, "Failed to fetch manifest: ${t.message}")
            handle.binder = null
            triggerReconnectWithBackoff(handle)
            ActionOutcome.Failure(ActionError("manifest_failed", t.message ?: "Unknown IPC error")).toJsonString()
        }
    }

    suspend fun executeAction(
        extensionId: String,
        actionName: String,
        jsonPayload: String
    ): String = withContext(Dispatchers.IO) {
        val handle = handles[extensionId]
            ?: return@withContext ActionOutcome.Failure(ActionError("not_found", "Extension not found: $extensionId")).toJsonString()
        val binder = ensureBound(handle)
            ?: return@withContext ActionOutcome.Failure(ActionError("not_connected", "Extension is not currently connected: $extensionId")).toJsonString()

        // 1. Pre-flight Access Controller Check
        val caller = CallerContext(
            android.os.Process.myUid(),
            appContext?.packageName?.takeIf { it.isNotBlank() } ?: "com.omnilink.test"
        )
        val payloadElement = runCatching { Json.parseToJsonElement(jsonPayload) }.getOrElse { Json.parseToJsonElement("{}") }
        val request = ActionRequest(actionName, payloadElement)

        val decision = accessController.decide(caller, request)
        if (decision == AccessDecision.DENY) {
            val outcome = ActionOutcome.Failure(ActionError("denied", "Execution denied by AccessController"))
            auditLogger.log(caller, request, outcome)
            return@withContext outcome.toJsonString()
        }

        // 2. Client-side Confirmation Gate Check
        val requiresConfirmation = isActionConfirmationRequired(handle.packageName, actionName)
        if (requiresConfirmation && decision == AccessDecision.REQUIRES_CONFIRMATION) {
            val gate = confirmationGate
            if (gate != null) {
                val approved = gate.request(
                    ConfirmationKind.ANDROID_INTENT,
                    "Execute action '$actionName' on extension '${handle.packageName}'?",
                    null
                )
                if (!approved) {
                    val outcome = ActionOutcome.Failure(ActionError("confirmation_denied", "User denied confirmation for action $actionName"))
                    auditLogger.log(caller, request, outcome)
                    return@withContext outcome.toJsonString()
                }
            }
        }

        // 3. Execution (non-blocking executeActionAsync)
        val requestJson = Json.encodeToString(ActionRequest.serializer(), request)
        val outcomeJsonStr = runCatching {
            suspendCancellableCoroutine<String> { continuation ->
                val callback = object : com.omnilink.sdk.IOmniResultCallback.Stub() {
                    override fun onResult(resultJson: String) {
                        continuation.resume(resultJson)
                    }
                }
                try {
                    binder.executeActionAsync(1, requestJson, callback)
                } catch (e: Exception) {
                    continuation.resumeWithException(e)
                }
            }
        }.getOrElse { t ->
            Log.w(TAG, "executeAction failed: ${t.message}")
            handle.binder = null
            handle.manifest = null
            triggerReconnectWithBackoff(handle)
            val outcome = ActionOutcome.Failure(ActionError("execution_failed", t.message ?: "Unknown IPC error"))
            auditLogger.log(caller, request, outcome)
            return@withContext outcome.toJsonString()
        }

        // 4. Log the outcome
        runCatching {
            val outcome = json.decodeFromString(ActionOutcome.serializer(), outcomeJsonStr)
            auditLogger.log(caller, request, outcome)
        }

        return@withContext outcomeJsonStr
    }

    private fun bindById(id: String) {
        val context = appContext ?: return
        val handle = handles[id] ?: return
        if (handle.binder != null || serviceConnections.containsKey(id)) return

        val intent = Intent(OmniLinkConstants.ACTION_EXTENSION_BIND).apply {
            component = ComponentName(handle.packageName, handle.serviceClassName)
            setPackage(handle.packageName)
        }

        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                handle.binder = IExtensionService.Stub.asInterface(service)
                Log.i(TAG, "Connected extension: ${handle.id}")
                reconnectJobs.remove(handle.id)?.cancel()

                CoroutineScope(Dispatchers.IO).launch {
                    fetchAndCacheManifest(handle)
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                handle.binder = null
                handle.manifest = null
                Log.w(TAG, "Disconnected extension: ${handle.id}")
                triggerReconnectWithBackoff(handle)
            }

            override fun onBindingDied(name: ComponentName?) {
                handle.binder = null
                handle.manifest = null
                Log.w(TAG, "Binding died extension: ${handle.id}")
                serviceConnections.remove(handle.id)
                triggerReconnectWithBackoff(handle)
            }

            override fun onNullBinding(name: ComponentName?) {
                handle.binder = null
                Log.w(TAG, "Null binding extension: ${handle.id}")
            }
        }

        val didBind = runCatching {
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }.getOrElse {
            Log.w(TAG, "Failed binding extension: ${handle.id}: ${it.message}")
            false
        }

        if (didBind) {
            serviceConnections[id] = connection
        } else {
            handle.binder = null
        }
    }

    private fun triggerReconnectWithBackoff(handle: ExtensionHandle) {
        val id = handle.id
        if (reconnectJobs.containsKey(id)) return

        val job = CoroutineScope(Dispatchers.Default).launch {
            var delayMs = 1000L
            while (isActive && handle.binder == null) {
                Log.i(TAG, "Attempting reconnect for ${handle.id} in ${delayMs}ms...")
                delay(delayMs)
                bindById(id)
                delayMs = (delayMs * 2).coerceAtMost(30000L)
            }
            reconnectJobs.remove(id)
        }
        reconnectJobs[id] = job
    }

    private suspend fun fetchAndCacheManifest(handle: ExtensionHandle) {
        val binder = handle.binder ?: return
        try {
            val request = ActionRequest("_manifest", Json.parseToJsonElement("{}"))
            val requestJson = Json.encodeToString(ActionRequest.serializer(), request)
            val resultJsonStr = binder.executeAction(1, requestJson)
            if (!resultJsonStr.isNullOrBlank()) {
                val outcome = json.decodeFromString(ActionOutcome.serializer(), resultJsonStr)
                if (outcome is ActionOutcome.Success) {
                    val manifest = json.decodeFromString<CapabilityManifest>(outcome.data.toString())
                    handle.manifest = manifest
                    Log.i(TAG, "Successfully cached manifest for ${handle.id}: $manifest")
                } else if (outcome is ActionOutcome.Failure) {
                    Log.w(TAG, "Failed to fetch manifest for ${handle.id}: ${outcome.error.message}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error fetching manifest for ${handle.id}: ${e.message}")
            handle.binder = null
            triggerReconnectWithBackoff(handle)
        }
    }

    fun isActionConfirmationRequired(packageName: String, actionName: String): Boolean {
        val handle = handles.values.firstOrNull { it.packageName == packageName } ?: return false
        val manifest = handle.manifest ?: return false
        val cap = manifest.capabilities.firstOrNull { it.name == actionName } ?: return false
        return cap.requiresConfirmation
    }

    private fun unbindById(id: String) {
        val context = appContext ?: return
        val conn = serviceConnections.remove(id) ?: return
        runCatching { context.unbindService(conn) }
        handles[id]?.let {
            it.binder = null
            it.manifest = null
        }
    }

    private fun unbindAll() {
        val ids = serviceConnections.keys.toList()
        for (id in ids) {
            unbindById(id)
        }
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
        if (handle.binder != null) return handle.binder
        bindById(handle.id)
        repeat(MAX_BIND_RETRIES) {
            handle.binder?.let { return it }
            delay(BIND_RETRY_DELAY_MS)
        }
        return null
    }

    private fun ActionOutcome.toJsonString(): String {
        return Json.encodeToString(ActionOutcome.serializer(), this)
    }
}
