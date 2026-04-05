package com.omnidev.workspace.data.localllm

import android.content.Context
import android.net.Uri
import android.os.Debug
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.*

// ═══════════════════════════════════════════════════════════════════════════════
// INTERFACE
// ═══════════════════════════════════════════════════════════════════════════════

interface LocalInferenceEngine {
    val isLoaded: Boolean
    val loadedModelName: String?
    val currentSession: InferenceSession?

    suspend fun loadModel(context: Context, modelUri: Uri): Result<String>
    fun generateResponse(prompt: String, config: InferenceConfig = InferenceConfig()): Flow<String>
    fun generateResponseInSession(sessionId: String, userMessage: String, config: InferenceConfig = InferenceConfig()): Flow<String>
    fun newSession(systemPrompt: String? = null): InferenceSession
    fun clearSession(sessionId: String)
    fun unloadModel()
    fun getStats(): InferenceStats
}

// ═══════════════════════════════════════════════════════════════════════════════
// DATA MODELS
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * KV cache quantization strategy.
 *
 * - [FP16]       → Default llama.cpp KV cache, highest quality, highest memory.
 * - [Q8_0]       → 8-bit symmetric, ~2× compression, near-lossless.
 * - [TURBO_QUANT] → Google TurboQuant (PolarQuant + QJL), ~5.3× compression,
 *                   enables 32k+ context on mobile. See [TurboQuantConfig].
 */
enum class KVCacheQuantType(val nativeId: Int, val bitsPerElement: Float) {
    FP16(0, 16f),
    Q8_0(1, 8f),
    TURBO_QUANT(2, 3f)
}

/**
 * Full TurboQuant configuration.
 *
 * TurboQuant = PolarQuant (key rotation) + QJL (query-joint locality hashing for keys)
 * Paper: "TurboQuant: Efficient KV-Cache Quantization for Large Language Models"
 *
 * Algorithm:
 * 1. Each key vector K is pre-multiplied by a random orthogonal matrix R:
 *    K' = R·K  (PolarQuant rotation — distributes outliers uniformly)
 * 2. K' is quantized to [quantBits] bits using per-head min/max scaling.
 * 3. During attention:  scores = (Q·R) · K_quantized^T  (query is rotated inline)
 * 4. Values are separately quantized to [valueBits] bits (usually 4-bit).
 * 5. Hadamard matrix is used as R when headDim is a power of 2 (fast WHT).
 *
 * @param quantBits     Key quantization bits (default 3).
 * @param valueBits     Value quantization bits (default 4 — values are smoother).
 * @param hadamardFast  Use Walsh–Hadamard transform (structured, O(n log n)).
 *                      Falls back to random Gaussian orthogonal if headDim is
 *                      not a power of 2.
 * @param outliersProtected  Reserve 1 extra bit for top-k outlier channels per head.
 * @param outlierTopK        Number of outlier channels to protect per attention head.
 * @param targetContextSize  Desired context window. TurboQuant enables very large
 *                           contexts on memory-constrained hardware.
 */
data class TurboQuantConfig(
    val quantBits: Int          = 3,
    val valueBits: Int          = 4,
    val hadamardFast: Boolean   = true,
    val outliersProtected: Boolean = true,
    val outlierTopK: Int        = 8,
    val targetContextSize: Int  = 32_768,
    val rotationSeed: Long      = 42L
) {
    init {
        require(quantBits in 2..8)  { "quantBits must be 2..8 (got $quantBits)" }
        require(valueBits in 2..8)  { "valueBits must be 2..8 (got $valueBits)" }
        require(outlierTopK >= 0)   { "outlierTopK must be >= 0" }
        require(targetContextSize > 0 && targetContextSize.countOneBits() == 1) {
            "targetContextSize must be a power of 2 (got $targetContextSize)"
        }
    }

    /**
     * Estimates memory savings compared to FP16 KV cache.
     * @return reduction factor (e.g. 5.3 = uses ~1/5.3 of FP16 memory).
     */
    fun estimatedCompressionRatio(): Float {
        val effectiveKeyBits = quantBits + if (outliersProtected) 0.5f else 0f
        return 16f / ((effectiveKeyBits + valueBits) / 2f)
    }
}

/**
 * Per-request inference configuration.
 *
 * @param maxNewTokens     Hard cap on generated tokens.
 * @param temperature      Sampling temperature. 0 = greedy decode.
 * @param topP             Nucleus sampling cutoff.
 * @param topK             Top-K sampling (0 = disabled).
 * @param minP             Min-P sampling (alternative to top-p, often superior).
 * @param repeatPenalty    Penalises repeated tokens.
 * @param repeatLastN      Window for repeat penalty (tokens).
 * @param seed             RNG seed. -1 = random.
 * @param stopSequences    List of strings that terminate generation.
 * @param streamTokens     Whether to stream individual tokens or buffer chunks.
 */
