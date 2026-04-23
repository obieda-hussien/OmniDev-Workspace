package com.omnidev.workspace.core.privileged

/**
 * Process-wide holder for the active [PrivilegedExecutionFacade].
 *
 * Installed once at `OmniDevApp.onCreate()` via the flavor-specific
 * `PrivilegedExecutionFacadeBootstrap` (one per flavor source set).
 *
 * Falls back to [DefaultDenyingFacade] until installed, matching the
 * [com.omnidev.workspace.core.policy.TierPolicyHolder] pattern.
 */
object PrivilegedExecutionFacadeHolder {

    @Volatile
    private var facade: PrivilegedExecutionFacade = DefaultDenyingFacade

    val current: PrivilegedExecutionFacade get() = facade

    fun install(facade: PrivilegedExecutionFacade) {
        this.facade = facade
    }
}

/** Safety-net facade used before bootstrap and in Lite/Norm flavors. */
private object DefaultDenyingFacade : PrivilegedExecutionFacade {
    override fun isAvailable(): Boolean = false
    override suspend fun execute(command: String, timeoutMs: Long): PrivilegedResult =
        PrivilegedResult.Denied("Privileged execution is not permitted in this build tier.")
}
