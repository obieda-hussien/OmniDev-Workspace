package com.omnidev.workspace.core.privileged

/**
 * NORM flavor: no privileged execution. Norm includes Accessibility + Terminal
 * + Git management, but NEVER root / Shizuku. Emulator-style Termux execution
 * is handled separately via TermuxEnvironmentBridge (not privileged).
 */
object PrivilegedExecutionFacadeBootstrap {
    fun install() {
        PrivilegedExecutionFacadeHolder.install(
            object : PrivilegedExecutionFacade {
                override fun isAvailable(): Boolean = false
                override suspend fun execute(command: String, timeoutMs: Long): PrivilegedResult =
                    PrivilegedResult.Denied(
                        "Privileged execution requires the PRO (Shizuku) or OEM (system-uid) build."
                    )
            }
        )
    }
}