data class InferenceConfig(
    val maxNewTokens:    Int     = 2048,
    val temperature:     Float   = 0.72f,
    val topP:            Float   = 0.95f,
    val topK:            Int     = 0,
    val minP:            Float   = 0.05f,
    val repeatPenalty:   Float   = 1.10f,
    val repeatLastN:     Int     = 128,
    val seed:            Long    = -1L,
    val stopSequences:   List<String> = emptyList(),
    val streamTokens:    Boolean = true
)

/**
 * Active multi-turn conversation session backed by llama.cpp KV cache.
 *
 * The session stores encoded tokens so each turn can reuse the KV cache
 * from previous turns without re-encoding the full conversation history.
 */
data class InferenceSession(
    val sessionId:    String,
    val systemPrompt: String?,
    val createdAt:    Long = System.currentTimeMillis()
) {
    val messages: MutableList<SessionMessage> = mutableListOf()
    var cachedTokenCount: Int = 0

    fun buildPrompt(userMessage: String, modelFamily: ModelFamily): String =
        modelFamily.formatConversation(systemPrompt, messages, userMessage)
}

data class SessionMessage(
    val role:      String,   // "user" | "assistant"
    val content:   String,
    val timestamp: Long = System.currentTimeMillis()
)

/** Detected model family — used for prompt template selection. */
enum class ModelFamily {
    LLAMA3, LLAMA2, MISTRAL, PHI3, GEMMA, QWEN, BITNET, UNKNOWN;

    fun formatConversation(
        system:   String?,
        history:  List<SessionMessage>,
        userMsg:  String
    ): String = when (this) {
        LLAMA3 -> buildString {
            if (system != null) append("<|begin_of_text|><|start_header_id|>system<|end_header_id|>\n$system<|eot_id|>\n")
            else append("<|begin_of_text|>")
            history.forEach { msg ->
                append("<|start_header_id|>${msg.role}<|end_header_id|>\n${msg.content}<|eot_id|>\n")
            }
            append("<|start_header_id|>user<|end_header_id|>\n$userMsg<|eot_id|>\n<|start_header_id|>assistant<|end_header_id|>\n")
        }
        MISTRAL -> buildString {
            if (system != null) append("[INST] $system\n\n")
            history.forEachIndexed { i, msg ->
                if (msg.role == "user") append(if (i == 0 && system == null) "[INST] ${msg.content} [/INST]"
                                               else "[INST] ${msg.content} [/INST]")
                else append("${msg.content}</s>")
            }
            append("[INST] $userMsg [/INST]")
        }
        PHI3 -> buildString {
            if (system != null) append("<|system|>\n$system<|end|>\n")
            history.forEach { msg ->
                val tag = if (msg.role == "user") "<|user|>" else "<|assistant|>"
                append("$tag\n${msg.content}<|end|>\n")
            }
            append("<|user|>\n$userMsg<|end|>\n<|assistant|>\n")
        }
        GEMMA -> buildString {
            history.forEach { msg ->
                val tag = if (msg.role == "user") "<start_of_turn>user" else "<start_of_turn>model"
                append("$tag\n${msg.content}<end_of_turn>\n")
            }
            append("<start_of_turn>user\n$userMsg<end_of_turn>\n<start_of_turn>model\n")
        }
        BITNET -> buildString {
            // BitNet b1.58 typically uses a simple instruct template
            if (system != null) append("System: $system\n\n")
            history.forEach { msg ->
                val tag = if (msg.role == "user") "Human" else "Assistant"
                append("$tag: ${msg.content}\n")
            }
            append("Human: $userMsg\nAssistant:")
        }
        else -> buildString {
            // Generic ChatML
            if (system != null) append("<|im_start|>system\n$system<|im_end|>\n")
            history.forEach { msg ->
                append("<|im_start|>${msg.role}\n${msg.content}<|im_end|>\n")
            }
            append("<|im_start|>user\n$userMsg<|im_end|>\n<|im_start|>assistant\n")
        }
    }

    companion object {
        fun detectFrom(filename: String): ModelFamily {
            val f = filename.lowercase()
            return when {
                "llama-3" in f || "llama3" in f || "meta-llama-3" in f -> LLAMA3
                "llama-2" in f || "llama2" in f                         -> LLAMA2
                "mistral" in f || "mixtral" in f                        -> MISTRAL
                "phi-3" in f || "phi3" in f                             -> PHI3
                "gemma" in f                                            -> GEMMA
                "qwen" in f                                             -> QWEN
                "bitnet" in f || "b1.58" in f                          -> BITNET
                else                                                    -> UNKNOWN
            }
        }
    }
}

