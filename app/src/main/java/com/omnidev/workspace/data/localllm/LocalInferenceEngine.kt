package com.omnidev.workspace.data.localllm

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.Flow

/**
 * Abstraction for an on-device local LLM engine that can load a quantized GGUF model
 * and generate responses without any network connectivity.
 *
 * Two production implementations are provided:
 * - [LlamaCppInferenceEngine] — backed by llama.cpp, the de-facto standard for GGUF models.
 * - [BitnetInferenceEngine]   — backed by BitNet.cpp, optimised for 1-bit BitNet models.
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
 * Singleton accessor for the active local inference engine.
 *
 * The active engine is determined by [activeEngineType] and can be switched at
 * runtime via [setActiveEngine].  Model state (loaded model, weights in native
 * memory) is NOT automatically transferred — callers must reload the model after
 * switching engines.
 */
object LocalEngineHolder {

    /** Stable model-selector ID used to route agent/chat requests to the local engine. */
    const val LOCAL_EDGE_MODEL_ID = "local-edge-model"

    private val llamaEngine  = LlamaCppInferenceEngine()
    private val bitnetEngine = BitnetInferenceEngine()

    @Volatile
    private var _activeType: LocalEngineType = LocalEngineType.LLAMA_CPP

    /** The currently-active engine type. */
    val activeEngineType: LocalEngineType get() = _activeType

    /** The currently-active engine instance. */
    val engine: LocalInferenceEngine
        get() = when (_activeType) {
            LocalEngineType.LLAMA_CPP -> llamaEngine
            LocalEngineType.BITNET    -> bitnetEngine
        }

    /**
     * Switches the active engine to [type].
     *
     * The previously-active engine's model (if any) is **not** automatically unloaded;
     * the caller is responsible for unloading before switching if desired.
     */
    fun setActiveEngine(type: LocalEngineType) {
        _activeType = type
    }

    /** Convenience accessor — returns the [LlamaCppInferenceEngine] instance directly. */
    val llamaCppEngine: LlamaCppInferenceEngine get() = llamaEngine

    /** Convenience accessor — returns the [BitnetInferenceEngine] instance directly. */
    val bitnetCppEngine: BitnetInferenceEngine get() = bitnetEngine
}
