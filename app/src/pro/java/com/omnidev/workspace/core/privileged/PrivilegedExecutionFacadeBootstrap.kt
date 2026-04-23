package com.omnidev.workspace.core.privileged

import com.omnidev.workspace.data.ipc.PrivilegedExecutionManager

/**
 * PRO flavor: full privileged execution via the existing Shizuku →
 * rish → root fallback chain in [PrivilegedExecutionManager].
 *
 * The implementation here is a THIN ADAPTER — all runtime logic lives in
 * PrivilegedExecutionManager (which will move to :tools:advanced in the
 * follow-up modularization PR). Once moved, this adapter stays as the
 * flavor-specific binding.
 */
object PrivilegedExecutionFacadeBootstrap {
    fun install() {
        PrivilegedExecutionFacadeHolder.install(
            object : PrivilegedExecutionFacade {
                override fun isAvailable(): Boolean =
                    PrivilegedExecutionManager.isShizukuReady() ||
                        PrivilegedExecutionManager.isRishReady() ||
                        PrivilegedExecutionManager.isRootAvailable()

                override suspend fun execute(command: String, timeoutMs: Long): PrivilegedResult {
                    val result = PrivilegedExecutionManager.executeCommand(command)
                    return result.fold(
                        onSuccess = { PrivilegedResult.Success(it) },
                        onFailure = { PrivilegedResult.Failure(it.message ?: "execution failed") }
                    )
                }
            }
        )
    }
}
