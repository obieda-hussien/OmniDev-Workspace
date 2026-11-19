package com.omnidev.workspace.domain.engine

import android.util.Log
import com.omnidev.workspace.OmniDevApp
import com.omnidev.workspace.data.tools.ToolDefinition
import com.omnidev.workspace.domain.neural.OmniSpecialistModels

/** Local ranking and prompt advice over the current, permitted schemas. */
internal object LocalSpecialistAdvice {
    val models: OmniSpecialistModels? by lazy {
        try {
            OmniDevApp.instance.assets.open("omni-models/specialists-v1.bin.gz").use(OmniSpecialistModels::read)
        } catch (error: Exception) {
            runCatching { Log.w("OmniSpecialists", "Local specialist weights unavailable", error) }
            null
        }
    }

    fun rank(query: String, definitions: List<ToolDefinition>): List<String> =
        if (definitions.isEmpty() || query.isBlank()) emptyList()
        else models?.rankTools(query, definitions.mapTo(HashSet()) { it.name }).orEmpty()

    fun operationContext(query: String, definitions: List<ToolDefinition>): String {
        if (definitions.isEmpty() || query.isBlank()) return ""
        val suggestion = models?.adviseOperation(query) ?: return ""
        val name = suggestion.key.substringBefore('/')
        val action = suggestion.key.substringAfter('/', "")
        val definition = definitions.firstOrNull { it.name == name } ?: return ""
        val required = suggestion.required.split(',').filter { it.isNotEmpty() }
        if (required.any { key -> definition.parameters.none { it.name == key } }) return ""
        if (action.isNotEmpty()) {
            val selector = definition.parameters.firstOrNull { it.name == "action" } ?: return ""
            if (selector.allowedValues.isNotEmpty() && action !in selector.allowedValues) return ""
        }
        val requiredBySchema = definition.parameters.filter { it.required || action in it.requiredForActions }
            .map { it.name }
        return buildString {
            append("\nLocal tool suggestion (verify intent and native schema): ")
            append(name)
            if (action.isNotEmpty()) append(" action=").append(action)
            append(". ").append(suggestion.usage)
            val keys = (required + requiredBySchema).distinct()
            if (keys.isNotEmpty()) append(" Required values: ").append(keys.joinToString()).append('.')
        }
    }

    fun explanationContext(query: String): String {
        if (!explanationQuestion.containsMatchIn(query)) return ""
        val entries = models?.explain(query.take(4_096)).orEmpty()
        if (entries.isEmpty()) return ""
        return entries.joinToString("\n", prefix = "\nOmni catalog descriptions (for explanation):\n")
    }

    private val explanationQuestion = Regex(
        "^(?:\\s*)(?:what (?:is|does)|which (?:tool|catalog)|explain|tell me what|يعني ايه|يعني إيه|اشرح|إيه وظيفة|ايه وظيفة|إيه الأداة|ايه الاداة|à quoi sert|quel outil|qué herramienta|¿qué herramienta)",
        RegexOption.IGNORE_CASE
    )
}
