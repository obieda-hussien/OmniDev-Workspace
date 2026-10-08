package com.omnidev.workspace.data.routines

/** Local matching is deliberately conservative; broader semantic reuse needs a reviewed proposal. */
object RoutineInvocationPolicy {
    enum class Decision { DIRECT, REVIEW, BLOCKED }

    private val discussion = Regex(
        "^(?:how|why|what|explain|describe|compare|summarize|when|if|suppose|imagine|" +
            "tell me|can (?:i|you) explain|help me understand|i (?:said|wrote|tried)|" +
            "ازاي|ليه|ايه|اشرح|وضح|قارن|لخص|لو|لما|تخيل|يعني|" +
            "عايز (?:افهم|اعرف)|عاوز (?:افهم|اعرف)|انا (?:قلت|كتبت|جربت)|ممكن تشرح)(?:\\s|$)")
    private val refusal = Regex(
        "(?:^|\\s)(?:don't|dont|do not|never|without (?:running|executing)|not now|" +
            "لا (?:تنفذ|تشغل)|بدون (?:تنفيذ|تشغيل)|متنفذش|ماتنفذش|متشغلش|ماتشغلش|" +
            "متعملش|ماتعملش|مش دلوقتي|مش عايز|مش عاوز)(?:\\s|$)")
    private val action = Regex(
        "^(?:please\\s+)?(?:run|resume|continue|execute|start|open|search|find|send|create|" +
            "move|set|turn|launch|fill|do|help with (?:one )?paused learned task|" +
            "can you|could you|i (?:want|need) you to|" +
            "شغل|نفذ|كمل|ابدا|افتح|ابحث|دور|ابعت|اعمل|انقل|ظبط|" +
            "عايزك|عاوزك|عايز|عاوز|ممكن|قم|برجاء)(?:\\s|$)")
    private val extraIntent = Regex(
        "(?:^|\\s)(?:and then|but|unless|instead|if|because|also|then|" +
            "وبعدين|بعدين|بس|لكن|الا لو|لو|عشان|بدل|كمان|وبعدها)(?:\\s|$)")
    private val contextualReference = Regex(
        "(?:^|\\s)(?:it|that|this|them|there|same|him|her|ده|دي|دا|دول|نفسه|نفسها)(?:\\s|$)")
    private val secondAction = Regex(
        "(?:^|\\s)(?:and\\s+(?:open|run|send|delete|remove|create|move|set)|" +
            "و\\s*(?:افتح|شغل|نفذ|ابعت|امسح|احذف|اعمل|انقل|ظبط))(?:\\s|$)|\\.\\s+[\\p{L}]")

    fun isDiscussion(message: String): Boolean {
        val text = intentText(message)
        return discussion.containsMatchIn(text) || refusal.containsMatchIn(text)
    }

    fun canReplayAutomatically(message: String): Boolean {
        val text = message.trim()
        return text.isNotEmpty() && text.length <= 400 && text.split(Regex("\\s+")).size <= 40 &&
            !text.contains('\n') && !text.contains('\r') && !text.contains("```") &&
            !text.endsWith('?') && !text.endsWith('؟') && !isDiscussion(text) &&
            !contextualReference.containsMatchIn(RoutineMatcher.normalize(text)) &&
            !(text.startsWith('"') && text.endsWith('"')) &&
            !(text.startsWith('“') && text.endsWith('”'))
    }

    fun isAtomicValue(value: String): Boolean = value.isNotBlank() && value.length <= 120 &&
        value.split(Regex("\\s+")).size <= 12 && value.none { it in "\n\r;؛!?؟" } &&
        !extraIntent.containsMatchIn(RoutineMatcher.normalize(value)) &&
        !secondAction.containsMatchIn(RoutineMatcher.normalize(value)) && !isDiscussion(value)

    /** The selected ID and every bound value must agree; mentioning a task never authorizes it. */
    fun decide(message: String, routines: List<LearnedRoutine>, routineId: String,
        parameters: Map<String, String>, resuming: Boolean = false): Decision {
        val routine = routines.singleOrNull { it.id == routineId && it.enabled } ?: return Decision.BLOCKED
        if (isDiscussion(message)) return Decision.BLOCKED
        val direct = RoutineMatcher.match(message, routines)
        if (!resuming && direct?.first?.id == routine.id && direct.second == parameters) return Decision.DIRECT
        // Questions about capability, long objectives and additional constraints cannot bypass review.
        return if (action.containsMatchIn(intentText(message))) Decision.REVIEW else Decision.BLOCKED
    }

    /** Retrieval only: lexical similarity is never treated as permission to execute. */
    fun candidates(message: String, routines: List<LearnedRoutine>): List<LearnedRoutine> {
        val words = tokens(message)
        return routines.filter { it.enabled }.map { routine ->
            val score = (listOf(routine.name) + routine.triggers).maxOfOrNull { text ->
                val target = tokens(text)
                if (target.isEmpty()) 0.0 else words.intersect(target).size.toDouble() / target.size
            } ?: 0.0
            routine to score
        }.filter { it.second >= .5 }.sortedByDescending { it.second }.take(3).map { it.first }
    }

    private fun tokens(text: String) = Regex("[\\p{L}\\p{N}_]+")
        .findAll(RoutineMatcher.normalize(text.replace(Regex("\\{\\{.*?\\}\\}"), " ")))
        .map { it.value }.filter { it.length > 1 }.toSet()

    private fun intentText(message: String) = RoutineMatcher.normalize(message)
        .replace(Regex("^(?:please|من فضلك|لو سمحت|لو تكرمت)\\s+"), "")
}
