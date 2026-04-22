package com.omnidev.workspace.core.tools

/**
 * Flavor-aware discovery point for tool contributions.
 *
 * Each flavor source set (lite/norm/pro/oem) provides its own
 * `ToolContributionsBootstrap.kt` that defines the `active()` list
 * exposed here. Until the physical module split lands we return an
 * empty list — `CompositeToolManager` + `TierToolGate` remain the
 * single source of truth at runtime.
 *
 * This shim lets call sites start depending on the contribution API
 * today so the follow-up PR that moves 60+ tools out of `:app` does
 * not have to rewrite every caller.
 */
object ToolContributions {

    /**
     * Returns the list of contributions active for the current flavor.
     * Flavor source sets override this via `ToolContributionsBootstrap`
     * once the `:tools:*` modules exist.
     */
    fun active(): List<ToolContribution> = emptyList()
}
