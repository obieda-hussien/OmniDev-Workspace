package com.omnidev.workspace.data.localllm

/**
 * Identifies which on-device inference engine to use for local model inference.
 *
 * Only [LLAMA_CPP] is supported. It handles all standard GGUF quantisation formats
 * (Q4_K_M, Q5_K_S, IQ4_XS, …) as well as BitNet i2_s quantized GGUF models —
 * no separate BitNet engine is required.
 * * * TURBOQUANT INTEGRATION:
 * The llama.cpp backend is now supercharged with Google's TurboQuant KV Cache 
 * compression (PolarQuant + QJL), enabling massive context windows (32k+) 
 * natively on mobile devices.
 */
enum class LocalEngineType(val displayName: String, val description: String) {
    /**
     * llama.cpp — the de-facto standard for GGUF model inference on mobile.
     * Supports Q4, Q8, IQ4, BitNet i2_s, and TurboQuant 3-bit KV caching natively.
     */
    LLAMA_CPP(
        displayName = "llama.cpp (TurboQuant)",
        description = "Supports all GGUF formats, BitNet i2_s, and 32k+ context via TurboQuant 3-bit KV caching."
    );

    companion object {
        /** Returns the enum value matching [name], or [LLAMA_CPP] as the default. */
        fun fromName(name: String): LocalEngineType =
            entries.firstOrNull { it.name == name } ?: LLAMA_CPP
    }
}
