package com.omnidev.workspace.data.oem

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import com.omnidev.system.contract.IOmniSystemService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Explicit OEM service, checked signer and ROM entitlement. Never falls back to a shell. */
class OemSystemClient(private val context: Context) {
    suspend fun execute(action: String, arguments: Bundle): Bundle = withContext(Dispatchers.IO) {
        val info = context.packageManager.getApplicationInfo(PACKAGE, 0)
        check(info.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0) { "System service is not installed in the ROM" }
        check(context.packageManager.checkSignatures(context.packageName, PACKAGE) == PackageManager.SIGNATURE_MATCH) { "System service signer does not match" }
        val ready = CompletableDeferred<IOmniSystemService>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) { ready.complete(IOmniSystemService.Stub.asInterface(binder)) }
            override fun onServiceDisconnected(name: ComponentName) { ready.completeExceptionally(IllegalStateException("OEM service disconnected")) }
            override fun onNullBinding(name: ComponentName) { ready.completeExceptionally(IllegalStateException("OEM service refused binding")) }
            override fun onBindingDied(name: ComponentName) { ready.completeExceptionally(IllegalStateException("OEM binding died")) }
        }
        val intent = Intent().setComponent(ComponentName(PACKAGE, "$PACKAGE.OmniSystemService"))
        val bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        check(bound) { "OEM system service is unavailable" }
        try { withTimeout(5_000) { ready.await() }.execute(action, arguments) }
        finally { runCatching { context.unbindService(connection) } }
    }
    companion object { const val PACKAGE = "com.omnidev.system" }
}
