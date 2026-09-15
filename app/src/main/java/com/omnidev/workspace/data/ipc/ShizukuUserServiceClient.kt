package com.omnidev.workspace.data.ipc

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import com.omnidev.workspace.ipc.IPrivilegedShellService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku

/** Result returned by the privileged Shizuku UserService. */
data class PrivilegedShellResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
    val uid: Int,
    val error: String? = null
) {
    val isSuccess: Boolean get() = !timedOut && error == null && exitCode == 0

    fun mergedOutput(): String = when {
        stdout.isNotBlank() && stderr.isNotBlank() -> "$stdout\n[stderr]\n$stderr"
        stdout.isNotBlank() -> stdout
        stderr.isNotBlank() -> stderr
        else -> ""
    }
}

/**
 * Process-wide client for [PrivilegedShellUserService].
 *
 * Unlike the old reflection based Shizuku.newProcess path, this uses the public
 * UserService API that Shizuku explicitly recommends for privileged app code.
 * A failed bind NEVER falls back to Runtime.exec(), because that would execute
 * under the normal app UID and create a dangerous false-success condition.
 */
object ShizukuUserServiceClient {

    private const val SERVICE_TAG = "omnidev_privileged_shell_v2"
    private const val SERVICE_VERSION = 2
    private const val BIND_TIMEOUT_MS = 12_000L

    @Volatile private var appContext: Context? = null
    @Volatile private var service: IPrivilegedShellService? = null
    @Volatile private var serviceConnection: ServiceConnection? = null

    private val bindMutex = Mutex()

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun isInitialized(): Boolean = appContext != null

    fun cachedUid(): Int? = runCatching {
        service?.takeIf { it.asBinder().isBinderAlive }?.uid
    }.getOrNull()

    suspend fun execute(command: String, timeoutMs: Long): PrivilegedShellResult =
        withContext(Dispatchers.IO) {
            require(command.isNotBlank()) { "Command is empty" }

            var lastFailure: Throwable? = null
            repeat(2) { attempt ->
                try {
                    val remote = getOrBindService()
                    val bundle = remote.execute(command, timeoutMs)
                    return@withContext PrivilegedShellResult(
                        exitCode = bundle.getInt("exitCode", -1),
                        stdout = bundle.getString("stdout").orEmpty(),
                        stderr = bundle.getString("stderr").orEmpty(),
                        timedOut = bundle.getBoolean("timedOut", false),
                        uid = bundle.getInt("uid", -1),
                        error = bundle.getString("error")
                    )
                } catch (t: Throwable) {
                    lastFailure = t
                    invalidateService()
                    if (attempt == 0 && Shizuku.pingBinder()) return@repeat
                }
            }

            throw IllegalStateException(
                "Unable to execute through Shizuku UserService: ${lastFailure?.message ?: "unknown error"}",
                lastFailure
            )
        }

    suspend fun ping(): String = withContext(Dispatchers.IO) {
        getOrBindService().ping()
    }

    private suspend fun getOrBindService(): IPrivilegedShellService {
        service?.let { cached ->
            if (runCatching { cached.asBinder().isBinderAlive }.getOrDefault(false)) return cached
        }

        return bindMutex.withLock {
            service?.let { cached ->
                if (runCatching { cached.asBinder().isBinderAlive }.getOrDefault(false)) {
                    return@withLock cached
                }
            }

            check(Shizuku.pingBinder()) { "Shizuku binder is not available" }
            check(Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                "Shizuku permission is not granted"
            }

            val context = checkNotNull(appContext) {
                "ShizukuUserServiceClient is not initialized. Call init(context) during app startup."
            }

            val deferred = CompletableDeferred<IPrivilegedShellService>()
            val args = buildServiceArgs(context)
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                    val api = IPrivilegedShellService.Stub.asInterface(binder)
                    service = api
                    serviceConnection = this
                    if (!deferred.isCompleted) deferred.complete(api)
                }

                override fun onServiceDisconnected(name: ComponentName) {
                    if (serviceConnection === this) {
                        service = null
                        serviceConnection = null
                    }
                    if (!deferred.isCompleted) {
                        deferred.completeExceptionally(
                            IllegalStateException("Shizuku UserService disconnected before bind completed")
                        )
                    }
                }
            }

            try {
                Shizuku.bindUserService(args, connection)
                val api = withTimeout(BIND_TIMEOUT_MS) { deferred.await() }
                val pong = api.ping()
                check(pong.startsWith("ok:uid=")) { "Invalid UserService handshake: $pong" }
                api
            } catch (t: Throwable) {
                runCatching { Shizuku.unbindUserService(args, connection, false) }
                invalidateService()
                throw t
            }
        }
    }

    private fun buildServiceArgs(context: Context): Shizuku.UserServiceArgs =
        Shizuku.UserServiceArgs(
            ComponentName(context.packageName, PrivilegedShellUserService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("omni_priv_shell")
            .tag(SERVICE_TAG)
            .version(SERVICE_VERSION)
            .debuggable(false)

    private fun invalidateService() {
        service = null
        serviceConnection = null
    }
}
