package com.omnidev.workspace.data.ipc

/**
 * Functional health of the Termux-side rish runtime.
 *
 * IMPORTANT: READY means an actual `rish -c id` smoke-test succeeded. File existence,
 * a valid DEX, or a live Shizuku binder are only prerequisites and must never be
 * promoted to READY by themselves.
 */
data class RishRuntimeHealth(
    val state: State,
    val summary: String,
    val details: String = "",
    val smokeOutput: String = "",
    val checkedAtMs: Long = System.currentTimeMillis()
) {
    val ready: Boolean get() = state == State.READY

    enum class State {
        UNKNOWN,
        READY,
        TERMUX_UNAVAILABLE,
        SHIZUKU_UNAVAILABLE,
        SHIZUKU_PERMISSION_REQUIRED,
        DEX_UNAVAILABLE,
        TERMUX_LAYOUT_BROKEN,
        COMMAND_NOT_FOUND,
        NATIVE_LIBRARY_LOAD_FAILURE,
        CROSS_SANDBOX_PERMISSION_FAILURE,
        DEX_PERMISSION_FAILURE,
        TIMEOUT,
        EXECUTION_FAILED
    }
}

/** Pure classifier kept Android-free so it is easy to unit-test. */
object RishFailureClassifier {

    fun classify(
        exitCode: Int,
        output: String,
        transportSucceeded: Boolean = true
    ): RishRuntimeHealth.State {
        val text = output.lowercase()

        if (!transportSucceeded) {
            return if (text.contains("termux") || text.contains("run_command") || text.contains("allow-external-apps")) {
                RishRuntimeHealth.State.TERMUX_UNAVAILABLE
            } else {
                RishRuntimeHealth.State.EXECUTION_FAILED
            }
        }

        if (exitCode == 0 && looksLikePrivilegedId(text)) {
            return RishRuntimeHealth.State.READY
        }

        return when {
            text.contains("couldn't find \"librish.so\"") ||
                text.contains("could not find \"librish.so\"") ||
                (text.contains("unsatisfiedlinkerror") && text.contains("librish.so")) ->
                RishRuntimeHealth.State.NATIVE_LIBRARY_LOAD_FAILURE

            text.contains("rish: command not found") ||
                text.contains("rish: not found") ||
                text.contains("command not found: rish") ->
                RishRuntimeHealth.State.COMMAND_NOT_FOUND

            (text.contains("cannot find") && text.contains("rish_shizuku.dex")) ||
                text.contains("rish_shizuku.dex is unavailable") ->
                RishRuntimeHealth.State.DEX_UNAVAILABLE

            text.contains("app_process cannot load writable dex") ||
                (text.contains("cannot remove the write permission") && text.contains("dex")) ->
                RishRuntimeHealth.State.DEX_PERMISSION_FAILURE

            (text.contains("/data/user/0/com.omnidev.workspace") && text.contains("permission denied")) ||
                (text.contains("/data/data/com.omnidev.workspace") && text.contains("permission denied")) ->
                RishRuntimeHealth.State.CROSS_SANDBOX_PERMISSION_FAILURE

            (text.contains("/bin/rish") && text.contains("is a directory")) ||
                text.contains("rish path is a directory") ||
                text.contains("omnidev_rish_layout_error") ->
                RishRuntimeHealth.State.TERMUX_LAYOUT_BROKEN

            text.contains("shizuku") && (
                text.contains("permission") || text.contains("unauthorized") || text.contains("denied")
            ) -> RishRuntimeHealth.State.SHIZUKU_PERMISSION_REQUIRED

            text.contains("shizuku") && (
                text.contains("not running") || text.contains("unavailable") || text.contains("binder")
            ) -> RishRuntimeHealth.State.SHIZUKU_UNAVAILABLE

            text.contains("timeout") || text.contains("timed out") ->
                RishRuntimeHealth.State.TIMEOUT

            else -> RishRuntimeHealth.State.EXECUTION_FAILED
        }
    }

    fun looksLikePrivilegedId(output: String): Boolean {
        val text = output.lowercase()
        return text.contains("uid=2000(shell)") ||
            text.contains("uid=0(root)") ||
            text.lineSequence().any { it.trim() == "shell" || it.trim() == "root" }
    }

    fun remediation(state: RishRuntimeHealth.State): String = when (state) {
        RishRuntimeHealth.State.READY -> "rish is healthy."
        RishRuntimeHealth.State.UNKNOWN -> "Run privileged_tool action=rish_setup to install/probe rish."
        RishRuntimeHealth.State.TERMUX_UNAVAILABLE ->
            "Fix Termux RunCommandService first: grant RUN_COMMAND and enable allow-external-apps=true."
        RishRuntimeHealth.State.SHIZUKU_UNAVAILABLE ->
            "Start Shizuku, then retry. Programmatic privileged commands can use the UserService backend once it is healthy."
        RishRuntimeHealth.State.SHIZUKU_PERMISSION_REQUIRED ->
            "Grant OmniDev permission in Shizuku, then retry."
        RishRuntimeHealth.State.DEX_UNAVAILABLE ->
            "Open Shizuku → Use in terminal apps/export rish, or reinstall Shizuku so rish_shizuku.dex is available."
        RishRuntimeHealth.State.TERMUX_LAYOUT_BROKEN ->
            "Re-run rish_setup. OmniDev will repair \$PREFIX/bin/rish into a symlink and place script+dex under \$PREFIX/opt/omnidev-rish."
        RishRuntimeHealth.State.COMMAND_NOT_FOUND ->
            "Re-run rish_setup to create the Termux executable/symlink."
        RishRuntimeHealth.State.NATIVE_LIBRARY_LOAD_FAILURE ->
            "Known Shizuku/rish native-loader failure: do NOT copy librish.so or patch LD_LIBRARY_PATH/java.library.path. " +
                "Use Shizuku UserService for OmniDev privileged commands; for terminal rish, re-export from a compatible Shizuku build."
        RishRuntimeHealth.State.CROSS_SANDBOX_PERMISSION_FAILURE ->
            "Do not execute OmniDev app-private rish files from Termux. Re-run rish_setup so files are installed inside Termux private storage."
        RishRuntimeHealth.State.DEX_PERMISSION_FAILURE ->
            "Re-run rish_setup. OmniDev installs rish_shizuku.dex inside Termux and chmods it read-only for Android 14+."
        RishRuntimeHealth.State.TIMEOUT -> "Restart Shizuku and retry the rish smoke test once."
        RishRuntimeHealth.State.EXECUTION_FAILED ->
            "Inspect the rish smoke-test output. Do not mutate Shizuku native libraries automatically."
    }
}
