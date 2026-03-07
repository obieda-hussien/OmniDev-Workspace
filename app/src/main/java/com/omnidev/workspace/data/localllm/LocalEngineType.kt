package com.omnidev.workspace.data.localllm

/**
 * Identifies which on-device inference engine to use for local model inference.
 *
 * @property displayName  Human-readable label shown in the UI.
 * @property description  Short description of the engine's strengths.
 */
enum class LocalEngineType(val displayName: String, val description: String) {
    /**
     * llama.cpp — the de-facto standard for GGUF model inference on mobile.
     * Supports a wide range of quantised models (Q4_K_M, Q5_K_S, IQ4_XS, …).
     */
    LLAMA_CPP(
        displayName = "llama.cpp",
        description = "Broad GGUF model support. Best for general-purpose models."
    ),

    /**
     * BitNet.cpp — Microsoft's inference engine for 1-bit (BitNet b1.58) models.
     * Delivers dramatically lower memory usage and faster inference on ARM devices
     * when used with compatible BitNet-quantised GGUF models.
     */
    BITNET(
        displayName = "BitNet.cpp",
        description = "Optimised for 1-bit BitNet models. Lower RAM, faster on ARM."
    );

    companion object {
        /** Returns the enum value matching [name], or [LLAMA_CPP] as the default. */
        fun fromName(name: String): LocalEngineType =
            entries.firstOrNull { it.name == name } ?: LLAMA_CPP
    }
}
