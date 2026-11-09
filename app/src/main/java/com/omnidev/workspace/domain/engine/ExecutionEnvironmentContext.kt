package com.omnidev.workspace.domain.engine

/** User-supplied remote identity is context, never proof of a local checkout or backend readiness. */
object ExecutionEnvironmentContext {
    private val githubUrl = Regex("(?i)https?://github\\.com/([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+)")
    private val repository = Regex("(?<![\\p{L}\\p{N}_./-])([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+)(?![\\p{L}\\p{N}_./-])")
    private val repoTerms = Regex("(?i)github|repository|repo\\b|جيتهوب|جيتهاب|جيت هاب|ريبو|مستودع")

    fun remoteRepository(objective: String): String? {
        val input = objective.take(16_000)
        val urls = githubUrl.findAll(input).toList()
        val localRoots = setOf("app", "src", "docs", "build", "gradle", "storage", "sdcard", "tmp", "system", "data", "vendor", "mnt", "home", "test", "tests")
        val ids = if (repoTerms.containsMatchIn(input)) repository.findAll(input).filter {
            it.groupValues[1].substringBefore('/') !in localRoots &&
                it.groupValues[1].substringAfter('/').let { name -> !name.contains('.') || name.endsWith(".git") }
        }.toList() else emptyList()
        return (urls + ids).maxByOrNull { it.range.first }?.groupValues?.get(1)?.removeSuffix(".git")
    }

    fun missingGit(observation: String?): Boolean {
        val text = observation.orEmpty().lowercase()
        return "git" in text && ("not found" in text || "inaccessible" in text) &&
            ("127" in text || "git:" in text)
    }

    fun priorityNames(objective: String, observation: String?): List<String> = when {
        remoteRepository(objective) != null -> listOf("github_manager")
        missingGit(observation) -> listOf("git_manager", "agent_runtime")
        else -> emptyList()
    }

    fun guidance(objective: String, observation: String? = null): String = buildString {
        remoteRepository(objective)?.let {
            append("REMOTE REPOSITORY: $it is a GitHub identity, not a verified checkout in the Android workspace. ")
            append("When exposed, use github_manager get_repo/list_contents/read_file or api_request for exact refs, branches and repository operations, with existing authorization; otherwise report the missing permitted capability. ")
            append("Until a checkout is verified, do not substitute the default local folder or local git for a remote read. ")
            append("Local builds/tests require an observed checkout and a ready developer runtime; otherwise use an authorized CI/IDE route or report the blocker. ")
        }
        if (missingGit(observation)) append("The observed shell has no git executable (exit 127); this is not a missing repository or evidence that root is needed. Use the available GitHub API for remote work or inspect git_manager/agent_runtime environment capabilities for local work. Do not retry the same shell or install packages without checking the actual runtime. ")
    }
}
