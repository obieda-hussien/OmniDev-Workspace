/**
 * llama_jni.cpp — JNI bridge between LlamaCppInferenceEngine.kt and llama.cpp
 *
 * Prerequisites
 * ─────────────
 * git submodule add https://github.com/ggerganov/llama.cpp.git app/src/main/cpp/llama.cpp
 * git submodule update --init --recursive
 *
 * This file uses the llama.cpp C API (llama.h / ggml.h).
 * Tested against llama.cpp tag b5050+ (sampler-chain API).
 */

#include <jni.h>
#include <string>
#include <vector>
#include <atomic>
#include <cstring>
#include <android/log.h>

#include "llama.h"

#define LOG_TAG "llama_jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ── Named constants ─────────────────────────────────────────────────────────

// Passing n_gpu_layers=INT_MAX (or any large number) tells llama.cpp to offload
// all model layers to the GPU.  99 is the conventional sentinel in llama.cpp
// examples; INT_MAX would also work.
static constexpr int GPU_ALL_LAYERS          = 99;

// Number of recent tokens inspected by the repetition penalty sampler.
// 64 is the llama.cpp default and performs well empirically for chat workloads.
static constexpr int REPEAT_PENALTY_WINDOW   = 64;

// Maximum UTF-8 bytes produced by a single token piece (llama.cpp tokens are
// typically 1–6 bytes; 256 is a safe upper bound).
static constexpr int TOKEN_PIECE_BUFFER_SIZE = 256;

// Fixed RNG seed for the llama.cpp sampler.  Using a non-zero constant gives
// reproducible outputs; swap for time(nullptr) if stochastic sampling is needed.
static constexpr uint32_t SAMPLER_SEED       = 0xC0FFEE42;



struct LlamaCtx {
    llama_model*         model   = nullptr;
    llama_context*       ctx     = nullptr;
    std::atomic<bool>    stop    {false};
};

static inline LlamaCtx* to_ctx(jlong ptr) {
    return reinterpret_cast<LlamaCtx*>(static_cast<uintptr_t>(ptr));
}

// ── JNI helpers ─────────────────────────────────────────────────────────────

static std::string jstring_to_str(JNIEnv* env, jstring js) {
    if (!js) return {};
    const char* chars = env->GetStringUTFChars(js, nullptr);
    std::string s(chars);
    env->ReleaseStringUTFChars(js, chars);
    return s;
}

// ── JNI implementations ─────────────────────────────────────────────────────

