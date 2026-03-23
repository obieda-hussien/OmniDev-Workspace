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
import com.omnidev.extension.ipc.IOmniExtensionInterface
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Dynamic discovery and binding manager for Omni-Link extension services.
 *
 * Discovery contract:
 * - Intent action: [ACTION_BIND_EXTENSION]
 * - Service must export an AIDL binder implementing [IOmniExtensionInterface]
 */
object ExtensionConnectionManager {
    private const val TAG = "ExtensionConnectionMgr"
    private const val MAX_BIND_RETRIES = 15
    private const val BIND_RETRY_DELAY_MS = 100L

    const val ACTION_BIND_EXTENSION = "com.omnidev.action.BIND_EXTENSION"

    @Volatile
    private var appContext: Context? = null

    private val initialized = AtomicBoolean(false)
    private val receiverRegistered = AtomicBoolean(false)

    data class ExtensionHandle(
        val packageName: String,
        val serviceClassName: String,
        @Volatile var binder: IOmniExtensionInterface? = null
    ) {
        val id: String get() = "$packageName/$serviceClassName"
    }

    private val handles = ConcurrentHashMap<String, ExtensionHandle>()
    private val serviceConnections = ConcurrentHashMap<String, ServiceConnection>()

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
            ?: return@withContext JSONObject()
                .put("ok", false)
                .put("error", "Extension not found: $extensionId")
                .toString()
        val binder = ensureBound(handle)
            ?: return@withContext JSONObject()
                .put("ok", false)
                .put("error", "Extension is not currently connected: $extensionId")
                .toString()
        runCatching { binder.getExtensionManifest() }
            .map { it.ifBlank { "{}" } }
            .getOrElse {
                JSONObject()
                    .put("ok", false)
                    .put("error", "Failed to fetch manifest: ${it.message ?: "unknown"}")
                    .toString()
            }
    }

    suspend fun executeAction(
        extensionId: String,
        actionName: String,
        jsonPayload: String
    ): String = withContext(Dispatchers.IO) {
        val handle = handles[extensionId]
            ?: return@withContext JSONObject()
                .put("ok", false)
                .put("error", "Extension not found: $extensionId")
                .toString()
        val binder = ensureBound(handle)
            ?: return@withContext JSONObject()
                .put("ok", false)
                .put("error", "Extension is not currently connected: $extensionId")
                .toString()
        runCatching { binder.executeAction(actionName, jsonPayload) }
            .map { it.ifBlank { "{}" } }
            .getOrElse {
                JSONObject()
                    .put("ok", false)
                    .put("error", "Action execution failed: ${it.message ?: "unknown"}")
                    .toString()
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
                handle.binder = IOmniExtensionInterface.Stub.asInterface(service)
                Log.i(TAG, "Connected extension: ${handle.id}")
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                handle.binder = null
                Log.w(TAG, "Disconnected extension: ${handle.id}")
            }

            override fun onBindingDied(name: ComponentName?) {
                handle.binder = null
                Log.w(TAG, "Binding died extension: ${handle.id}")
                serviceConnections.remove(handle.id)
                bindById(handle.id)
            }

            override fun onNullBinding(name: ComponentName?) {
                handle.binder = null
                Log.w(TAG, "Null binding extension: ${handle.id}")
            }
        }

        val didBind = runCatching {
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }.getOrElse {
            Log.w(TAG, "Failed binding extension: ${handle.id}", it)
            false
        }

        if (didBind) {
            serviceConnections[id] = connection
        } else {
            handle.binder = null
        }
    }

    private fun unbindById(id: String) {
        val context = appContext ?: return
        val conn = serviceConnections.remove(id) ?: return
        runCatching { context.unbindService(conn) }
        handles[id]?.binder = null
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

    private suspend fun ensureBound(handle: ExtensionHandle): IOmniExtensionInterface? {
        if (handle.binder != null) return handle.binder
        bindById(handle.id)
        repeat(MAX_BIND_RETRIES) {
            handle.binder?.let { return it }
            delay(BIND_RETRY_DELAY_MS)
        }
        return null
    }
}
