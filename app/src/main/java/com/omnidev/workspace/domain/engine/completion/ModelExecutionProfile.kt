package com.omnidev.workspace.domain.engine.completion

enum class ApiPath { OPENAI_COMPATIBLE, GEMINI_NATIVE, OTHER }
enum class ToolChoiceMode { AUTO, ANY }

/**
 * Per-model execution policy. Defaults reproduce today's behaviour exactly; a profile only applies when
 * AgentConfig.enableModelProfiles is true, so quality can be measured before and after.
 */
data class ModelExecutionProfile(
    val id: String,
    /** null = keep AgentConfig.enableSelfReflection. */
    val selfReflection: Boolean? = null,
    val maxFinishRejections: Int = 3
) {
    companion object {
        val DEFAULT = ModelExecutionProfile("default")
        private val FLASH_LITE = ModelExecutionProfile("gemini-flash-lite", selfReflection = false, maxFinishRejections = 4)

        fun resolve(modelId: String, enabled: Boolean): ModelExecutionProfile {
            if (!enabled) return DEFAULT
            val id = modelId.lowercase()
            return if ("gemini" in id && "flash" in id && "lite" in id) FLASH_LITE else DEFAULT
        }
    }
}

/**
 * Tool-choice forcing is opt-in and path-specific. Google documents `any` for the native API only;
 * the OpenAI-compatible endpoint documents `auto`, so it never gets ANY from here.
 */
object ToolChoicePolicy {
    fun choose(path: ApiPath, actionPending: Boolean, nativeAnyVerified: Boolean): ToolChoiceMode =
        if (path == ApiPath.GEMINI_NATIVE && actionPending && nativeAnyVerified) ToolChoiceMode.ANY else ToolChoiceMode.AUTO
}