extern "C" {

// ─── nativeIsStub ───────────────────────────────────────────────────────────
// Returns JNI_FALSE — this is the REAL llama.cpp library, not the stub.
// The stub counterpart in llama_jni_stub.cpp returns JNI_TRUE.
JNIEXPORT jboolean JNICALL
Java_com_omnidev_workspace_data_localllm_LlamaCppInferenceEngine_nativeIsStub(
        JNIEnv*, jclass)
{
    return JNI_FALSE;
}

// ─── nativeLoadModel ────────────────────────────────────────────────────────
JNIEXPORT jlong JNICALL
Java_com_omnidev_workspace_data_localllm_LlamaCppInferenceEngine_nativeLoadModel(
        JNIEnv* env, jobject /* thiz */,
        jstring jModelPath, jint nThreads, jint contextSize, jint batchSize, jboolean useGpu)
{
    std::string modelPath = jstring_to_str(env, jModelPath);
    LOGI("Loading model: %s  threads=%d  ctx=%d  batch=%d  gpu=%d",
         modelPath.c_str(), nThreads, contextSize, batchSize, useGpu);

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = useGpu ? GPU_ALL_LAYERS : 0;

    llama_model* model = llama_load_model_from_file(modelPath.c_str(), mparams);
    if (!model) {
        LOGE("llama_load_model_from_file failed: %s", modelPath.c_str());
        return 0;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx          = (uint32_t)contextSize;
    cparams.n_batch        = (uint32_t)batchSize;
    cparams.n_ubatch       = (uint32_t)batchSize;
    cparams.n_threads      = (uint32_t)nThreads;
    cparams.n_threads_batch = (uint32_t)nThreads;

    llama_context* ctx = llama_new_context_with_model(model, cparams);
    if (!ctx) {
        LOGE("llama_new_context_with_model failed");
        llama_free_model(model);
        return 0;
    }

    auto* llamaCtx = new LlamaCtx{model, ctx, false};
    LOGI("Model loaded OK — ctx pointer: %p", (void*)llamaCtx);
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(llamaCtx));
}

// ─── nativeStartGeneration ──────────────────────────────────────────────────
JNIEXPORT void JNICALL
Java_com_omnidev_workspace_data_localllm_LlamaCppInferenceEngine_nativeStartGeneration(
        JNIEnv* env, jobject /* thiz */,
        jlong ctxPtr, jstring jPrompt, jint maxNewTokens,
        jfloat temperature, jfloat topP, jfloat repeatPenalty,
        jobject callback)
{
    LlamaCtx* llamaCtx = to_ctx(ctxPtr);
    if (!llamaCtx || !llamaCtx->ctx || !llamaCtx->model) {
        LOGE("nativeStartGeneration called with invalid context");
        return;
    }
    llamaCtx->stop = false;

    // ── Resolve callback method ID ──────────────────────────────────────────
    jclass  cbClass  = env->GetObjectClass(callback);
    jmethodID onToken = env->GetMethodID(cbClass, "onToken", "(Ljava/lang/String;Z)V");
    if (!onToken) {
        LOGE("Cannot find onToken method on TokenCallback");
        return;
    }

    // ── Helper: emit one token to Kotlin ────────────────────────────────────
    auto emit = [&](const char* piece, bool done) {
        jstring js = env->NewStringUTF(piece ? piece : "");
        env->CallVoidMethod(callback, onToken, js, (jboolean)(done ? JNI_TRUE : JNI_FALSE));
        env->DeleteLocalRef(js);
    };

    // ── Tokenise the prompt ──────────────────────────────────────────────────
    std::string prompt = jstring_to_str(env, jPrompt);

    const llama_vocab* vocab = llama_model_get_vocab(llamaCtx->model);

    // Estimate max tokens (prompt + response)
    int n_ctx = (int)llama_n_ctx(llamaCtx->ctx);
    std::vector<llama_token> promptTokens(n_ctx);

    int n_prompt = llama_tokenize(
            vocab,
            prompt.c_str(), (int32_t)prompt.size(),
            promptTokens.data(), (int32_t)promptTokens.size(),
            /* add_special= */ true,
            /* parse_special= */ true);

    if (n_prompt < 0) {
        LOGE("Tokenisation failed (required buf=%d)", -n_prompt);
        emit("", true);
        return;
    }
    promptTokens.resize(n_prompt);
    LOGI("Prompt tokenised: %d tokens", n_prompt);

    // ── Evaluate prompt batch ────────────────────────────────────────────────
    llama_batch batch = llama_batch_init((int32_t)promptTokens.size(), 0, 1);
    for (int i = 0; i < (int)promptTokens.size(); i++) {
        batch.token   [i]    = promptTokens[i];
        batch.pos     [i]    = i;
        batch.n_seq_id[i]    = 1;
        batch.seq_id  [i][0] = 0;
        batch.logits  [i]    = 0;
    }
    batch.n_tokens                  = (int32_t)promptTokens.size();
    batch.logits[batch.n_tokens - 1] = 1; // compute logits only for last token

    if (llama_decode(llamaCtx->ctx, batch) != 0) {
        LOGE("llama_decode failed for prompt");
        llama_batch_free(batch);
        emit("", true);
        return;
    }
    llama_batch_free(batch);

    // ── Build sampler chain ──────────────────────────────────────────────────
    llama_sampler* smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(topP, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_penalties(
            /*n_prev=*/  REPEAT_PENALTY_WINDOW,
            /*penalty_repeat=*/    repeatPenalty,
            /*penalty_freq=*/      0.0f,
            /*penalty_present=*/   0.0f));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(SAMPLER_SEED));

    // ── Auto-regressive generation loop ─────────────────────────────────────
    int n_cur = (int)promptTokens.size();
    char piece_buf[TOKEN_PIECE_BUFFER_SIZE];

    for (int i = 0; i < maxNewTokens && !llamaCtx->stop; ++i) {
        llama_token new_token = llama_sampler_sample(smpl, llamaCtx->ctx, -1);

        bool is_eog  = llama_token_is_eog(vocab, new_token);
        bool is_last = is_eog || (i == maxNewTokens - 1) || (bool)llamaCtx->stop;

        // Decode token → UTF-8 piece
        memset(piece_buf, 0, sizeof(piece_buf));
        int nChars = llama_token_to_piece(
                vocab, new_token,
                piece_buf, (int32_t)(sizeof(piece_buf) - 1),
                /*lstrip=*/ 0,
                /*special=*/ false);

        if (nChars > 0) {
            piece_buf[nChars] = '\0';
            emit(piece_buf, is_last);
        } else if (is_last) {
            emit("", true);
        }

        if (is_eog || llamaCtx->stop) break;

        // Advance context with the new token
        llama_batch next = llama_batch_init(1, 0, 1);
        next.token   [0]    = new_token;
        next.pos     [0]    = n_cur++;
        next.n_seq_id[0]    = 1;
        next.seq_id  [0][0] = 0;
        next.logits  [0]    = 1;
        next.n_tokens       = 1;

        if (llama_decode(llamaCtx->ctx, next) != 0) {
            LOGE("llama_decode failed for token %d", i);
            llama_batch_free(next);
            break;
        }
        llama_batch_free(next);
    }

    llama_sampler_free(smpl);

    // Ensure the flow is always closed even if the loop exited early
    emit("", true);
    LOGI("Generation complete: %d new tokens produced", n_cur - (int)promptTokens.size());
}

// ─── nativeStopGeneration ───────────────────────────────────────────────────
JNIEXPORT void JNICALL
Java_com_omnidev_workspace_data_localllm_LlamaCppInferenceEngine_nativeStopGeneration(
        JNIEnv* env, jobject /* thiz */, jlong ctxPtr)
{
    LlamaCtx* llamaCtx = to_ctx(ctxPtr);
    if (llamaCtx) {
        LOGI("Stop generation requested");
        llamaCtx->stop = true;
    }
}

// ─── nativeFreeModel ────────────────────────────────────────────────────────
JNIEXPORT void JNICALL
Java_com_omnidev_workspace_data_localllm_LlamaCppInferenceEngine_nativeFreeModel(
        JNIEnv* env, jobject /* thiz */, jlong ctxPtr)
{
    LlamaCtx* llamaCtx = to_ctx(ctxPtr);
    if (llamaCtx) {
        LOGI("Freeing model context %p", (void*)llamaCtx);
        llamaCtx->stop = true;
        if (llamaCtx->ctx)   llama_free(llamaCtx->ctx);
        if (llamaCtx->model) llama_free_model(llamaCtx->model);
        delete llamaCtx;
    }
}

} // extern "C"