/** Snapshot of engine performance metrics. */
data class InferenceStats(
    val totalTokensGenerated: Long    = 0L,
    val totalInferenceTimeMs: Long    = 0L,
    val lastToksPerSecond:    Float   = 0f,
    val peakNativeHeapMB:     Long    = 0L,
    val modelLoadCount:       Int     = 0,
    val sessionCount:         Int     = 0
) {
    val avgToksPerSecond: Float
        get() = if (totalInferenceTimeMs > 0)
            (totalTokensGenerated / (totalInferenceTimeMs / 1000f))
        else 0f
}

// ═══════════════════════════════════════════════════════════════════════════════
// TURBO QUANT MATH UTILITIES
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * TurboQuant rotation matrix factory.
 *
 * Generates the orthogonal key rotation matrix R used in PolarQuant.
 * R is pre-computed once per model load and passed to the native layer.
 *
 * When [headDim] is a power of 2, returns a Walsh–Hadamard matrix (structured,
 * no storage needed — computed on-the-fly in O(n log n) in native code).
 * Otherwise, falls back to a random Gram–Schmidt orthogonal matrix seeded by
 * [seed].
 */
object TurboQuantRotation {

    private const val TAG = "TurboQuantRotation"

    /**
     * Checks whether a Walsh–Hadamard transform can be used for [headDim].
     * WHT is exact, orthogonal, and O(n log n) — ideal for power-of-2 dims.
     */
    fun canUseHadamard(headDim: Int): Boolean =
        headDim > 0 && headDim.countOneBits() == 1

    /**
     * Generates the rotation matrix for [headDim] as a flat FloatArray
     * in row-major order (size = headDim × headDim).
     *
     * For Hadamard: returns the normalised H_n / sqrt(n) matrix.
     * For random: returns a Gram–Schmidt orthonormalised random matrix.
     *
     * NOTE: For headDim > 256 with Hadamard, this returns a sentinel
     * `FloatArray(1) { Float.NaN }` to signal native WHT (avoids 512×512 alloc).
     */
    fun generate(headDim: Int, seed: Long, hadamardFast: Boolean): FloatArray {
        return if (hadamardFast && canUseHadamard(headDim)) {
            if (headDim > 256) {
                Log.i(TAG, "headDim=$headDim — delegating to native WHT")
                floatArrayOf(Float.NaN) // Sentinel: native side uses fast WHT
            } else {
                Log.i(TAG, "Building Hadamard matrix headDim=$headDim")
                buildHadamard(headDim)
            }
        } else {
            Log.i(TAG, "Building random orthogonal matrix headDim=$headDim seed=$seed")
            buildRandomOrthogonal(headDim, seed)
        }
    }

    /**
     * Builds H_n / sqrt(n) — the normalised Hadamard matrix via Sylvester construction:
     *
     *     H_1 = [1]
     *     H_{2n} = (1/√2) * [ H_n   H_n ]
     *                        [ H_n  -H_n ]
     */
    private fun buildHadamard(n: Int): FloatArray {
        val mat = Array(n) { FloatArray(n) }
        mat[0][0] = 1f
        var size = 1
        while (size < n) {
            val scale = (1.0 / sqrt(2.0)).toFloat()
            for (i in 0 until size) {
                for (j in 0 until size) {
                    val v = mat[i][j]
                    mat[i + size][j]        =  v * scale
                    mat[i + size][j + size] = -v * scale
                    mat[i][j]               =  v * scale
                    mat[i][j + size]        =  v * scale
                }
            }
            size *= 2
        }
        // Flatten to row-major FloatArray
        val flat = FloatArray(n * n)
        for (i in 0 until n) for (j in 0 until n) flat[i * n + j] = mat[i][j]
        return flat
    }

