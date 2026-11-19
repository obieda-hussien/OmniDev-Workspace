package com.omnidev.workspace.domain.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.omnidev.workspace.data.tools.ToolDefinition
import com.omnidev.workspace.data.tools.ToolParameter
import com.omnidev.workspace.domain.neural.OmniSpecialistModels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class BundledSpecialistModelsTest {
    @Test
    fun packagedAssetRunsAllSpecialistHeads() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val models = context.assets.open("omni-models/specialists-v1.bin.gz").use(OmniSpecialistModels::read)
        val query = "create a new task"
        assertEquals(listOf("task_manager"), models.rankTools(query, setOf("task_manager")))
        assertEquals(emptyList<String>(), models.rankTools(query, emptySet()))
        assertEquals("task_manager/create", models.adviseOperation(query)?.key)
        assertTrue(models.explain("يعني إيه enhanced_attack_surface؟").single().startsWith("enhanced_attack_surface:"))
        assertEquals(emptyList<String>(), models.rankTools("enhanced_attack_surface", setOf("enhanced_attack_surface")))
        val embedding = models.embed("اختبار الذاكرة")
        assertEquals(16, embedding.size)
        assertTrue(abs(embedding.sumOf { (it * it).toDouble() } - 1.0) < 1e-5)
    }

    @Test
    fun liveSchemaControlsOperationHints() {
        val query = "create a new task"
        val title = ToolParameter("title", "string", "Task title", required = true)
        val listOnly = ToolParameter("action", "string", "Task action", allowedValues = listOf("list"))
        val create = listOnly.copy(allowedValues = listOf("list", "create"))
        val matching = ToolDefinition("task_manager", "Manage tasks", listOf(title, create))

        val hint = LocalSpecialistAdvice.operationContext(query, listOf(matching))
        assertTrue(hint.contains("task_manager action=create"))
        assertTrue(hint.contains("title"))
        assertEquals("", LocalSpecialistAdvice.operationContext(query, emptyList()))
        assertEquals("", LocalSpecialistAdvice.operationContext(query, listOf(matching.copy(parameters = listOf(listOnly, title)))))
        assertFalse(LocalSpecialistAdvice.explanationContext("يعني إيه enhanced_attack_surface؟").isBlank())
    }
}
