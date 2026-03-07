package com.omnidev.workspace.data.localllm

/**
 * Identifies which on-device inference engine to use for local model inference.
 *
 * Only [LLAMA_CPP] is supported. It handles all standard GGUF quantisation formats
 * (Q4_K_M, Q5_K_S, IQ4_XS, …) as well as BitNet i2_s quantized GGUF models —
 * no separate BitNet engine is required.
 */
enum class LocalEngineType(val displayName: String, val description: String) {
    /**
     * llama.cpp — the de-facto standard for GGUF model inference on mobile.
     * Supports Q4, Q8, IQ4 and BitNet i2_s quantised GGUF models natively.
     */
    LLAMA_CPP(
        displayName = "llama.cpp",
        description = "Supports all GGUF formats including BitNet i2_s quantized models."
    );

    companion object {
        /** Returns the enum value matching [name], or [LLAMA_CPP] as the default. */
        fun fromName(name: String): LocalEngineType =
            entries.firstOrNull { it.name == name } ?: LLAMA_CPP
    }
}
