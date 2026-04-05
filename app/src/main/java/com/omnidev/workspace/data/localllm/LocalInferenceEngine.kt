package com.omnidev.workspace.data.localllm

// ═══════════════════════════════════════════════════════════════════════════════
// LOCAL ENGINE HOLDER (SINGLETON)
//
// NOTE: The full interface definition (LocalInferenceEngine) and all shared
//       data models (InferenceConfig, InferenceSession, InferenceStats, etc.)
//       live in LlamaCppInferenceEngine.kt.
//       The enum LocalEngineType lives in LocalEngineType.kt.
//       This file only provides the singleton accessor.
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Singleton accessor for the local inference engine (llama.cpp with TurboQuant).
 *
 * All GGUF model formats — including BitNet i2_s quantized models — are handled
 * by the single llama.cpp backend. [activeEngineType] is always [LocalEngineType.LLAMA_CPP].
 */
object LocalEngineHolder {

    /** Stable model-selector ID used to route agent/chat requests to the local engine. */
    const val LOCAL_EDGE_MODEL_ID = "local-edge-model"

    private val llamaEngine = LlamaCppInferenceEngine()

    /** The active engine type — always [LocalEngineType.LLAMA_CPP]. */
    val activeEngineType: LocalEngineType get() = LocalEngineType.LLAMA_CPP

    /** The active inference engine instance, exposed through the abstraction. */
    val engine: LocalInferenceEngine get() = llamaEngine

    /**
     * No-op compatibility shim. Provided so callers that previously called
     * [setActiveEngine] continue to compile without error. The engine is
     * always llama.cpp; the [type] parameter is ignored.
     */
    @Suppress("UNUSED_PARAMETER")
    fun setActiveEngine(type: LocalEngineType) { /* single engine — no-op */ }

    /** Direct accessor for the [LlamaCppInferenceEngine] instance if concrete methods are needed. */
    val llamaCppEngine: LlamaCppInferenceEngine get() = llamaEngine
}
