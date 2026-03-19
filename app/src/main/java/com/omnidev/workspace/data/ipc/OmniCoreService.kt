package com.omnidev.workspace.data.ipc

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.DeadObjectException
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import android.view.KeyEvent
import com.omnidev.workspace.IOmniCoreInterface
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * OmniCoreService — a bound Android [Service] running in the isolated process
 * `:core_ipc` that exposes the [IOmniCoreInterface] AIDL interface to trusted
 * companion apps.
 *
 * ### Security model
 * Every binder method starts by calling [enforceCorePermission], which invokes
 * [enforceCallingPermission] with the custom signature-level permission
 * `com.omnidev.permission.CONTROL_CORE`. Unsigned or differently-signed callers
 * are rejected with a [SecurityException] before any privileged work is done.
 *
 * ### Process isolation
 * Declared with `android:process=":core_ipc"` in the Manifest so that a crash
 * in a caller or in the privileged execution path cannot kill the main app
 * process.
 *
 * ### Thread safety
 * Binder calls arrive on binder pool threads. All suspend work is dispatched
 * through [serviceScope] backed by [SupervisorJob]; failures in one call do
 * not cancel others. [runBlocking] bridges the binder-thread/coroutine boundary
 * for the synchronous AIDL return values expected by callers.
 */
class OmniCoreService : Service() {

    companion object {
        private const val TAG = "OmniCoreService"

        /** Custom signature-level permission that guards all IPC methods. */
        const val PERMISSION_CONTROL_CORE = "com.omnidev.permission.CONTROL_CORE"
    }

    private val serviceJob = SupervisorJob()
    private val exceptionHandler = CoroutineExceptionHandler { _, t ->
        Log.e(TAG, "Unhandled coroutine exception", t)
    }
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob + exceptionHandler)

    // ─────────────────────────────────────────────────────────────────────
    // Binder implementation
    // ─────────────────────────────────────────────────────────────────────

    private val binder = object : IOmniCoreInterface.Stub() {

        /**
         * Execute a privileged shell command.
         *
         * Blocks the calling binder thread until the command completes or an
         * error is returned. [DeadObjectException] from nested IPC calls inside
         * [PrivilegedExecutionManager] is caught and surfaced as `false`.
         */
        override fun executeSystemCommand(command: String): Boolean {
            enforceCorePermission()
            return runBlocking(Dispatchers.IO) {
                runCatching {
                    val result = PrivilegedExecutionManager.executeCommand(command)
                    result.isSuccess
                }.getOrElse { t ->
                    when (t) {
                        is DeadObjectException, is RemoteException -> {
                            Log.w(TAG, "IPC transport error in executeSystemCommand: ${t.message}")
                            false
                        }
                        else -> {
                            Log.e(TAG, "executeSystemCommand failed", t)
                            false
                        }
                    }
                }
            }
        }

        /**
         * Return a JSON snapshot of the current device state.
         *
         * Never throws across the binder boundary; errors produce an inline
         * JSON error payload so callers always receive a valid string.
         */
        override fun getDeviceState(): String {
            enforceCorePermission()
            return runBlocking(Dispatchers.IO) {
                runCatching {
                    val snapshot = PrivilegedExecutionManager.getDeviceState(applicationContext)
                    snapshot.toJson()
                }.getOrElse { t ->
                    when (t) {
                        is DeadObjectException, is RemoteException -> {
                            Log.w(TAG, "IPC transport error in getDeviceState: ${t.message}")
                            "{\"error\":\"IPC transport failure\"}"
                        }
                        else -> {
                            Log.e(TAG, "getDeviceState failed", t)
                            "{\"error\":\"${t.message.jsonEscape()}\"}"
                        }
                    }
                }
            }
        }

        /**
         * Inject a [KeyEvent] via Shizuku's `input keyevent` shell command.
         *
         * This is fire-and-forget from the caller's perspective (one-way
         * semantics); the actual dispatch is done asynchronously on [serviceScope].
         */
        override fun injectInputEvent(event: KeyEvent?) {
            enforceCorePermission()
            if (event == null) {
                Log.w(TAG, "injectInputEvent: received null KeyEvent — ignoring")
                return
            }
            serviceScope.launch {
                runCatching {
                    // Translate to `input keyevent <keycode>` shell command.
                    val keyCode = event.keyCode
                    PrivilegedExecutionManager.executeCommand("input keyevent $keyCode")
                        .onFailure { t -> Log.e(TAG, "injectInputEvent dispatch failed", t) }
                }.onFailure { t ->
                    Log.e(TAG, "injectInputEvent unexpected error", t)
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Service lifecycle
    // ─────────────────────────────────────────────────────────────────────

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        Log.d(TAG, "OmniCoreService destroyed — coroutine scope cancelled")
    }

    // ─────────────────────────────────────────────────────────────────────
    // Permission helpers
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Throws [SecurityException] if the remote caller does not hold
     * [PERMISSION_CONTROL_CORE]. Must be called at the top of every AIDL method.
     */
    private fun enforceCorePermission() {
        val callerUid = Binder.getCallingUid()
        val check = checkPermission(PERMISSION_CONTROL_CORE, Binder.getCallingPid(), callerUid)
        if (check != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException(
                "Caller UID $callerUid does not hold $PERMISSION_CONTROL_CORE"
            )
        }
    }
}

/** Escapes special characters for embedding a string inside a JSON string literal. */
private fun String?.jsonEscape(): String = (this ?: "")
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
    .replace("\n", "\\n")
    .replace("\r", "\\r")
    .replace("\t", "\\t")