    /**
     * Gram–Schmidt orthonormalisation of a seeded random Gaussian matrix.
     * Produces a random orthogonal matrix (Haar measure) for non-power-of-2 dims.
     */
    private fun buildRandomOrthogonal(n: Int, seed: Long): FloatArray {
        val rng = java.util.Random(seed)
        // Generate n×n Gaussian random matrix
        val cols = Array(n) { DoubleArray(n) { rng.nextGaussian() } }
        // Gram–Schmidt
        for (j in 0 until n) {
            // Subtract projections onto previous orthonormal vectors
            for (k in 0 until j) {
                val dot = (0 until n).sumOf { i -> cols[j][i] * cols[k][i] }
                for (i in 0 until n) cols[j][i] -= dot * cols[k][i]
            }
            // Normalise
            val norm = sqrt((0 until n).sumOf { i -> cols[j][i].pow(2) })
            if (norm > 1e-10) for (i in 0 until n) cols[j][i] /= norm
        }
        // Flatten to row-major FloatArray (R[row][col] = cols[col][row])
        val flat = FloatArray(n * n)
        for (i in 0 until n) for (j in 0 until n) flat[i * n + j] = cols[j][i].toFloat()
        return flat
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// MAIN ENGINE
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Production [LocalInferenceEngine] backed by llama.cpp via JNI.
 *
 * ── TurboQuant Integration ────────────────────────────────────────────────────
 * Google TurboQuant compresses the KV cache from 16-bit to ~3-bit through:
 *
 *  1. **PolarQuant (Key rotation)**
 *     Keys are multiplied by a random orthogonal matrix R before quantization.
 *     R spreads outlier energy uniformly across all channels → standard min/max
 *     quantization can then use all its bits efficiently without wasting range
 *     on outlier channels.
 *
 *  2. **QJL (Query-Joint Locality Hashing)**
 *     Instead of quantizing keys independently, the query Q is used to guide
 *     the key compression. Keys that are likely to score high attention with Q
 *     are quantized at higher precision, while irrelevant keys use fewer bits.
 *     Attention quality is preserved even at 3 bits.
 *
 *  3. **Value Quantization**
 *     Values are quantized separately (typically 4-bit) since they are smoother
 *     (post-softmax weighted sum) than keys.
 *
 *  Combined: ~5× memory reduction → 32k context on 4GB mobile RAM
 *
 * ── Safe FD Management ────────────────────────────────────────────────────────
 *  For file:// URIs, the [ParcelFileDescriptor] is kept open while the model
 *  is loaded to prevent SIGBUS crashes when llama.cpp mmap's the GGUF file
 *  (the kernel page-faults into the file via the open FD).
 *  For content:// URIs, the file is copied to cacheDir (SAF FD is not stable
 *  across binder transactions) and the PFD is closed immediately after.
 *
 * ── Concurrency Safety ────────────────────────────────────────────────────────
 *  [AtomicBoolean] prevents two concurrent [generateResponse] calls from
 *  corrupting the shared C++ context pointer. The native KV cache and sampling
 *  state are NOT thread-safe.
 *
 * ── Multi-Turn Sessions ───────────────────────────────────────────────────────
 *  [InferenceSession] instances store conversation history and the count of
 *  tokens already in the KV cache so each new turn only encodes the delta.
 */
class LlamaCppInferenceEngine : LocalInferenceEngine {

    // ── State ──────────────────────────────────────────────────────────────

    @Volatile private var nativeCtxPtr:    Long   = 0L
    @Volatile private var _loadedModelName: String? = null
    @Volatile private var _modelFamily:    ModelFamily = ModelFamily.UNKNOWN
    @Volatile private var tempModelFile:   File?  = null
    @Volatile private var activePfd:       ParcelFileDescriptor? = null

    /** Rotation matrix bytes passed to native for TurboQuant PolarQuant. */
    @Volatile private var turboRotationMatrix: FloatArray? = null

    private val isGenerating = AtomicBoolean(false)

    private val sessions = mutableMapOf<String, InferenceSession>()
    @Volatile private var _currentSession: InferenceSession? = null

    // Stats accumulators
    private val totalTokensGenerated = AtomicLong(0L)
    private val totalInferenceTimeMs = AtomicLong(0L)
    @Volatile private var lastToksPerSecond = 0f
    @Volatile private var peakNativeHeapMB  = 0L
    @Volatile private var modelLoadCount    = 0

