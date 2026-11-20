package com.omnidev.workspace.domain.engine

/** User-supplied remote identity is context, never proof of a local checkout or backend readiness. */
object ExecutionEnvironmentContext {
    private val githubUrl = Regex("(?i)https?://github\\.com/([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+)")
    private val repository = Regex("(?<![\\p{L}\\p{N}_./-])([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+)(?![\\p{L}\\p{N}_./-])")
    private val repoTerms = Regex("(?i)github|repository|repo\\b|جيتهوب|جيتهاب|جيت هاب|ريبو|مستودع")

    private val productName = Regex("""(?i)\bomni[ -]*dev[ -]*workspace\b""")
    private val rootReadme = Regex("""(?i)(?<![/\w.-])readme(?:\.md)?(?![/\w.-])""")
    private val readRequest = Regex("""(?i)explain|summari[sz]e|inspect|review|\bread\b|شرح|اشرح|تشرح|لخص|اقرا|اقرأ|شوف""")
    private val localRequest = Regex("""(?i)\blocal\b|محلي|ملفاتي|على جهازي""")
    private val continuation = Regex("""(?i)(?:ابد[أءا]|ابدئ|يا?لا|كمل|كمّل|continue|go ahead|start)(?: يا?لا)?[.!؟ ]*""")

    fun effectiveObjective(current: String, history: List<com.omnidev.workspace.data.model.ChatMessage>): String {
        if (DeviceWorkflow.followup(current)) {
            val previous = history.asReversed().filter { it.role == com.omnidev.workspace.data.model.MessageRole.USER }
                .takeWhile { DeviceWorkflow.followup(it.content) || DeviceWorkflow.youtubeSearch(it.content) }
                .firstOrNull { DeviceWorkflow.youtubeSearch(it.content) }?.content
            if (previous != null) return DeviceWorkflow.searchContinuation(previous)
        }
        if (!continuation.matches(current.trim())) return current
        val prior = history.lastOrNull { it.role == com.omnidev.workspace.data.model.MessageRole.USER &&
            !continuation.matches(it.content.trim()) }?.content ?: return current
        return if (readmeRepository(prior) != null) prior else current
    }

    fun readmeRepository(objective: String): String? {
        if (!readRequest.containsMatchIn(objective)) return null
        if (!rootReadme.containsMatchIn(objective) || localRequest.containsMatchIn(objective)) return null
        val named = remoteRepository(objective)
            ?: repository.findAll(objective).map { it.groupValues[1] }.lastOrNull { productName.matches(it.substringAfter('/')) }
        if (named != null) return named.removeSuffix(".git")
        return if (productName.containsMatchIn(objective)) "obieda-hussien/OmniDev-Workspace" else null
    }

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
        return "git" in text && ("not found" in text || "inaccessible" in text ||
            "tool_dependency_missing" in text && "git is unavailable" in text) &&
            ("127" in text || "git:" in text)
    }

    fun missingGitWorktree(observation: String?): Boolean {
        val text = observation.orEmpty().lowercase()
        return "not a git repository" in text || "git_worktree_required" in text
    }

    fun priorityNames(objective: String, observation: String?): List<String> = when {
        DeviceWorkflow.captureRequested(objective) -> listOf("visual_inspector", "semantic_ui", "screenshot_tool")
        DeviceWorkflow.youtubeSearch(objective) -> listOfNotNull("read_notifications".takeIf { DeviceWorkflow.notificationsFirst(objective) }, "social_media_video", "app_manager_tool", "semantic_ui")
        readmeRepository(objective) != null || remoteRepository(objective) != null -> listOf("github_manager")
        missingGit(observation) -> listOf("git_manager", "agent_runtime")
        else -> emptyList()
    }

    fun guidance(objective: String, observation: String? = null): String = buildString {
        if (DeviceWorkflow.youtubeSearch(objective)) append("Search using the requested terms with social_media_video(action=search_youtube, query=...). Recommend a returned title using known preferences, or state your assumption. If the user wants results on screen, inspect semantic_ui dump_tree for a localized search icon and verify the results screen. When an icon text lookup fails, inspect the current tree and its content descriptions before retrying. For requested visual inspection use visual_inspector if permitted; screenshot_tool only saves an image. If a step fails, return available results including the notification summary and specify the unfinished step. ")
        readmeRepository(objective)?.let { repo ->
            append("README source: use github_manager(action=read_file, repo=$repo, path=README.md). Read further lines if truncated. Explain the returned content in the user's language. Listing repositories or searching memory cannot verify the file. ")
        }
        remoteRepository(objective)?.let {
            append("REMOTE REPOSITORY: $it is a GitHub identity, not a verified checkout in the Android workspace. ")
            append("When exposed, use github_manager get_repo/list_contents/read_file or api_request for exact refs, branches and repository operations, with existing authorization; otherwise report the missing permitted capability. ")
            append("Until a checkout is verified, do not substitute the default local folder or local git for a remote read. ")
            append("Local builds/tests require an observed checkout and a ready developer runtime; otherwise use an authorized CI/IDE route or report the blocker. ")
        }
        if (missingGit(observation)) append("The observed shell has no git executable (exit 127); this is not a missing repository or evidence that root is needed. Use the available GitHub API for remote work or inspect git_manager/agent_runtime environment capabilities for local work. Do not retry the same shell or install packages without checking the actual runtime. ")
        if (missingGitWorktree(observation)) append("The observed Git diagnostic identifies a missing local worktree, NOT an absent remote repository. Never rerun git_status without verifying a local checkout and cwd. Use permitted github_manager for remote reads; for local builds verify the checkout or report the missing environment. ")
    }
}
