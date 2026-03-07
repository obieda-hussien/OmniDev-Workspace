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
 * [LocalInferenceEngine] backed by BitNet.cpp via JNI.
 *
 * BitNet.cpp (https://github.com/microsoft/BitNet) is a llama.cpp fork optimised
 * for 1-bit quantised (BitNet b1.58) models.  Its public C API mirrors llama.cpp,
 * so the JNI contract here is identical to [LlamaCppInferenceEngine].
 *
 * Native library: `libbitnet_jni.so` — built from `app/src/main/cpp/` via CMake.
 *
 * When the BitNet.cpp submodule is absent a stub library is compiled in its place;
 * [isNativeAvailable] will be `false` and [loadModel] returns [Result.failure]
 * with a clear setup message instead of crashing.
 */
class BitnetInferenceEngine : LocalInferenceEngine {

    @Volatile private var nativeCtxPtr: Long = 0L
    @Volatile private var _loadedModelName: String? = null

    override val isLoaded: Boolean get() = nativeCtxPtr != 0L

    // ── Public API ──────────────────────────────────────────────────────────

    override suspend fun loadModel(context: Context, modelUri: Uri): Result<String> =
        withContext(Dispatchers.IO) {
            if (!isNativeAvailable) {
                return@withContext Result.failure(
                    IllegalStateException(
                        "BitNet.cpp inference engine is not compiled into this build. " +
                        "To enable on-device BitNet inference:\n" +
                        "1. Run: git submodule update --init --recursive\n" +
                        "2. Rebuild: ./gradlew assembleDebug\n\n" +
                        "BitNet models run entirely on-device with very low RAM usage."
                    )
                )
            }

            try {
                if (nativeCtxPtr != 0L) {
                    try { nativeFreeModel(nativeCtxPtr) } catch (e: Throwable) {
                        Log.w(TAG, "nativeFreeModel threw while releasing previous model", e)
                    }
                    nativeCtxPtr = 0L
                    _loadedModelName = null
                }

                val pfd = context.contentResolver.openFileDescriptor(modelUri, "r")
                    ?: return@withContext Result.failure(
                        IOException("Cannot open model file — URI not accessible: $modelUri")
                    )

                val modelName = resolveDisplayName(context, modelUri)

                val fileSizeBytes = try { pfd.statSize } catch (_: Exception) { -1L }
                if (fileSizeBytes > 0) {
                    val nativeHeapFree = android.os.Debug.getNativeHeapSize() -
                            android.os.Debug.getNativeHeapAllocatedSize()
                    if (fileSizeBytes > nativeHeapFree * OOM_HEADROOM_FACTOR) {
                        Log.w(TAG,
                            "Model size (${fileSizeBytes / 1_048_576}MB) may exceed " +
                            "available native memory — proceeding with caution.")
                    }
                }

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
                pfd.close()

                if (ctx == 0L) {
                    return@withContext Result.failure(
                        IOException(
                            "BitNet.cpp failed to load \"$modelName\". " +
                            "Ensure the file is a valid BitNet-quantised GGUF model " +
                            "and that the device has sufficient RAM."
                        )
                    )
                }

                nativeCtxPtr = ctx
                _loadedModelName = modelName
                Log.i(TAG, "BitNet model loaded: $modelName (ctx=0x${ctx.toString(16)})")
                Result.success(modelName)

            } catch (oom: OutOfMemoryError) {
                Log.e(TAG, "OOM while loading BitNet model — freeing native resources", oom)
                safeFreeCurrent()
                Result.failure(IOException(
                    "Out of memory while loading the BitNet model. " +
                    "BitNet models typically require less RAM than standard GGUF — " +
                    "try a smaller variant or close other apps."
                ))
            } catch (e: Exception) {
                Log.e(TAG, "loadModel failed", e)
                safeFreeCurrent()
                Result.failure(e)
            }
        }

    override fun generateResponse(prompt: String): Flow<String> = callbackFlow {
        if (!isNativeAvailable) {
            trySend("[ERROR] BitNet.cpp inference engine is not available in this build. " +
                    "Rebuild the app with the BitNet.cpp submodule initialized.")
            close()
            return@callbackFlow
        }

        if (!isLoaded) {
            trySend("[ERROR] No BitNet model loaded. Load a BitNet GGUF model in Settings → Local Edge Model.")
            close()
            return@callbackFlow
        }

        val ctx = nativeCtxPtr
        var generationThread: Thread? = null

        val callback = LlamaCppInferenceEngine.TokenCallback { token, isDone ->
            if (token.isNotEmpty()) trySend(token)
            if (isDone) close()
        }

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
                Log.e(TAG, "OOM during BitNet inference", oom)
                trySend("[ERROR] Out of memory during BitNet inference. " +
                        "Try a smaller model or close other apps.")
                close()
            } catch (e: Exception) {
                Log.e(TAG, "nativeStartGeneration threw", e)
                trySend("[ERROR] BitNet inference failed: ${e.message}")
                close(e)
            }
        }, "bitnet-inference")
        generationThread.start()

        awaitClose {
            try { nativeStopGeneration(ctx) } catch (_: Throwable) {}
            generationThread?.join(GENERATION_THREAD_SHUTDOWN_TIMEOUT_MS)
        }
    }.flowOn(Dispatchers.Default)

    @Synchronized
    override fun unloadModel() {
        safeFreeCurrent()
    }

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

    private external fun nativeLoadModel(
        modelPath:   String,
        nThreads:    Int,
        contextSize: Int,
        batchSize:   Int,
        useGpu:      Boolean
    ): Long

    private external fun nativeStartGeneration(
        ctx:           Long,
        prompt:        String,
        maxNewTokens:  Int,
        temperature:   Float,
        topP:          Float,
        repeatPenalty: Float,
        callback:      LlamaCppInferenceEngine.TokenCallback
    )

    private external fun nativeStopGeneration(ctx: Long)
    private external fun nativeFreeModel(ctx: Long)

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun resolveDisplayName(context: Context, uri: Uri): String = try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            cursor.moveToFirst()
            if (idx >= 0) cursor.getString(idx) else uri.lastPathSegment ?: "Unknown"
        } ?: (uri.lastPathSegment ?: "Unknown")
    } catch (_: Exception) {
        uri.lastPathSegment ?: "bitnet_model.gguf"
    }

    companion object {
        private const val TAG = "BitnetEngine"
        private const val MAX_INFERENCE_THREADS = 8
        private const val GENERATION_THREAD_SHUTDOWN_TIMEOUT_MS = 2_000L
        private const val OOM_HEADROOM_FACTOR = 3L

        @JvmStatic
        private external fun nativeIsStub(): Boolean

        val isNativeAvailable: Boolean

        init {
            isNativeAvailable = try {
                System.loadLibrary("bitnet_jni")
                val stub = nativeIsStub()
                if (stub) {
                    Log.w(TAG,
                        "libbitnet_jni.so loaded but is the STUB build — " +
                        "real BitNet inference is not available. " +
                        "Run `git submodule update --init --recursive` then rebuild to " +
                        "enable on-device BitNet.cpp inference.")
                    false
                } else {
                    Log.i(TAG, "libbitnet_jni.so loaded — real BitNet on-device inference enabled")
                    true
                }
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG,
                    "libbitnet_jni.so not found — BitNet inference not available.", e)
                false
            }
        }
    }
}
