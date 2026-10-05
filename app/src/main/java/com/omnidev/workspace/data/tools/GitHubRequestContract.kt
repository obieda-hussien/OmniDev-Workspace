package com.omnidev.workspace.data.tools

import java.net.URLDecoder
import java.net.URLEncoder

/** Explicit read operations and conservative compatibility for old saved api_request calls. */
object GitHubRequestContract {
    val actions = listOf("policy_status", "whoami", "get_repo", "list_repos", "list_contents", "read_file", "create_issue", "create_pull_request", "api_request")
    private val roots = setOf("repos", "user", "users", "orgs", "organizations", "repositories", "search", "graphql", "gists", "issues", "notifications", "rate_limit", "meta", "emojis", "licenses", "markdown", "feeds", "events", "teams", "projects", "app", "applications", "installation", "marketplace_listing", "enterprise", "enterprises", "networks", "octocat", "zen", "gitignore")
    private val repository = Regex("[A-Za-z0-9._-]+/[A-Za-z0-9._-]+")
    data class Read(val endpoint: String, val rawFile: Boolean = false)

    fun repo(raw: String): String = raw.trim().also {
        require(it.matches(repository) && it.split('/').none { part -> part == "." || part == ".." }) { "Expected exact owner/repo, preserving punctuation. Do not guess or remove a trailing hyphen." }
    }

    fun endpoint(raw: String, method: String): String {
        val value = raw.trim()
        require(value.isNotEmpty()) { "API endpoint is empty. Use get_repo with repo=owner/repo, or endpoint=/repos/owner/repo." }
        require(value.length <= 2048 && value.none { it.isISOControl() } && !value.contains("://") && !value.startsWith("//") && !value.contains('\\') && !value.contains('#')) { "Only a relative api.github.com path is allowed." }
        val path = value.substringBefore('?')
        path.split('/').filter { it.isNotEmpty() }.forEach { segment ->
            val decoded = URLDecoder.decode(segment, "UTF-8")
            require(decoded != "." && decoded != ".." && decoded.none { it.isISOControl() } && !decoded.contains('/') && !decoded.contains('\\') && !decoded.contains('%')) { "Invalid or encoded traversal in API path." }
        }
        val relative = value.removePrefix("/")
        val parts = relative.substringBefore('?').split('/')
        if (parts.first() !in roots) {
            require(method == "GET" && parts.size >= 2 && parts.take(2).joinToString("/").matches(repository)) { "Unknown API root. Use /repos/owner/repo/...; mutation endpoints must be explicit." }
            return "/repos/$relative"
        }
        return "/$relative"
    }

    fun read(action: String, repository: String, path: String?, ref: String?, page: Int, perPage: Int, owner: String?): Read {
        require(page in 1..10_000 && perPage in 1..100) { "page must be 1..10000 and per_page 1..100." }
        return when (action) {
            "get_repo" -> Read("/repos/${repo(repository)}")
            "list_repos" -> {
                val root = if (owner.isNullOrBlank()) "/user/repos" else {
                    require(owner.matches(Regex("[A-Za-z0-9-]+"))) { "Invalid owner login." }
                    "/users/$owner/repos"
                }
                Read("$root?per_page=$perPage&page=$page&sort=updated")
            }
            "list_contents", "read_file" -> {
                val clean = path.orEmpty().trim().trim('/')
                require(action != "read_file" || clean.isNotBlank()) { "read_file requires an exact file path." }
                require(clean.split('/').none { it == "." || it == ".." } && clean.none { it.isISOControl() } && !clean.contains('\\')) { "Invalid repository file path." }
                val encoded = clean.split('/').filter { it.isNotEmpty() }.joinToString("/") { encode(it) }
                val branch = ref?.takeIf { it.isNotBlank() }?.let { "?ref=${encode(it)}" }.orEmpty()
                Read("/repos/${repo(repository)}/contents" + if (encoded.isEmpty()) branch else "/$encoded$branch", action == "read_file")
            }
            else -> error("Not a typed read action")
        }
    }

    fun failureKey(action: String, args: Map<String, String>): String? = runCatching {
        val method = if (action == "api_request") args["method"]?.takeIf { it.isNotBlank() } ?: args["title"]?.takeIf { it.isNotBlank() } ?: "GET" else "GET"
        val path = when (action) {
            "api_request" -> endpoint(args["endpoint"] ?: args["repo"].orEmpty(), method.uppercase())
            "get_repo", "list_contents", "read_file", "list_repos" -> read(action, args["repo"].orEmpty(), args["path"], args["ref"], args["page"]?.toIntOrNull() ?: 1, args["per_page"]?.toIntOrNull() ?: 20, args["owner"]).endpoint
            "whoami" -> "/user"
            else -> return@runCatching null
        }
        method.uppercase() + " " + path // ref and page identify distinct resources/observations.
    }.getOrNull()

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
