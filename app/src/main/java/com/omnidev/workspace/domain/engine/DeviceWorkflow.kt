package com.omnidev.workspace.domain.engine

import com.omnidev.workspace.domain.engine.completion.EvidenceRef

/** Independent receipts for commonly combined device requests. Inputs never grant tool access. */
internal object DeviceWorkflow {
    private val sequence = Regex("""(?i)\s+(?:(?:and\s+)?then|وبعدين|و\s+بعدين|بعدين|وبعدها|ثم)\s+""")
    private val action = Regex("""(?i)\b(open|search|read|summari[sz]e|find|launch|send|write|check|set|schedule)\b|افتح|تفتح|دور|تدور|تبحث|ظبط|اضبط|ضبط|ابحث|تلخص|لخص|اقرأ|شوف|ابعت|اكتب""")
    private val search = Regex("""(?i)\b(search|find|look for)\b|(?:تدور|دور|ابحث|تبحث)""")
    private val youtube = Regex("""(?i)youtube|يوتيوب""")
    private val launch = Regex("""(?i)(?:\b(?:open|launch)\s+(?:the\s+)?|(?:افتح|تفتح)\s+(?:ال)?)\s*(?:youtube|يوتيوب)""")
    private val notifications = Regex("""(?i)(?:summari[sz]e|read|لخص|اقرأ|اقرا)\s+(?:(?:my|the)\s+)?(?:notifications|[اإ]شعاراتي|ال[اإ]شعارات)""")
    private val actionFollowup = Regex("""(?i)(?:طب\s+)?(?:استخدمها|ابحثلي|دورلي|search now)(?:\s+يا?لا)?[.!؟ ]*""")

    private val informational = Regex("""(?i)^\s*(?:how (?:do|can|to|would|should)|what is|explain how|ازاي|إزاي|كيف|اشرحلي ازاي|اشرح لي كيف|don't|do not|متفتحش|ماتفتحش|متبحثش)\s""")

    fun orderedParts(goal: String): List<String> {
        if (informational.containsMatchIn(goal)) return emptyList()
        val parts = sequence.split(goal.trim(), limit = 10)
        return parts.takeIf { it.size > 1 && it.all(action::containsMatchIn) }.orEmpty()
    }

    fun notificationsFirst(goal: String) = orderedParts(goal).firstOrNull()?.let(notifications::containsMatchIn) == true
    fun searchContinuation(previous: String): String {
        val match = search.findAll(previous).lastOrNull() ?: return previous
        return "Search YouTube " + previous.substring(match.range.last + 1).trim()
    }

    fun youtubeSearch(goal: String) = !informational.containsMatchIn(goal) && youtube.containsMatchIn(goal) && search.containsMatchIn(goal)
    private val screenshotRequest = Regex("""(?i)(?:take|capture|inspect|خد|تاخد|صور)\s+(?:a\s+)?(?:screenshot|screen shot|اسكرين شوت|سكرين شوت|لقطة شاشة)""")
    fun captureRequested(goal: String) = screenshotRequest.containsMatchIn(goal)
    private val alarmRequest = Regex("""(?i)\b(?:set|schedule)\s+(?:(?:an?|the|my)\s+)?alarm\b|(?:ظبط|اضبط|ضبط)\s+(?:ال)?منبه""")
    private val webRequest = Regex("""(?i)(?:\bsearch\s+(?:(?:on|in)\s+)?(?:the\s+)?(?:google|web|internet)\b|(?:ابحث|تبحث|دور)\s+(?:في|على|علي)\s+(?:جوجل|الويب|النت)(?=\s|\p{P}|$))""")
    fun needsAlarm(goal: String) = !informational.containsMatchIn(goal) && alarmRequest.containsMatchIn(goal)
    fun needsWeb(goal: String) = !informational.containsMatchIn(goal) && webRequest.containsMatchIn(goal)
    fun tracked(goal: String) = !informational.containsMatchIn(goal) &&
        (SpotifyPlayback.requested(goal) != null || needsAlarm(goal) || needsWeb(goal) || youtubeSearch(goal) || notifications.containsMatchIn(goal) || captureRequested(goal))
    fun followup(goal: String) = actionFollowup.matches(goal.trim()) ||
        goal.trim().substringAfterLast('،').trim().let { actionFollowup.matches(it) }

    fun orderError(goal: String, evidence: List<EvidenceRef>, calls: List<com.omnidev.workspace.data.model.ToolCall>): String? {
        if (SpotifyPlayback.requested(goal) != null && calls.any {
                it.name == "media_control" && it.arguments["action"]?.lowercase() in setOf("play", "toggle", "next", "previous")
            }) return "A generic media command cannot select the requested song. Use the Spotify search UI and verify with media_control info."
        val parts = orderedParts(goal)
        if (parts.isEmpty()) return null
        val attempted = evidence.filter { it.outcome != RunEvidenceLedger.Outcome.SKIPPED }
        val alarm = calls.any { it.name == "planner_tool" && it.arguments["action"] == "alarm" }
        if (notificationsFirst(goal) && attempted.none { it.tool == "read_notifications" && it.target in setOf("", "operation=read") } &&
            (alarm || calls.any { it.name in setOf("web_search", "web_search_deep", "web_scraper") ||
                it.name == "app_manager_tool" && it.arguments["action"] == "launch_app" ||
                it.name == "social_media_video" && it.arguments["action"] == "search_youtube" }))
            return "Nothing in this batch ran. Read notification contents first with read_notifications operation=read, or report the blocker."
        val webStep = parts.indexOfFirst(::needsWeb)
        if (alarm && webStep >= 0 && parts.indexOfFirst(::needsAlarm) > webStep &&
            attempted.none { it.tool in setOf("web_search", "web_search_deep") })
            return "Nothing in this batch ran. Perform the requested web search before sending the alarm."
        return null
    }

    fun missing(goal: String, evidence: List<EvidenceRef>): List<String> {
        if (!tracked(goal)) return emptyList()
        val ok = evidence.filter { it.outcome == RunEvidenceLedger.Outcome.OK }
        return buildList {
            if (SpotifyPlayback.requested(goal) != null && ok.none { it.tool == "media_control" && "action=info" in it.target })
                add("Search for the requested title and artist in Spotify with semantic_ui, then verify playback with media_control info.")
            if (captureRequested(goal) && ok.none { it.tool == "screenshot_tool" || it.tool == "visual_inspector" })
                add("Capture the requested screen with a permitted tool, or report the capture blocker.")
            if (notifications.containsMatchIn(goal) && ok.none { it.tool == "read_notifications" && it.target in setOf("", "operation=read") })
                add("Read the requested notifications and summarize the returned alerts.")
            if (needsWeb(goal) && ok.none { it.tool in setOf("web_search", "web_search_deep") })
                add("Use web_search with the requested topic. Read relevant results and report the actual sources.")
            if (needsAlarm(goal) && ok.none { it.tool == "planner_tool" && "action=alarm" in it.target.split(' ') })
                add("Request the alarm with planner_tool action=alarm and localTime in HH:mm. Report the clock app's actual response.")
            if (launch.containsMatchIn(goal) && ok.none { it.tool == "app_manager_tool" &&
                    it.target.split(' ').containsAll(listOf("action=launch_app", "packageName=com.google.android.youtube")) })
                add("Open the requested YouTube app.")
            if (youtubeSearch(goal) && ok.none { it.tool == "social_media_video" &&
                    "action=search_youtube" in it.target.split(' ') })
                add("Use social_media_video search_youtube with the requested topic and cite a returned video. Report incomplete if results are unavailable.")
        }
    }
}
