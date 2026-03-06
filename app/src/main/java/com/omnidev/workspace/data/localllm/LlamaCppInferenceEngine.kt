package com.omnidev.workspace.data.localllm

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Production [LocalInferenceEngine] backed by llama.cpp via JNI.
 *
 * This is the **sole** implementation of [LocalInferenceEngine]. It handles both
 * the full-inference path (when the real `libllama_jni.so` is compiled from the
 * llama.cpp git submodule) and the unavailable path (when the native library is
 * missing or only the stub is present) — no mock/fallback class is needed.
 *
 * Native library: `libllama_jni.so` — built from `app/src/main/cpp/` via CMake.
 *
 * ── Model loading ─────────────────────────────────────────────────────────────
 * 1. Opens a ParcelFileDescriptor for the content:// (or file://) URI.
 * 2. Uses `/proc/self/fd/{fd}` to give the native layer a readable file-system path
 *    without copying the (potentially multi-GB) GGUF file to app-private storage.
 * 3. Calls [nativeLoadModel] → llama.cpp allocates the model weights and returns an
 *    opaque 64-bit context pointer.
 * 4. Closes the PFD after loading (weights are fully resident in native memory).
 *
 * ── Inference ─────────────────────────────────────────────────────────────────
 * 1. [nativeStartGeneration] runs a llama.cpp decode loop on a **dedicated Thread**.
 * 2. Each generated token is delivered via [TokenCallback.onToken] on that thread.
 * 3. The Kotlin [callbackFlow] collects those tokens and emits them downstream.
 * 4. On Flow cancellation, [nativeStopGeneration] sets a stop flag that exits the
 *    native loop cleanly on the next token boundary.
 *
 * ── OOM Protection ───────────────────────────────────────────────────────────
 * - Native `loadModel` failures (including OOM in llama.cpp allocations) return 0
 *   and are caught gracefully as `Result.failure`.
 * - All native calls are wrapped in try-catch for `OutOfMemoryError` / `Error` so
 *   the app never hard-crashes from a native allocation failure.
 * - `unloadModel()` always frees native memory via `nativeFreeModel()` and is safe
 *   to call multiple times.
 */
class LlamaCppInferenceEngine : LocalInferenceEngine {

    /** Opaque pointer to the native `LlamaContext` struct; 0 means no model loaded. */
    @Volatile private var nativeCtxPtr: Long = 0L

    /** Display name of the currently-loaded model file (e.g. "Llama-3.2-1B.gguf"). */
    @Volatile private var _loadedModelName: String? = null

    override val isLoaded: Boolean get() = nativeCtxPtr != 0L

    // ── Public API ──────────────────────────────────────────────────────────

