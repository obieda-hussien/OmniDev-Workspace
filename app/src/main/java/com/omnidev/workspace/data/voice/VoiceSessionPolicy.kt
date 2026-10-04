package com.omnidev.workspace.data.voice

object VoiceSessionPolicy {
    enum class Intent { CANCEL, UNLOCK, TASK }
    fun intent(text: String): Intent {
        val value = text.trim().lowercase(java.util.Locale.ROOT).replace('أ', 'ا').replace('إ', 'ا')
        if (value in setOf("cancel", "stop", "close", "الغاء", "الغي", "وقف", "خلاص")) return Intent.CANCEL
        val isDigits = runCatching {
            SpokenCredentialParser.parse(text, SpokenCredential.Kind.PIN).use { true }
        }.getOrDefault(false)
        if (Regex("\\b(unlock|passcode|password|pin|pattern|capital|uppercase|literal)\\b").containsMatchIn(value) ||
            isDigits ||
            value.matches(Regex("[0-9٠-٩۰-۹\\s]+")) ||
            value.split(Regex("\\s+")).all { it in setOf("zero", "oh", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "صفر", "واحد", "اتنين", "تلاته", "اربعه", "خمسه", "سته", "سبعه", "تمانيه", "تسعه") } ||
            value.contains("افتح القفل") || value.contains("افتح الرمز") || value.contains("باسورد") || value.contains("نقش")) return Intent.UNLOCK
        return Intent.TASK
    }
    fun confirmed(text: String) = text.trim().lowercase(java.util.Locale.ROOT).replace('أ', 'ا') in setOf("confirm", "yes", "تاكيد", "اكد", "ايوه")
    fun canCapture(locked: Boolean, overlay: Boolean, voiceCredential: Boolean, unlock: Boolean, privatePhase: Boolean) =
        (!locked || overlay) && (!privatePhase || (locked && overlay && voiceCredential && unlock))
    fun shouldUnlockForTool(name: String, action: String?): Boolean = name in setOf("visual_inspector", "screenshot_tool", "ui_replica_pipeline") ||
        (name in setOf("semantic_ui", "ui_automation", "autofill_assist", "ime_tool", "app_manager", "app_manager_tool") &&
            action?.lowercase() !in setOf("dump_tree", "get_summary", "find_element", "get_text", "describe", "verify", "wait_for", "macro_list", "routine_wait", "status", "list"))
}
