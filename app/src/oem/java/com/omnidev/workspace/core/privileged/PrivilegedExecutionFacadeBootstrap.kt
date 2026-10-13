package com.omnidev.workspace.core.privileged

/** OEM never executes model-generated shell under a system identity. */
object PrivilegedExecutionFacadeBootstrap {
    fun install() { PrivilegedExecutionFacadeHolder.install(object : PrivilegedExecutionFacade {
        override fun isAvailable() = false
        override suspend fun execute(command: String, timeoutMs: Long): PrivilegedResult =
            PrivilegedResult.Denied("Use the typed oem_system tool; OEM has no generic system shell.")
    }) }
}
