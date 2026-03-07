package com.omnidev.workspace.data.localllm

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.Flow

/**
 * Abstraction for an on-device local LLM engine that can load a quantized GGUF model
 * and generate responses without any network connectivity.
 *
 * The sole production implementation is [LlamaCppInferenceEngine], backed by llama.cpp.
 * It supports all standard GGUF quantisation formats (Q4_K_M, Q8_0, IQ4_XS, …) as well
 * as BitNet i2_s quantized GGUF models — no separate BitNet engine is required.
 */
interface LocalInferenceEngine {
    /** True if a model is currently loaded and ready to accept prompts. */
    val isLoaded: Boolean

    /**
     * Loads a model from the given [modelUri] (a SAF content:// URI or file:// path).
     * Returns a [Result] wrapping the model display name on success, or the exception on failure.
     */
    suspend fun loadModel(context: Context, modelUri: Uri): Result<String>

    /**
     * Generates a response to [prompt] as a streaming [Flow] of text tokens.
     * Each emission is a partial token; callers should concatenate them.
     */
    fun generateResponse(prompt: String): Flow<String>

    /** Unloads the current model and frees native memory. */
    fun unloadModel()
}

/**
 * Singleton accessor for the local inference engine (llama.cpp).
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

    /** The active inference engine instance. */
    val engine: LocalInferenceEngine get() = llamaEngine

    /**
     * No-op compatibility shim. Provided so callers that previously called
     * [setActiveEngine] continue to compile without error. The engine is
     * always llama.cpp; the [type] parameter is ignored.
     */
    @Suppress("UNUSED_PARAMETER")
    fun setActiveEngine(type: LocalEngineType) { /* single engine — no-op */ }

    /** Direct accessor for the [LlamaCppInferenceEngine] instance. */
    val llamaCppEngine: LlamaCppInferenceEngine get() = llamaEngine
}
