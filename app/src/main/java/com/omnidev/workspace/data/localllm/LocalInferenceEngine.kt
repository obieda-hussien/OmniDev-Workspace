package com.omnidev.workspace.data.localllm

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * Abstraction for an on-device local LLM engine that can load a quantized GGUF model
 * and generate responses without any network connectivity.
 *
 * The JNI bindings to a native llama.cpp / GGML runtime are mocked here —
 * the interface and flow structure are production-ready for when the native .so
 * libraries are bundled with the app.
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
 * Mock implementation of [LocalInferenceEngine].
 *
 * This class mirrors the real JNI call contract:
 *   - `nativeLoadModel(filePath: String): Long`  — returns a native context pointer
 *   - `nativeGenerate(ctx: Long, prompt: String): String` — returns next token
 *   - `nativeFreeModel(ctx: Long)`
 *
 * The mock simulates a realistic streaming response so the rest of the pipeline
 * can be developed and tested without the native libraries.
 */
class MockLocalInferenceEngine : LocalInferenceEngine {

    private var loadedModelName: String? = null

    override val isLoaded: Boolean get() = loadedModelName != null

    override suspend fun loadModel(context: Context, modelUri: Uri): Result<String> {
        return try {
            // In production: nativeLoadModel(resolveFilePath(context, modelUri))
            val modelName = resolveDisplayName(context, modelUri)
            delay(500) // Simulate model loading time
            loadedModelName = modelName
            Result.success(modelName)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun generateResponse(prompt: String): Flow<String> = flow {
        if (!isLoaded) {
            emit("[ERROR] No model loaded. Please select a model in Local Model Manager.")
            return@flow
        }
        // Mock streaming: emit a simulated response word-by-word
        // In production this would call nativeGenerate() in a loop
        val mockResponse = "I am running locally on your device using the ${loadedModelName} model. " +
                "This is a mock response demonstrating the streaming token output. " +
                "In production, this would use llama.cpp JNI bindings to run actual inference."
        for (word in mockResponse.split(" ")) {
            emit("$word ")
            delay(50) // Simulate token generation latency
        }
    }.flowOn(Dispatchers.Default)

    override fun unloadModel() {
        // In production: nativeFreeModel(nativeCtxPointer)
        loadedModelName = null
    }

    private fun resolveDisplayName(context: Context, uri: Uri): String {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                cursor.moveToFirst()
                if (nameIndex >= 0) cursor.getString(nameIndex) else uri.lastPathSegment ?: "Unknown"
            } ?: (uri.lastPathSegment ?: "Unknown")
        } catch (e: Exception) {
            uri.lastPathSegment ?: "local_model.gguf"
        }
    }
}

/**
 * Singleton accessor for the local inference engine.
 *
 * Preference order:
 *  1. [LlamaCppInferenceEngine] — real on-device inference via `libllama_jni.so`.
 *     Requires the `app/src/main/cpp/llama.cpp` git submodule to be initialised
 *     and the app to be built with the NDK.
 *  2. [MockLocalInferenceEngine] — fallback when the native library is absent
 *     (developer build without the submodule, or CI without NDK). The mock
 *     returns a clearly-labelled placeholder message so the rest of the pipeline
 *     can be developed and tested without native code.
 */
object LocalEngineHolder {
    val engine: LocalInferenceEngine =
        if (LlamaCppInferenceEngine.isNativeAvailable) LlamaCppInferenceEngine()
        else MockLocalInferenceEngine()

    const val LOCAL_EDGE_MODEL_ID = "local-edge-model"
}
