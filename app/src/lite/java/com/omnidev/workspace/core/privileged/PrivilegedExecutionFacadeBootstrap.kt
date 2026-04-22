package com.omnidev.workspace.core.privileged

/**
 * LITE flavor: no privileged execution at all.
 * The default deny-all facade is already installed, so this bootstrap is a no-op
 * but exists for symmetry with the other flavors.
 */
object PrivilegedExecutionFacadeBootstrap {
    fun install() {
        PrivilegedExecutionFacadeHolder.install(
            object : PrivilegedExecutionFacade {
                override fun isAvailable(): Boolean = false
                override suspend fun execute(command: String, timeoutMs: Long): PrivilegedResult =
                    PrivilegedResult.Denied(
                        "Privileged execution is disabled in the LITE (Play Store) build."
                    )
            }
        )
    }
}
