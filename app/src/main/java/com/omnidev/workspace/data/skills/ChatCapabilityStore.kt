package com.omnidev.workspace.data.skills

import android.content.Context
import com.omnidev.workspace.domain.model.SkillAccessMode
import com.omnidev.workspace.domain.model.ToolAccessMode

/**
 * Lightweight persisted policy backing the chat composer "+ / Add to chat" menu.
 *
 * Existing search/fetch toggles stay in SettingsRepository for compatibility.
 * Browser/skill selection lives here so capability policy can also be enforced by
 * SkillManager and TierToolGate without a Room schema migration.
 */
object ChatCapabilityStore {
    private const val PREFS = "omnidev_chat_capabilities_v2"
    private const val KEY_TOOL_MODE = "tool_access_mode"
    private const val KEY_HEADLESS = "headless_browser_enabled"
    private const val KEY_SKILL_MODE = "skill_access_mode"
    private const val KEY_ALL_SKILLS = "use_all_enabled_skills"
    private const val KEY_SELECTED_SKILLS = "selected_skill_names"

    data class Snapshot(
        val toolAccessMode: ToolAccessMode = ToolAccessMode.ON_DEMAND,
        val headlessBrowserEnabled: Boolean = true,
        val skillAccessMode: SkillAccessMode = SkillAccessMode.ON_DEMAND,
        val useAllEnabledSkills: Boolean = true,
        val selectedSkillNames: Set<String> = emptySet()
    ) {
        fun allowsSkill(name: String): Boolean =
            skillAccessMode != SkillAccessMode.DISABLED &&
                (useAllEnabledSkills || name.trim().lowercase() in selectedSkillNames)
    }

    fun read(context: Context): Snapshot {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Snapshot(
            toolAccessMode = ToolAccessMode.fromKey(
                prefs.getString(KEY_TOOL_MODE, ToolAccessMode.ON_DEMAND.name).orEmpty()
            ),
            headlessBrowserEnabled = prefs.getBoolean(KEY_HEADLESS, true),
            skillAccessMode = SkillAccessMode.fromKey(
                prefs.getString(KEY_SKILL_MODE, SkillAccessMode.ON_DEMAND.name).orEmpty()
            ),
            useAllEnabledSkills = prefs.getBoolean(KEY_ALL_SKILLS, true),
            selectedSkillNames = prefs.getStringSet(KEY_SELECTED_SKILLS, emptySet())
                .orEmpty()
                .map { it.trim().lowercase() }
                .filter { it.isNotBlank() }
                .toSet()
        )
    }

    fun write(context: Context, snapshot: Snapshot) {
        val normalized = snapshot.selectedSkillNames
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .toSet()
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TOOL_MODE, snapshot.toolAccessMode.name)
            .putBoolean(KEY_HEADLESS, snapshot.headlessBrowserEnabled)
            .putString(KEY_SKILL_MODE, snapshot.skillAccessMode.name)
            .putBoolean(KEY_ALL_SKILLS, snapshot.useAllEnabledSkills)
            .putStringSet(KEY_SELECTED_SKILLS, normalized)
            .apply()
    }

    fun update(
        context: Context,
        transform: (Snapshot) -> Snapshot
    ): Snapshot {
        val next = transform(read(context))
        write(context, next)
        return next
    }

    val headlessToolNames: Set<String> = setOf(
        "headless_browser",
        "browser_navigate",
        "browser_execute_js",
        "browser_get_dom",
        "browser_click",
        "browser_type",
        "browser_screenshot"
    )
}
