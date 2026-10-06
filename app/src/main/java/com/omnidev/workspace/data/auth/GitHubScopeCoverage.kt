package com.omnidev.workspace.data.auth

/** UI diagnostics only; GitHub and local operation gates remain authoritative. */
object GitHubScopeCoverage {
    fun missing(granted: String, requested: String): Set<String> {
        val available = split(granted).toMutableSet()
        if ("user" in available) available += setOf("read:user", "user:email", "user:follow")
        if ("repo" in available) available += setOf("public_repo", "repo:status", "repo_deployment", "repo:invite")
        if ("project" in available) available += "read:project"
        for (area in listOf("org", "public_key", "gpg_key", "ssh_signing_key", "repo_hook")) {
            if ("admin:$area" in available) available += setOf("write:$area", "read:$area")
            if ("write:$area" in available) available += "read:$area"
        }
        if ("write:packages" in available) available += "read:packages"
        return split(requested) - available
    }
    private fun split(raw: String) = raw.split(Regex("[,\\s]+")).filter { it.isNotBlank() }.toSet()
}