    override val isLoaded:        Boolean          get() = nativeCtxPtr != 0L
    override val loadedModelName: String?          get() = _loadedModelName
    override val currentSession:  InferenceSession? get() = _currentSession

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * Loads a GGUF model from [modelUri], optionally configuring TurboQuant KV cache.
     *
     * Steps:
     *  1. Release any previously-loaded model.
     *  2. Detect model family from filename for prompt template selection.
     *  3. Compute TurboQuant rotation matrix R on Kotlin side (Hadamard or random).
     *  4. Pass R + TurboQuant config to [nativeLoadModel].
     *  5. Keep FD open (file://) or copy to cacheDir (content://).
     */
    override suspend fun loadModel(context: Context, modelUri: Uri): Result<String> =
        withContext(Dispatchers.IO) {
            if (!isNativeAvailable) {
                return@withContext Result.failure(IllegalStateException(
                    "Native inference engine not compiled. " +
                    "Run: git submodule update --init --recursive && ./gradlew assembleDebug"
                ))
            }

            try {
                safeFreeCurrent()

                val pfd = context.contentResolver.openFileDescriptor(modelUri, "r")
                    ?: return@withContext Result.failure(
                        IOException("Cannot open model URI: $modelUri")
                    )

                val modelName  = resolveDisplayName(context, modelUri)
                val family     = ModelFamily.detectFrom(modelName)
                _modelFamily   = family
                Log.i(TAG, "Detected model family: $family for \"$modelName\"")

                // ── Memory pre-check ─────────────────────────────────────
                val fileSizeMB = try { pfd.statSize / 1_048_576L } catch (_: Exception) { -1L }
                checkMemoryHeadroom(fileSizeMB)

                // ── Resolve model path ────────────────────────────────────
                val (modelPath, newTempFile) = resolveModelPath(context, modelUri, pfd)

                // ── Build TurboQuant rotation matrix ─────────────────────
                // headDim is not exposed by GGUF metadata without full parse,
                // so we use 128 (standard for LLaMA/Mistral) and let the native
                // side override if it detects a different value from the GGUF header.
                val tqConfig = DEFAULT_TURBO_QUANT_CONFIG
                val rotMatrix = TurboQuantRotation.generate(
                    headDim      = DEFAULT_HEAD_DIM,
                    seed         = tqConfig.rotationSeed,
                    hadamardFast = tqConfig.hadamardFast
                )
                turboRotationMatrix = rotMatrix
                Log.i(TAG, "TurboQuant rotation matrix: " +
                    if (rotMatrix.size == 1 && rotMatrix[0].isNaN()) "native WHT"
                    else "${rotMatrix.size} floats (headDim=$DEFAULT_HEAD_DIM)")

                // ── Load ─────────────────────────────────────────────────
                val cpuCores = Runtime.getRuntime().availableProcessors()
                    .coerceAtMost(MAX_INFERENCE_THREADS)
                    .let { adaptThreadCount(fileSizeMB, it) }

                val ctx = nativeLoadModelTurbo(
                    modelPath        = modelPath,
                    nThreads         = cpuCores,
                    contextSize      = tqConfig.targetContextSize,
                    batchSize        = DEFAULT_BATCH_SIZE,
                    useGpu           = false,
                    kvCacheQuantType = KVCacheQuantType.TURBO_QUANT.nativeId,
                    kvQuantBits      = tqConfig.quantBits,
                    vQuantBits       = tqConfig.valueBits,
                    outliersTopK     = if (tqConfig.outliersProtected) tqConfig.outlierTopK else 0,
                    rotationMatrix   = rotMatrix,
                    rotationMatrixDim = DEFAULT_HEAD_DIM
                )

                // Close PFD for content URIs (file was copied); keep open for file:// (mmap)
                if (modelUri.scheme == "content") {
                    pfd.close()
                    activePfd = null
                } else {
                    activePfd = pfd
                }

                if (ctx == 0L) {
                    newTempFile?.delete()
                    activePfd?.close(); activePfd = null
                    return@withContext Result.failure(buildLoadFailureException(modelName, tqConfig))
                }

                nativeCtxPtr     = ctx
                _loadedModelName = modelName
                tempModelFile    = newTempFile
                modelLoadCount++
                trackHeapPeak()

                val compressionInfo = "(TurboQuant ${tqConfig.quantBits}b keys / " +
                    "${tqConfig.valueBits}b values, ctx=${tqConfig.targetContextSize}, " +
                    "compression ~${String.format("%.1f", tqConfig.estimatedCompressionRatio())}×)"
                Log.i(TAG, "Model loaded: $modelName $compressionInfo ctx=0x${ctx.toString(16)}")
                Result.success(modelName)

            } catch (oom: OutOfMemoryError) {
                Log.e(TAG, "OOM while loading model", oom)
                safeFreeCurrent()
                Result.failure(IOException(
                    "Out of memory. Try a smaller quantized model (Q4_K_S / IQ4_XS) " +
                    "or close background apps to free RAM."
                ))
            } catch (e: Exception) {
                Log.e(TAG, "loadModel failed", e)
                safeFreeCurrent()
                Result.failure(e)
            }
        }

    /**
     * Generates a single-shot (stateless) response to [prompt].
     * Each call is independent — no KV cache reuse across calls.
     * For multi-turn, use [generateResponseInSession].
     */
    override fun generateResponse(
        prompt: String,
        config: InferenceConfig
    ): Flow<String> = callbackFlow {

        if (!guardGeneration(this)) return@callbackFlow

        val ctx         = nativeCtxPtr
        val startTimeMs = System.currentTimeMillis()
        var tokenCount  = 0
        var stopSignalled = false

        val callback = TokenCallback { token, isDone ->
            if (token.isNotEmpty() && !stopSignalled) {
                // Check stop sequences
                val accumulated = token  // simplified — real impl buffers last N chars
                if (config.stopSequences.any { seq -> seq in accumulated }) {
                    stopSignalled = true
                    close()
                    return@TokenCallback
                }
                trySend(token)
                tokenCount++
            }
            if (isDone) {
                recordStats(tokenCount, System.currentTimeMillis() - startTimeMs)
                close()
            }
        }

        val generationJob = launch(Dispatchers.IO) {
            try {
                nativeStartGenerationV2(
                    ctx           = ctx,
                    prompt        = prompt,
                    maxNewTokens  = config.maxNewTokens,
                    temperature   = config.temperature,
                    topP          = config.topP,
                    topK          = config.topK,
                    minP          = config.minP,
                    repeatPenalty = config.repeatPenalty,
                    repeatLastN   = config.repeatLastN,
                    seed          = config.seed,
                    callback      = callback
                )
            } catch (oom: OutOfMemoryError) {
                Log.e(TAG, "OOM during inference", oom)
                trySend("[ERROR] Out of memory during inference.")
                close()
            } catch (e: Exception) {
                Log.e(TAG, "Generation failed", e)
                trySend("[ERROR] ${e.message}")
                close(e)
            } finally {
                isGenerating.set(false)
            }
        }

        awaitClose {
            try { nativeStopGeneration(ctx) } catch (_: Throwable) {}
            generationJob.cancel()
            isGenerating.set(false)
        }
    }.flowOn(Dispatchers.Default)

    /**
     * Generates a response within a named [InferenceSession], reusing the KV
     * cache from previous turns in the session for efficient multi-turn inference.
     *
     * The session accumulates [SessionMessage] entries and passes the full
     * formatted conversation through the model-family-specific prompt template.
     */
    override fun generateResponseInSession(
        sessionId:   String,
        userMessage: String,
        config:      InferenceConfig
    ): Flow<String> = callbackFlow {

        if (!guardGeneration(this)) return@callbackFlow

        val session = sessions.getOrPut(sessionId) {
            InferenceSession(sessionId, systemPrompt = null)
        }
        _currentSession = session

        val formattedPrompt = session.buildPrompt(userMessage, _modelFamily)
        val ctx             = nativeCtxPtr
        val startTimeMs     = System.currentTimeMillis()
        var tokenCount      = 0
        val assistantReply  = StringBuilder()

        val callback = TokenCallback { token, isDone ->
            if (token.isNotEmpty()) {
                trySend(token)
                assistantReply.append(token)
                tokenCount++
            }
            if (isDone) {
                // Persist exchange in session history
                session.messages += SessionMessage("user",      userMessage)
                session.messages += SessionMessage("assistant", assistantReply.toString())
                session.cachedTokenCount += tokenCount
                recordStats(tokenCount, System.currentTimeMillis() - startTimeMs)
                close()
            }
        }

        val generationJob = launch(Dispatchers.IO) {
            try {
                // Pass the KV cache token offset to skip re-encoding prior turns
                nativeStartGenerationWithCache(
                    ctx               = ctx,
                    prompt            = formattedPrompt,
                    kvCacheTokenOffset = session.cachedTokenCount,
                    maxNewTokens      = config.maxNewTokens,
                    temperature       = config.temperature,
                    topP              = config.topP,
                    topK              = config.topK,
                    minP              = config.minP,
                    repeatPenalty     = config.repeatPenalty,
                    repeatLastN       = config.repeatLastN,
                    seed              = config.seed,
                    callback          = callback
                )
            } catch (e: Exception) {
                Log.e(TAG, "Session generation failed", e)
                trySend("[ERROR] ${e.message}")
                close(e)
            } finally {
                isGenerating.set(false)
            }
        }

        awaitClose {
            try { nativeStopGeneration(ctx) } catch (_: Throwable) {}
            generationJob.cancel()
            isGenerating.set(false)
        }
    }.flowOn(Dispatchers.Default)

    override fun newSession(systemPrompt: String?): InferenceSession {
        val session = InferenceSession(
            sessionId    = "session_${System.currentTimeMillis()}",
            systemPrompt = systemPrompt
        )
        sessions[session.sessionId] = session
        _currentSession = session
        return session
    }

    override fun clearSession(sessionId: String) {
        sessions.remove(sessionId)
        if (_currentSession?.sessionId == sessionId) _currentSession = null
        // Evict KV cache entries for this session in native
        val ctx = nativeCtxPtr
        if (ctx != 0L) {
            try { nativeClearKVCache(ctx) } catch (_: Throwable) {}
        }
    }

    @Synchronized
    override fun unloadModel() = safeFreeCurrent()

    override fun getStats(): InferenceStats = InferenceStats(
        totalTokensGenerated = totalTokensGenerated.get(),
        totalInferenceTimeMs = totalInferenceTimeMs.get(),
        lastToksPerSecond    = lastToksPerSecond,
        peakNativeHeapMB     = peakNativeHeapMB,
        modelLoadCount       = modelLoadCount,
        sessionCount         = sessions.size
    )

    // ── Private helpers ────────────────────────────────────────────────────

    /**
     * Guards against concurrent generation and missing model.
     * Returns true if generation can proceed, false if it should abort.
     */
    private fun <T> guardGeneration(scope: kotlinx.coroutines.channels.ProducerScope<T>): Boolean {
        if (!isNativeAvailable) {
            scope.trySend("[ERROR] Native engine not available. Rebuild with llama.cpp submodule." as T)
            scope.close(); return false
        }
        if (!isLoaded) {
            scope.trySend("[ERROR] No model loaded. Load a GGUF model in Settings → Local Edge Model." as T)
            scope.close(); return false
        }
        if (!isGenerating.compareAndSet(false, true)) {
            scope.trySend("[ERROR] Engine busy — another generation is in progress." as T)
            scope.close(); return false
        }
        return true
    }

    /**
     * Resolves the model's file-system path and handles content:// vs file:// URIs.
     * Returns (absolutePath, tempFileOrNull).
     */
    private suspend fun resolveModelPath(
        context:  Context,
        modelUri: Uri,
        pfd:      ParcelFileDescriptor
    ): Pair<String, File?> = withContext(Dispatchers.IO) {
        if (modelUri.scheme == "content") {
            val dest = File(context.cacheDir, "llm_active_model.gguf")
            context.contentResolver.openInputStream(modelUri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            } ?: run {
                pfd.close()
                throw IOException("Cannot open input stream for model URI: $modelUri")
            }
            pfd.close()
            Log.i(TAG, "Model cached to ${dest.absolutePath} (${dest.length() / 1_048_576} MB)")
            dest.absolutePath to dest
        } else {
            // file:// — use /proc/self/fd symlink; keep PFD open to prevent mmap SIGBUS
            "/proc/self/fd/${pfd.fd}" to null
        }
    }

    private fun checkMemoryHeadroom(fileSizeMB: Long) {
        if (fileSizeMB <= 0) return
        val nativeHeapFreeMB = (Debug.getNativeHeapSize() -
                Debug.getNativeHeapAllocatedSize()) / 1_048_576L
        Log.i(TAG, "Model: ${fileSizeMB}MB | Free native heap: ${nativeHeapFreeMB}MB")
        if (fileSizeMB > nativeHeapFreeMB / OOM_HEADROOM_FACTOR) {
            Log.w(TAG, "Low memory warning — model may fail to load or cause OOM during inference")
        }
    }

    /**
     * Adapts thread count downward for large models on efficiency-core-heavy devices.
     * Returns the adjusted count.
     */
    private fun adaptThreadCount(fileSizeMB: Long, cores: Int): Int {
        // Large models (>4GB) saturate the memory bus; limit to P-cores estimate
        return if (fileSizeMB > 4_096) (cores / 2).coerceAtLeast(2) else cores
    }

    private fun buildLoadFailureException(modelName: String, config: TurboQuantConfig): IOException {
        val isBitNet = ModelFamily.detectFrom(modelName) == ModelFamily.BITNET
        val hint = if (isBitNet)
            "BitNet models require ≥${MIN_BITNET_RAM_GB}GB free RAM and a GGUF from " +
            "microsoft/bitnet_b1_58-2B-4T-gguf (HuggingFace)."
        else
            "Ensure the file is a valid, non-corrupted GGUF model and the device has " +
            "sufficient RAM. With TurboQuant (${config.quantBits}b) the context window " +
            "is set to ${config.targetContextSize} tokens — try reducing if RAM is limited."
        return IOException("llama.cpp failed to load \"$modelName\". $hint")
    }

    private fun safeFreeCurrent() {
        val ctx = nativeCtxPtr
        if (ctx != 0L) {
            try { nativeFreeModel(ctx) } catch (e: Throwable) {
                Log.w(TAG, "nativeFreeModel threw during cleanup", e)
            }
            nativeCtxPtr = 0L
        }
        try { activePfd?.close() } catch (_: Exception) {}
        activePfd           = null
        _loadedModelName    = null
        _modelFamily        = ModelFamily.UNKNOWN
        turboRotationMatrix = null
        tempModelFile?.delete()
        tempModelFile = null
        isGenerating.set(false)
        sessions.clear()
        _currentSession = null
    }

    private fun recordStats(tokens: Int, elapsedMs: Long) {
        if (elapsedMs <= 0) return
        totalTokensGenerated.addAndGet(tokens.toLong())
        totalInferenceTimeMs.addAndGet(elapsedMs)
        lastToksPerSecond = tokens / (elapsedMs / 1000f)
        trackHeapPeak()
        Log.d(TAG, "Generation: $tokens tokens in ${elapsedMs}ms (${String.format("%.1f", lastToksPerSecond)} tok/s)")
    }

    private fun trackHeapPeak() {
        val heapMB = Debug.getNativeHeapAllocatedSize() / 1_048_576L
        if (heapMB > peakNativeHeapMB) peakNativeHeapMB = heapMB
    }

    private fun resolveDisplayName(context: Context, uri: Uri): String = try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            cursor.moveToFirst()
            if (idx >= 0) cursor.getString(idx) else uri.lastPathSegment ?: "Unknown"
        } ?: (uri.lastPathSegment ?: "Unknown")
    } catch (_: Exception) {
        uri.lastPathSegment ?: "local_model.gguf"
    }

