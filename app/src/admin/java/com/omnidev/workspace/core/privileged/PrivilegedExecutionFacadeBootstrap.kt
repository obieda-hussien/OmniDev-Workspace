package com.omnidev.workspace.core.privileged

import android.util.Log
import com.omnidev.workspace.core.policy.ConfirmationKind
import com.omnidev.workspace.core.policy.OmniAuditLog
import com.omnidev.workspace.core.policy.TierPolicyHolder
import com.omnidev.workspace.data.ipc.PrivilegedExecutionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * ADMIN flavor: the broadest privileged-execution facade in the project.
 *
 * Execution strategy (tried in order, first available wins):
 *   1. Shizuku / rish via [PrivilegedExecutionManager] (the normal PRO path).
 *   2. Direct `Runtime.exec()` fallback (identical to the OEM path) — useful on a
 *      platform-signed admin install or on an emulator where `sh` already runs
 *      with elevated privilege.
 *
 * Every attempt is written to [OmniAuditLog] (auto-approved = true) so the
 * full timeline of privileged actions is recoverable after a test run, even
 * though no Compose dialog is shown.
 *
 * This file is the ADMIN-tier master-key analogue of:
 *   * `src/pro/java/.../PrivilegedExecutionFacadeBootstrap.kt` (Shizuku chain), and
 *   * `src/oem/java/.../PrivilegedExecutionFacadeBootstrap.kt` (Runtime.exec).
 */
object PrivilegedExecutionFacadeBootstrap {
    private const val TAG = "AdminPrivilegedExec"
    private const val MAX_OUTPUT_CHARS = 16_000

    fun install() {
        PrivilegedExecutionFacadeHolder.install(
            object : PrivilegedExecutionFacade {
                override fun isAvailable(): Boolean =
                    // Admin is always considered "available": it will pick whatever
                    // path works at runtime. We still check the managers so tests
                    // can assert on the specific underlying backend.
                    // ADMIN has its own direct platform-shell fallback below, so availability
                    // does not require probing root or any external privilege backend.
                    true

                override suspend fun execute(command: String, timeoutMs: Long): PrivilegedResult {
                    val policy = TierPolicyHolder.current

                    // Master-key audit — every privileged command is logged.
                    OmniAuditLog.record(
                        tier = policy.tier,
                        autoApproved = true,
                        kind = ConfirmationKind.SHIZUKU_COMMAND,
                        preview = "[ADMIN master-key] $command"
                    )

                    // 1) Preferred path — Shizuku / rish via the shared manager.
                    if (
                        PrivilegedExecutionManager.isShizukuReady() ||
                        PrivilegedExecutionManager.isRishReady()
                    ) {
                        val viaManager = PrivilegedExecutionManager.executeCommand(command)
                        viaManager.fold(
                            onSuccess = { return PrivilegedResult.Success(it) },
                            onFailure = { t ->
                                Log.w(TAG, "Shizuku/rish chain failed, falling back to ADMIN Runtime.exec: ${t.message}")
                            }
                        )
                    }

                    // 2) Fallback — direct Runtime.exec (OEM-style).
                    return withContext(Dispatchers.IO) {
                        withTimeoutOrNull(timeoutMs) {
                            runCatching {
                                val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
                                val out = BufferedReader(InputStreamReader(proc.inputStream))
                                    .readText()
                                    .take(MAX_OUTPUT_CHARS)
                                val err = BufferedReader(InputStreamReader(proc.errorStream))
                                    .readText()
                                    .take(MAX_OUTPUT_CHARS)
                                val exit = proc.waitFor()
                                when {
                                    exit == 0 -> PrivilegedResult.Success(out)
                                    else -> PrivilegedResult.Partial(out, err, exit)
                                }
                            }.getOrElse { t ->
                                Log.w(TAG, "Admin Runtime.exec failed: ${t.message}", t)
                                PrivilegedResult.Failure(t.message ?: "admin exec failure")
                            }
                        } ?: PrivilegedResult.Failure("Admin command timed out after ${timeoutMs}ms.")
                    }
                }
            }
        )
    }
}
