package com.omnidev.workspace.data.tools

/** Platform-independent routing for one setup session. Counts come from the installed APK. */
internal object DeviceAccessSetupPlan {
    enum class Route { RUNTIME, DEVELOPMENT, SPECIAL, ENTITLEMENT, INSTALL, UNSUPPORTED }
    data class Access(
        val key: String,
        val title: String,
        val detail: String,
        val status: String,
        val route: Route,
        val declaration: Boolean = true
    ) {
        val granted get() = status == "GRANTED"
        val actionable get() = route in setOf(Route.RUNTIME, Route.DEVELOPMENT, Route.SPECIAL) &&
            status !in setOf("NOT_SUPPORTED", "NOT_DECLARED", "TIER_BLOCKED")
    }
    data class Step(val key: String, val title: String, val permissions: List<String> = emptyList())

    fun automaticPermissions(access: List<Access>): List<String> = access.filter {
        !it.granted && it.actionable && it.route in setOf(Route.RUNTIME, Route.DEVELOPMENT)
    }.sortedBy { if (it.key in PermissionRequestPlan.staged) 1 else 0 }.map { it.key }.distinct()

    fun nextStep(access: List<Access>, attempted: Set<String>, sdk: Int): Step? {
        val pending = access.filter { !it.granted && it.actionable && it.key !in attempted }
        val foreground = PermissionRequestPlan.foregroundBatch(
            pending.filter { it.route == Route.RUNTIME }.map { it.key }, sdk
        ).filter { name -> access.any { it.key == name && it.route == Route.RUNTIME } }
        if (foreground.isNotEmpty() && "runtime_batch" !in attempted) {
            return Step("runtime_batch", "App permissions", foreground)
        }
        val granted = access.filter { it.granted }.map { it.key }.toSet()
        pending.firstOrNull {
            it.route == Route.RUNTIME && it.key in PermissionRequestPlan.staged &&
                PermissionRequestPlan.prerequisites(it.key).any { name -> name in granted }
        }?.let { return Step(it.key, it.title, listOf(it.key)) }
        // Development and signature entitlements have no Android dialog. Never loop on denials.
        return pending.firstOrNull { it.route == Route.SPECIAL && !it.declaration }
            ?.let { Step(it.key, it.title) }
    }
}
