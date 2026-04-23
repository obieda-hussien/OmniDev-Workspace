package com.omnidev.workspace.core.tools

/**
 * =====================================================================
 *  ToolContribution — pluggable tool registration contract.
 * =====================================================================
 *
 *  This is the *seam* that will let us physically split the tool stack
 *  into independent Gradle modules (`:tools:lite`, `:tools:standard`,
 *  `:tools:advanced`) in a follow-up PR without touching call sites.
 *
 *  Design:
 *    * Each module exposes ONE `ToolContribution` implementation that
 *      registers its tools with a [ToolContributionRegistrar].
 *    * The `:app` module discovers contributions via either:
 *        (a) flavor-specific source sets that provide a single
 *            `ToolContributions.active()` list, OR
 *        (b) a ServiceLoader wired in `META-INF/services` (future).
 *    * `CompositeToolManager` will iterate contributions and build its
 *      internal tool map instead of being a 1473-line god class.
 *
 *  Why this seam exists *before* the physical split:
 *    Refactoring 60+ tools in a single PR is too risky. By shipping the
 *    contract + flavor-aware discovery today, we can migrate tools one
 *    category at a time behind green CI. See MODULARIZATION_ROADMAP.md.
 *
 *  This file is intentionally behaviour-free: it only defines contracts.
 *  The runtime is still driven by `CompositeToolManager` + `TierToolGate`.
 */
interface ToolContribution {
    /** Stable id, e.g. "tools.lite", "tools.standard", "tools.advanced". */
    val id: String

    /**
     * Minimum tier required for this contribution to load at runtime.
     * `:app` filters contributions against [com.omnidev.workspace.core.policy.TierPolicy].
     */
    val minTier: Tier

    /**
     * Register this contribution's tools with the supplied [registrar].
     * Implementations must be idempotent and must NOT perform I/O.
     */
    fun register(registrar: ToolContributionRegistrar)
}

/**
 * Declarative tier enum used only by the contribution layer.
 * Mirrors `BuildConfig.TIER` but keeps `:core:*` free of `BuildConfig`.
 */
enum class Tier {
    LITE,    // B2C free, Play-Store safe
    NORM,    // B2C standard developer
    PRO,     // B2C premium god-mode
    OEM      // B2B system-level integration
}

/**
 * Handed to each [ToolContribution] during registration. The concrete
 * implementation in `:app` is a thin adapter around the existing
 * `CompositeToolManager` tool map.
 */
interface ToolContributionRegistrar {
    /**
     * Register a tool with [name]. [block] is the existing execution
     * lambda shape used by CompositeToolManager:
     *   suspend (JSONObject, String?) -> String
     *
     * We keep it as `Any` here to avoid dragging `org.json.JSONObject`
     * into `:core:*` — the adapter in `:app` enforces the real type.
     */
    fun register(name: String, executor: Any)
}
