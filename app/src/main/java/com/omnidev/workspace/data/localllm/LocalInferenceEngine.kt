package com.omnidev.workspace.data.localllm

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.Flow

/**
 * Abstraction for an on-device local LLM engine that can load a quantized GGUF model
 * and generate responses without any network connectivity.
 *
 * The production implementation is [LlamaCppInferenceEngine], backed by llama.cpp
 * via JNI.  When the native library is not compiled in, [LlamaCppInferenceEngine]
 * itself returns descriptive errors from [loadModel] / [generateResponse] rather
 * than relying on a separate mock class.
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
 * Singleton accessor for the local inference engine.
 *
 * Always uses [LlamaCppInferenceEngine] — the engine itself handles the case
 * where the native library is absent or is the no-op stub by returning
 * descriptive errors from `loadModel()` and `generateResponse()` instead of
 * crashing or producing confusing mock output.
 */
object LocalEngineHolder {
    val engine: LocalInferenceEngine = LlamaCppInferenceEngine()

    const val LOCAL_EDGE_MODEL_ID = "local-edge-model"
}
