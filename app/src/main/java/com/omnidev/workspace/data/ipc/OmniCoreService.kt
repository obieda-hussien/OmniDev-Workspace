package com.omnidev.workspace.data.ipc

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import com.omnidev.workspace.ipc.IOmniCoreInterface
import com.omnidev.workspace.ipc.IOmniResponseCallback
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * OmniCoreService: AIDL IPC bridge for launcher → Omni brain control.
 *
 * Runs in :core_ipc process and is manifest-protected by
 * com.omnidev.permission.CONTROL_CORE (signature). We still enforce the same
 * permission in every Binder method defensively.
 */
class OmniCoreService : Service() {

    companion object {
        private const val TAG = "OmniCoreService"
        const val PERMISSION_CONTROL_CORE = "com.omnidev.permission.CONTROL_CORE"
    }

    private val serviceJob = SupervisorJob()
    private val exceptionHandler = CoroutineExceptionHandler { _, t ->
        Log.e(TAG, "Unhandled coroutine exception", t)
    }
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob + exceptionHandler)

    private lateinit var router: LauncherCommandRouter

    private val binder = object : IOmniCoreInterface.Stub() {

        override fun getSystemStatus(): Int {
            enforceCallingOrSelfPermission(
                PERMISSION_CONTROL_CORE,
                "Unauthorized IPC Call"
            )
            return router.getSystemStatus()
        }

        override fun executeSystemCommand(command: String?, contextData: String?) {
            enforceCallingOrSelfPermission(
                PERMISSION_CONTROL_CORE,
                "Unauthorized IPC Call"
            )
            val safeCommand = command?.trim().orEmpty()
            if (safeCommand.isBlank()) return
            serviceScope.launch {
                router.executeSystemCommand(safeCommand, contextData)
            }
        }

        override fun askAgentSilent(prompt: String?) {
            enforceCallingOrSelfPermission(
                PERMISSION_CONTROL_CORE,
                "Unauthorized IPC Call"
            )
            val safePrompt = prompt?.trim().orEmpty()
            if (safePrompt.isBlank()) return
            serviceScope.launch {
                router.askAgentSilent(safePrompt)
            }
        }

        override fun streamAgentResponse(prompt: String?, callback: IOmniResponseCallback?) {
            enforceCallingOrSelfPermission(
                PERMISSION_CONTROL_CORE,
                "Unauthorized IPC Call"
            )
            val safePrompt = prompt?.trim().orEmpty()
            val safeCallback = callback ?: return
            if (safePrompt.isBlank()) {
                try {
                    safeCallback.onError("Prompt cannot be blank")
                } catch (_: RemoteException) {
                    Log.w(TAG, "Callback died while sending empty-prompt error")
                }
                return
            }
            serviceScope.launch {
                router.streamAgentResponse(
                    prompt = safePrompt,
                    onToken = { token ->
                        try {
                            safeCallback.onToken(token)
                        } catch (_: RemoteException) {
                            Log.w(TAG, "Callback died during token stream")
                        }
                    },
                    onComplete = { full ->
                        try {
                            safeCallback.onComplete(full)
                        } catch (_: RemoteException) {
                            Log.w(TAG, "Callback died before completion")
                        }
                    },
                    onError = { error ->
                        try {
                            safeCallback.onError(error)
                        } catch (_: RemoteException) {
                            Log.w(TAG, "Callback died while sending error")
                        }
                    }
                )
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Keep privileged backends initialized in this isolated IPC process.
        PrivilegedExecutionManager.init(applicationContext)
        router = LauncherCommandRouter(applicationContext)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