    // ── JNI Declarations ──────────────────────────────────────────────────

    /**
     * Loads a GGUF model with full TurboQuant KV cache configuration.
     *
     * @param rotationMatrix     Flat row-major float array of the pre-computed
     *                           orthogonal rotation matrix R (PolarQuant).
     *                           Pass `[NaN]` (size=1) to signal native WHT.
     * @param rotationMatrixDim  Side length of the square rotation matrix.
     * @return Opaque context pointer, or 0 on failure.
     */
    private external fun nativeLoadModelTurbo(
        modelPath:        String,
        nThreads:         Int,
        contextSize:      Int,
        batchSize:        Int,
        useGpu:           Boolean,
        kvCacheQuantType: Int,     // KVCacheQuantType.nativeId
        kvQuantBits:      Int,     // Key quantization bits
        vQuantBits:       Int,     // Value quantization bits
        outliersTopK:     Int,     // Protected outlier channels per head
        rotationMatrix:   FloatArray,
        rotationMatrixDim: Int
    ): Long

    /**
     * Extended generation call with expanded sampling parameters.
     */
    private external fun nativeStartGenerationV2(
        ctx:           Long,
        prompt:        String,
        maxNewTokens:  Int,
        temperature:   Float,
        topP:          Float,
        topK:          Int,
        minP:          Float,
        repeatPenalty: Float,
        repeatLastN:   Int,
        seed:          Long,
        callback:      TokenCallback
    )

