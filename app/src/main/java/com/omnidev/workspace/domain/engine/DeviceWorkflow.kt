package com.omnidev.workspace.domain.engine

import com.omnidev.workspace.domain.engine.completion.EvidenceRef

/** Independent receipts for commonly combined device requests. Inputs never grant tool access. */
internal object DeviceWorkflow {
    private val sequence = Regex("""(?i)\s+(?:(?:and\s+)?then|وبعدين|و\s+بعدين|بعدين|وبعدها|ثم)\s+""")
    private val action = Regex("""(?i)\b(open|search|read|summari[sz]e|find|launch|send|write|check)\b|افتح|تفتح|دور|تدور|ابحث|تلخص|لخص|اقرأ|شوف|ابعت|اكتب""")
    private val search = Regex("""(?i)\b(search|find|look for)\b|(?:تدور|دور|ابحث|تبحث)""")
    private val youtube = Regex("""(?i)youtube|يوتيوب""")
    private val launch = Regex("""(?i)(?:\b(?:open|launch)\s+(?:the\s+)?|(?:افتح|تفتح)\s+(?:ال)?)\s*(?:youtube|يوتيوب)""")
    private val notifications = Regex("""(?i)(?:summari[sz]e|read|لخص|اقرأ|اقرا)\s+(?:(?:my|the)\s+)?(?:notifications|[اإ]شعاراتي|ال[اإ]شعارات)""")
    private val actionFollowup = Regex("""(?i)(?:طب\s+)?(?:استخدمها|ابحثلي|دورلي|search now)(?:\s+يا?لا)?[.!؟ ]*""")

    private val informational = Regex("""(?i)^\s*(?:how (?:do|can|to)|what is|explain how|ازاي|إزاي|كيف|اشرحلي ازاي|اشرح لي كيف|don't|do not|متفتحش|ماتفتحش|متبحثش)\s""")

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
    fun tracked(goal: String) = !informational.containsMatchIn(goal) &&
        (youtubeSearch(goal) || notifications.containsMatchIn(goal) || captureRequested(goal))
    fun followup(goal: String) = actionFollowup.matches(goal.trim()) ||
        goal.trim().substringAfterLast('،').trim().let { actionFollowup.matches(it) }

    fun missing(goal: String, evidence: List<EvidenceRef>): List<String> {
        if (!tracked(goal)) return emptyList()
        val ok = evidence.filter { it.outcome == RunEvidenceLedger.Outcome.OK }
        return buildList {
            if (captureRequested(goal) && ok.none { it.tool == "screenshot_tool" || it.tool == "visual_inspector" })
                add("Capture the requested screen with a permitted tool, or report the capture blocker.")
            if (notifications.containsMatchIn(goal) && ok.none { it.tool == "read_notifications" })
                add("Read the requested notifications and summarize the returned alerts.")
            if (launch.containsMatchIn(goal) && ok.none { it.tool == "app_manager_tool" &&
                    it.target.split(' ').containsAll(listOf("action=launch_app", "packageName=com.google.android.youtube")) })
                add("Open the requested YouTube app.")
            if (youtubeSearch(goal) && ok.none { it.tool == "social_media_video" &&
                    "action=search_youtube" in it.target.split(' ') })
                add("Use social_media_video search_youtube with the requested topic and cite a returned video. Report incomplete if results are unavailable.")
        }
    }
}
