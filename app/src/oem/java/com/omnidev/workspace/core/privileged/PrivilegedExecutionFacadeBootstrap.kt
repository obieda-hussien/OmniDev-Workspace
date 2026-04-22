package com.omnidev.workspace.core.privileged

import android.util.Log
import com.omnidev.workspace.core.policy.ConfirmationKind
import com.omnidev.workspace.core.policy.OmniAuditLog
import com.omnidev.workspace.core.policy.TierPolicyHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * OEM flavor: privileged execution via `Runtime.exec()` directly.
 *
 * The OEM APK is signed with the platform key and runs under
 * `android.uid.system`, so `Runtime.exec()` commands execute with system
 * privileges without needing Shizuku or `su`.
 *
 * Every execution is:
 *   1. Gated by [com.omnidev.workspace.core.policy.TierPolicy.allowSystemIntegration].
 *   2. Audit-logged via [OmniAuditLog] (auto-approved = true, because OEM is zero-click).
 *   3. Time-bounded by the caller's `timeoutMs`.
 */
object PrivilegedExecutionFacadeBootstrap {
    private const val TAG = "OemPrivilegedExec"
    private const val MAX_OUTPUT_CHARS = 16_000

    fun install() {
        PrivilegedExecutionFacadeHolder.install(
            object : PrivilegedExecutionFacade {
                override fun isAvailable(): Boolean =
                    TierPolicyHolder.current.allowSystemIntegration

                override suspend fun execute(command: String, timeoutMs: Long): PrivilegedResult {
                    val policy = TierPolicyHolder.current
                    if (!policy.allowSystemIntegration) {
                        return PrivilegedResult.Denied(
                            "OEM system-integration capability is not enabled (tier=${policy.tier})."
                        )
                    }

                    // OEM is zero-click: log, then execute.
                    OmniAuditLog.record(
                        tier = policy.tier,
                        autoApproved = true,
                        kind = ConfirmationKind.SHIZUKU_COMMAND,
                        preview = "[OEM system-uid] $command"
                    )

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
                                Log.w(TAG, "exec failed: ${t.message}", t)
                                PrivilegedResult.Failure(t.message ?: "exec failure")
                            }
                        } ?: PrivilegedResult.Failure("Command timed out after ${timeoutMs}ms.")
                    }
                }
            }
        )
    }
}