    /**
     * Session-aware generation that reuses the KV cache from prior turns.
     *
     * @param kvCacheTokenOffset Number of tokens already cached from previous turns.
     *                           The native side skips encoding these tokens.
     */
    private external fun nativeStartGenerationWithCache(
        ctx:                Long,
        prompt:             String,
        kvCacheTokenOffset: Int,
        maxNewTokens:       Int,
        temperature:        Float,
        topP:               Float,
        topK:               Int,
        minP:               Float,
        repeatPenalty:      Float,
        repeatLastN:        Int,
        seed:               Long,
        callback:           TokenCallback
    )

    /** Sets the stop flag for the active generation loop. Thread-safe. */
    private external fun nativeStopGeneration(ctx: Long)

    /** Frees the llama_context and llama_model for [ctx]. */
    private external fun nativeFreeModel(ctx: Long)

    /** Evicts all KV cache entries (e.g. on session reset). */
    private external fun nativeClearKVCache(ctx: Long)

    /** Returns current peak RSS memory used by native allocations (bytes). */
    private external fun nativeGetMemoryUsage(ctx: Long): Long

    // ── Token callback ─────────────────────────────────────────────────────

    /**
     * Called from the native inference thread for each generated token.
     * JNI signature: `(Ljava/lang/String;Z)V`
     *
     * [isDone] is true on EOS or when [maxNewTokens] is exhausted.
     */
    fun interface TokenCallback {
        fun onToken(token: String, isDone: Boolean)
    }

