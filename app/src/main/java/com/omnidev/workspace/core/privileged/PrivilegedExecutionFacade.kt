package com.omnidev.workspace.core.privileged

/**
 * Tier-aware facade over privileged command execution.
 *
 * This interface is the seam that will eventually let us relocate the 19 files
 * currently importing [com.omnidev.workspace.data.tools.ShizukuCommandTool]
 * into a dedicated `:tools:advanced` Gradle module without touching 19 call
 * sites. Every caller depends only on this facade (in `:core`), and each flavor
 * source set provides an implementation backed by whatever privilege backend
 * is available:
 *
 *  | Flavor | Impl file                                               | Backend                          |
 *  |--------|---------------------------------------------------------|----------------------------------|
 *  | lite   | `src/lite/java/.../LitePrivilegedExecutionFacade.kt`    | Always denies.                   |
 *  | norm   | `src/norm/java/.../NormPrivilegedExecutionFacade.kt`    | Always denies.                   |
 *  | pro    | `src/pro/java/.../ProPrivilegedExecutionFacade.kt`      | Shizuku → rish → root fallback.  |
 *  | oem    | `src/oem/java/.../OemPrivilegedExecutionFacade.kt`      | android.uid.system direct call.  |
 *
 * Production callers obtain the facade via
 * [PrivilegedExecutionFacadeHolder.current] after `OmniDevApp.onCreate()` has
 * installed the correct flavor-specific implementation.
 *
 * Every call is subject to [com.omnidev.workspace.core.policy.TierPolicy]
 * capability checks (`allowShizuku`, `allowRoot`) at the implementation level;
 * callers do not need to gate themselves.
 */
interface PrivilegedExecutionFacade {

    /** Whether the current tier + runtime state can execute privileged commands. */
    fun isAvailable(): Boolean

    /**
     * Execute [command] via the best available backend.
     *
     * @return a [PrivilegedResult] describing the outcome. Never throws.
     */
    suspend fun execute(command: String, timeoutMs: Long = 30_000L): PrivilegedResult
}

/** Outcome of a privileged execution attempt. */
sealed class PrivilegedResult {
    /** Command ran and exited 0. */
    data class Success(val output: String) : PrivilegedResult()

    /** Command ran but exited non-zero (or produced an error stream). */
    data class Partial(val output: String, val error: String, val exitCode: Int) : PrivilegedResult()

    /** Command never ran — tier or runtime denied it. */
    data class Denied(val reason: String) : PrivilegedResult()

    /** Unexpected failure. */
    data class Failure(val error: String) : PrivilegedResult()
}