    override suspend fun loadModel(context: Context, modelUri: Uri): Result<String> =
        withContext(Dispatchers.IO) {
            // ── Guard: native engine must be available ──────────────────────
            if (!isNativeAvailable) {
                return@withContext Result.failure(
                    IllegalStateException(
                        "On-device inference engine is not compiled into this build. " +
                        "To enable local inference:\n" +
                        "1. Run: git submodule update --init --recursive\n" +
                        "2. Rebuild: ./gradlew assembleDebug\n\n" +
                        "The model will then run entirely on-device."
                    )
                )
            }

            try {
                // Release any previously-loaded model first
                if (nativeCtxPtr != 0L) {
                    try { nativeFreeModel(nativeCtxPtr) } catch (_: Throwable) {}
                    nativeCtxPtr = 0L
                    _loadedModelName = null
                }

                val pfd = context.contentResolver.openFileDescriptor(modelUri, "r")
                    ?: return@withContext Result.failure(
                        IOException("Cannot open model file — URI not accessible: $modelUri")
                    )

                val modelName = resolveDisplayName(context, modelUri)

                // ── OOM pre-check ──────────────────────────────────────────
                // Estimate whether the device has enough free memory for the model.
                // GGUF models are mmap'd so this is a rough heuristic, not a hard limit.
                val fileSizeBytes = try { pfd.statSize } catch (_: Exception) { -1L }
                if (fileSizeBytes > 0) {
                    val runtime = Runtime.getRuntime()
                    val nativeHeapFree = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())
                    if (fileSizeBytes > nativeHeapFree * OOM_SAFETY_FACTOR) {
                        Log.w(TAG,
                            "Model size (${fileSizeBytes / 1_048_576}MB) may exceed " +
                            "available memory — proceeding with caution.")
                    }
                }

                // /proc/self/fd/{n} is a symbolic link that is readable as a file path by
                // native code for the duration of this process, avoiding a full file copy.
                val procPath = "/proc/self/fd/${pfd.fd}"

                val cpuCores = Runtime.getRuntime().availableProcessors()
                    .coerceAtMost(MAX_INFERENCE_THREADS)
                val ctx = nativeLoadModel(
                    modelPath   = procPath,
                    nThreads    = cpuCores,
                    contextSize = 4096,
                    batchSize   = 512,
                    useGpu      = false
                )

                // pfd can be closed now — the native side mmap'd or read the file
                pfd.close()

                if (ctx == 0L) {
                    return@withContext Result.failure(
                        IOException(
                            "llama.cpp failed to load \"$modelName\". " +
                            "Ensure the file is a valid, non-corrupted quantized GGUF model " +
                            "and that the device has sufficient RAM."
                        )
                    )
                }

                nativeCtxPtr = ctx
                _loadedModelName = modelName
                Log.i(TAG, "Model loaded: $modelName (ctx=0x${ctx.toString(16)})")
                Result.success(modelName)

            } catch (oom: OutOfMemoryError) {
                Log.e(TAG, "OOM while loading model — freeing native resources", oom)
                safeFreeCurrent()
                Result.failure(IOException(
                    "Out of memory while loading the model. " +
                    "Try a smaller quantized model (e.g. Q4_K_S or IQ4_XS) " +
                    "or close other apps to free RAM."
                ))
            } catch (e: Exception) {
                Log.e(TAG, "loadModel failed", e)
                safeFreeCurrent()
                Result.failure(e)
            }
        }

    override fun generateResponse(prompt: String): Flow<String> = callbackFlow {
        // ── Guard: native engine must be available ──────────────────────────
        if (!isNativeAvailable) {
            trySend("[ERROR] Native inference engine is not available in this build. " +
                    "Rebuild the app with the llama.cpp submodule initialized.")
            close()
            return@callbackFlow
        }

        if (!isLoaded) {
            trySend("[ERROR] No model loaded. Load a GGUF model in Settings → Local Edge Model.")
            close()
            return@callbackFlow
        }

        val ctx = nativeCtxPtr
        var generationThread: Thread? = null

        val callback = TokenCallback { token, isDone ->
            if (token.isNotEmpty()) trySend(token)
            if (isDone) close()
        }

        // Run the blocking generation loop on a dedicated thread so the coroutine
        // can be cancelled (awaitClose → nativeStopGeneration) at any time.
        generationThread = Thread({
            try {
                nativeStartGeneration(
                    ctx           = ctx,
                    prompt        = prompt,
                    maxNewTokens  = 2048,
                    temperature   = 0.72f,
                    topP          = 0.95f,
                    repeatPenalty = 1.10f,
                    callback      = callback
                )
            } catch (oom: OutOfMemoryError) {
                Log.e(TAG, "OOM during inference", oom)
                trySend("[ERROR] Out of memory during inference. " +
                        "Try a smaller model or close other apps.")
                close()
            } catch (e: Exception) {
                Log.e(TAG, "nativeStartGeneration threw", e)
                trySend("[ERROR] Inference failed: ${e.message}")
                close(e)
            }
        }, "llama-inference")
        generationThread.start()

        awaitClose {
            // Coroutine cancelled → signal the native loop to exit cleanly
            try { nativeStopGeneration(ctx) } catch (_: Throwable) {}
            generationThread?.join(GENERATION_THREAD_SHUTDOWN_TIMEOUT_MS)
        }
    }.flowOn(Dispatchers.Default)

    @Synchronized
    override fun unloadModel() {
        safeFreeCurrent()
    }

    /** Safely frees the current native context without throwing. */
    private fun safeFreeCurrent() {
        val ctx = nativeCtxPtr
        if (ctx != 0L) {
            try { nativeFreeModel(ctx) } catch (e: Throwable) {
                Log.w(TAG, "nativeFreeModel threw during cleanup", e)
            }
            nativeCtxPtr = 0L
        }
        _loadedModelName = null
    }

    // ── JNI Declarations ────────────────────────────────────────────────────

    /**
     * Loads a GGUF model from [modelPath] (absolute file-system path).
     * @return Opaque context pointer, or `0` on failure.
     */
    private external fun nativeLoadModel(
        modelPath:   String,
        nThreads:    Int,
        contextSize: Int,
        batchSize:   Int,
        useGpu:      Boolean
    ): Long

    /**
     * Runs the llama.cpp token-generation loop synchronously on the calling thread.
     * Each decoded token (and the final EOS marker) is delivered to [callback].
     * The loop exits when EOS is hit, [maxNewTokens] are produced, or
     * [nativeStopGeneration] sets the internal stop flag.
     */
    private external fun nativeStartGeneration(
        ctx:           Long,
        prompt:        String,
        maxNewTokens:  Int,
        temperature:   Float,
        topP:          Float,
        repeatPenalty: Float,
        callback:      TokenCallback
    )

    /** Sets a stop flag that causes the active [nativeStartGeneration] call to return. */
    private external fun nativeStopGeneration(ctx: Long)

    /** Frees all native memory (llama_context + llama_model) for [ctx]. */
    private external fun nativeFreeModel(ctx: Long)

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun resolveDisplayName(context: Context, uri: Uri): String = try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            cursor.moveToFirst()
            if (idx >= 0) cursor.getString(idx) else uri.lastPathSegment ?: "Unknown"
        } ?: (uri.lastPathSegment ?: "Unknown")
    } catch (_: Exception) {
        uri.lastPathSegment ?: "local_model.gguf"
    }

    // ── Native token callback (called from the inference thread) ────────────

    /**
     * Functional interface passed to the native side.
     * `onToken` is invoked once per generated token from the native thread.
     *
     * JNI signature: `(Ljava/lang/String;Z)V`
     */
    fun interface TokenCallback {
        fun onToken(token: String, isDone: Boolean)
    }

    // ── Static initialiser ──────────────────────────────────────────────────

    companion object {
        private const val TAG = "LlamaCppEngine"

        /** Upper bound on inference threads — 8 is a practical limit on mobile hardware
         *  beyond which additional threads provide diminishing returns and increase
         *  memory bandwidth pressure on efficiency cores. */
        private const val MAX_INFERENCE_THREADS = 8

        /** How long to wait for the native generation thread to exit after cancellation. */
        private const val GENERATION_THREAD_SHUTDOWN_TIMEOUT_MS = 2_000L

        /**
         * If the model file is larger than `available_heap × OOM_SAFETY_FACTOR`, emit
         * a warning before attempting to load.  GGUF files are typically mmap'd by
         * llama.cpp, so this is a heuristic rather than a strict memory limit.
         */
        private const val OOM_SAFETY_FACTOR = 3L

        /**
         * Returns `true` when the stub library (llama.cpp submodule absent) is loaded,
         * `false` for the real inference-capable build.
         * Being `@JvmStatic` avoids allocating a [LlamaCppInferenceEngine] instance just
         * to perform this check during [Companion.init].
         *
         * JNI function: `Java_…_LlamaCppInferenceEngine_nativeIsStub`
         */
        @JvmStatic
        private external fun nativeIsStub(): Boolean

        /**
         * `true` if `libllama_jni.so` was successfully loaded from the APK AND the
         * library is the real inference build (not the stub).
         * `false` if the native library is absent or the stub was compiled (developer
         * build without the submodule) — `loadModel()` returns a descriptive failure.
         */
        val isNativeAvailable: Boolean

        init {
            isNativeAvailable = try {
                System.loadLibrary("llama_jni")
                // Distinguish between the real library and the stub compiled when the
                // llama.cpp submodule is absent.  The stub's nativeIsStub() returns true;
                // the real library's returns false.
                val stub = nativeIsStub()
                if (stub) {
                    Log.w(TAG,
                        "libllama_jni.so loaded but is the STUB build — " +
                        "real inference is not available. " +
                        "Run `git submodule update --init --recursive` then rebuild to " +
                        "enable on-device llama.cpp inference.")
                    false
                } else {
                    Log.i(TAG, "libllama_jni.so loaded — real on-device inference enabled")
                    true
                }
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG,
                    "libllama_jni.so not found — native inference not available. " +
                    "Run `git submodule update --init --recursive` then rebuild to enable " +
                    "real llama.cpp inference.", e)
                false
            }
        }
    }
}