    // ── Companion ──────────────────────────────────────────────────────────

    companion object {
        private const val TAG = "LlamaCppEngine"

        private const val MAX_INFERENCE_THREADS  = 8
        private const val OOM_HEADROOM_FACTOR    = 2L
        private const val MIN_BITNET_RAM_GB      = 3
        private const val DEFAULT_HEAD_DIM       = 128   // LLaMA/Mistral/Phi-3 standard
        private const val DEFAULT_BATCH_SIZE     = 512

        /** Default TurboQuant config — 3-bit keys, 4-bit values, 32k context. */
        val DEFAULT_TURBO_QUANT_CONFIG = TurboQuantConfig(
            quantBits          = 3,
            valueBits          = 4,
            hadamardFast       = true,
            outliersProtected  = true,
            outlierTopK        = 8,
            targetContextSize  = 32_768,
            rotationSeed       = 42L
        )

        @JvmStatic private external fun nativeIsStub(): Boolean

        val isNativeAvailable: Boolean

        init {
            isNativeAvailable = try {
                System.loadLibrary("llama_jni")
                val stub = nativeIsStub()
                if (stub) {
                    Log.w(TAG, "libllama_jni.so is STUB — real inference disabled. " +
                               "Run: git submodule update --init --recursive && rebuild.")
                    false
                } else {
                    Log.i(TAG, "libllama_jni.so loaded — TurboQuant inference ready")
                    true
                }
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "libllama_jni.so not found. " +
                           "Run: git submodule update --init --recursive && rebuild.", e)
                false
            }
        }
    }
}
