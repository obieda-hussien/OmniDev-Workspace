package com.omnidev.workspace.ui.chat

import androidx.test.platform.app.InstrumentationRegistry
import com.omnidev.workspace.data.skills.ChatCapabilityStore
import com.omnidev.workspace.data.skills.SkillManager
import com.omnidev.workspace.domain.model.SkillAccessMode
import org.junit.Assert.*
import org.junit.Test

class SkillMentionPolicyTest {
    @Test fun onlySelectedSkillBodiesAreLoadedAndDisabledSkillsAreRejected() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = SkillManager(context)
        val original = ChatCapabilityStore.read(context)
        val first = "mention-policy-first"
        val second = "mention-policy-second"
        try {
            ChatCapabilityStore.write(context, original.copy(skillAccessMode = SkillAccessMode.ON_DEMAND, useAllEnabledSkills = true))
            for (name in listOf(first, second)) manager.installSkillMarkdown(
                "---\nname: $name\ndescription: Test mention focus.\n---\nInstructions for $name.").getOrThrow()
            val prompt = manager.buildMentionedPromptContext(setOf(first))
            assertTrue(prompt.contains("Instructions for $first"))
            assertFalse(prompt.contains(second))
            manager.setEnabled(first, false)
            try { manager.buildMentionedPromptContext(setOf(first)); fail("Disabled skill must be rejected") }
            catch (_: IllegalArgumentException) { }
            ChatCapabilityStore.write(context, original.copy(skillAccessMode = SkillAccessMode.DISABLED))
            try { manager.buildMentionedPromptContext(setOf(second)); fail("Mentions must not override disabled skills") }
            catch (_: IllegalArgumentException) { }
        } finally {
            manager.deleteUserSkill(first)
            manager.deleteUserSkill(second)
            ChatCapabilityStore.write(context, original)
        }
    }
}
