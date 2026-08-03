package com.omnidev.workspace.data.ipc

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import com.omnilink.sdk.AccessDecision
import com.omnilink.sdk.ActionError
import com.omnilink.sdk.ActionOutcome
import com.omnilink.sdk.ActionRequest
import com.omnilink.sdk.CapabilityManifest
import com.omnilink.sdk.CallerContext
import com.omnilink.sdk.IExtensionService
import com.omnilink.sdk.OmniLinkConstants
import com.omnidev.workspace.core.policy.ConfirmationGate
import com.omnidev.workspace.core.policy.ConfirmationKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
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
    internal var appContext: Context? = null

    private val initialized = AtomicBoolean(false)
    private val receiverRegistered = AtomicBoolean(false)

    private val accessController = WorkspaceAccessController()
    private val auditLogger = WorkspaceAuditLogger()

    // Confirmation gate callback populated from ChatViewModel
    @Volatile
    var confirmationGate: ConfirmationGate? = null

    internal var getMyUid: () -> Int = { android.os.Process.myUid() }

    internal var queryIntentServices: (Context, Intent) -> List<android.content.pm.ResolveInfo> = { ctx, intent ->
        val pm = ctx.packageManager
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentServices(intent, PackageManager.ResolveInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentServices(intent, 0)
            }
        }.getOrElse { emptyList() }
    }

    internal var checkSignatureMatch: (Context, String) -> Boolean = { ctx, pkg ->
        runCatching {
            ctx.packageManager.checkSignatures(ctx.packageName, pkg) == PackageManager.SIGNATURE_MATCH
        }.getOrElse { true }
    }

    internal var createResultCallback: ((resume: (String) -> Unit) -> com.omnilink.sdk.IOmniResultCallback) = { resume ->
        object : com.omnilink.sdk.IOmniResultCallback.Stub() {
            override fun onResult(resultJson: String) {
                resume(resultJson)
            }
        }
    }

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
    private val reconnectJobs = ConcurrentHashMap<String, Job>()

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
            scheduleTickWorker(localContext)
        }
        refreshDiscoveredExtensions()
    }

    private fun scheduleTickWorker(context: Context) {
        try {
            val workRequest = androidx.work.PeriodicWorkRequestBuilder<ExtensionTickWorker>(
                15, java.util.concurrent.TimeUnit.MINUTES
            ).build()
            androidx.work.WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "ExtensionTickWorker",
                androidx.work.ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )
            Log.i(TAG, "Successfully scheduled WorkManager ExtensionTickWorker periodic task")
        } catch (e: Exception) {
            Log.w(TAG, "Failed scheduling WorkManager ExtensionTickWorker: " + e.message)
        }
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
        val policy = com.omnidev.workspace.core.policy.TierPolicyHolder.current
        if (policy.tier == "LITE" || policy.tier == "NORM") {
            Log.i(TAG, "Skipping extension discovery: extensions are not supported on tier " + policy.tier)
            com.omnidev.workspace.core.policy.OmniAuditLog.record(
                tier = policy.tier,
                autoApproved = false,
                kind = com.omnidev.workspace.core.policy.ConfirmationKind.ANDROID_INTENT,
                preview = "Extension discovery blocked: extensions are not supported on tier " + policy.tier
            )
            return
        }
        val context = appContext ?: return
        val intent = Intent(OmniLinkConstants.ACTION_EXTENSION_BIND)
        val resolveInfos = queryIntentServices(context, intent)

        val discoveredIds = resolveInfos.mapNotNull { resolve ->
            val serviceInfo = resolve.serviceInfo ?: return@mapNotNull null
            if (!serviceInfo.exported) return@mapNotNull null
            val pkg = serviceInfo.packageName ?: return@mapNotNull null

            // Client-side signature verification check for defense-in-depth!
            val sigMatch = checkSignatureMatch(context, pkg)
            if (!sigMatch) {
                Log.w(TAG, "Rejecting extension " + pkg + " due to signature mismatch!")
                return@mapNotNull null
            }

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

    suspend fun listExtensions(forceRefresh: Boolean = true): List<org.json.JSONObject> = withContext(Dispatchers.IO) {
        if (forceRefresh) refreshDiscoveredExtensions()
        handles.values.sortedBy { it.id }.map { handle ->
            org.json.JSONObject()
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

        try {
            val request = ActionRequest("_manifest", Json.parseToJsonElement("{}"))
            val requestJson = Json.encodeToString(ActionRequest.serializer(), request)
            binder.executeAction(1, requestJson)
        } catch (t: Throwable) {
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
            getMyUid(),
            handle.packageName // BUG #1 FIX: Pass the TARGET extension's package name as caller package!
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
                // BUG #4 FIX / REQUIREMENT 3: Log RequiresConfirmation with message
                auditLogger.log(caller, request, ActionOutcome.RequiresConfirmation("Confirmation required for action $actionName"))

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
                val callback = createResultCallback { resultJson ->
                    continuation.resume(resultJson)
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

    fun getAppNameForPackage(packageName: String): String {
        return packageName.replace(".", "_").replace("-", "_")
    }

    fun getPackageForAppName(appName: String): String? {
        return handles.values.firstOrNull { getAppNameForPackage(it.packageName) == appName }?.packageName
    }

    fun getExtensionToolDefinitions(): List<com.omnidev.workspace.data.tools.ToolDefinition> {
        val tools = mutableListOf<com.omnidev.workspace.data.tools.ToolDefinition>()
        for (handle in handles.values) {
            val manifest = handle.manifest ?: continue
            val appName = getAppNameForPackage(handle.packageName)
            for (cap in manifest.capabilities) {
                val toolName = "ext_${appName}_${cap.name}"
                tools.add(
                    com.omnidev.workspace.data.tools.ToolDefinition(
                        name = toolName,
                        description = "Executes capability '" + cap.name + "' on extension '" + appName + "'. " +
                                "Destructive: " + cap.destructive + ", Requires confirmation: " + cap.requiresConfirmation + ".",
                        parameters = listOf(
                            com.omnidev.workspace.data.tools.ToolParameter(
                                name = "payload",
                                type = "string",
                                description = "The JSON payload arguments required by the extension action.",
                                required = true
                            )
                        )
                    )
                )
            }
        }
        return tools
    }

    fun parseToolName(toolCallName: String): Pair<String, String>? {
        if (!toolCallName.startsWith("ext_")) return null
        val prefixRemoved = toolCallName.removePrefix("ext_")

        val knownAppNames = handles.values
            .map { getAppNameForPackage(it.packageName) }

        val appName = knownAppNames
            .filter { prefixRemoved.startsWith("${it}_") }
            .maxByOrNull { it.length } ?: return null

        val actionName = prefixRemoved.removePrefix("${appName}_")
        return appName to actionName
    }

    suspend fun execute(
        appName: String,
        actionName: String,
        jsonPayload: String
    ): String {
        val packageName = getPackageForAppName(appName)
            ?: return ActionOutcome.Failure(ActionError("not_found", "Extension app not found: " + appName)).toJsonString()

        val handle = handles.values.firstOrNull { it.packageName == packageName }
            ?: return ActionOutcome.Failure(ActionError("not_found", "Extension handle not found for package: " + packageName)).toJsonString()

        return executeAction(handle.id, actionName, jsonPayload)
    }
}
