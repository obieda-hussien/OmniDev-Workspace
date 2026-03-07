/**
 * bitnet_jni.cpp — JNI bridge between BitnetInferenceEngine.kt and BitNet.cpp
 *
 * Prerequisites
 * ─────────────
 * git submodule add https://github.com/microsoft/BitNet.git app/src/main/cpp/bitnet
 * git submodule update --init --recursive
 *
 * BitNet.cpp (https://github.com/microsoft/BitNet) is a fork of llama.cpp
 * optimised for 1-bit (BitNet b1.58) quantised models.  Its C API mirrors
 * llama.cpp — the same llama.h header is exposed from the bitnet/ include
 * directory, and the same llama_context / llama_model types are used.
 *
 * Tested against BitNet.cpp tag v0.1.1+ (ARM TL1/TL2 kernel support).
 */

#include <jni.h>
#include <string>
#include <vector>
#include <atomic>
#include <cstring>
#include <android/log.h>

// BitNet.cpp exposes the same llama.h public API as llama.cpp (it's a fork).
// Headers are sourced from bitnet/include/ via CMake target_include_directories.
#include "llama.h"

#define LOG_TAG "bitnet_jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ── Named constants ──────────────────────────────────────────────────────────

static constexpr int GPU_ALL_LAYERS          = 99;
static constexpr int REPEAT_PENALTY_WINDOW   = 64;
static constexpr int TOKEN_PIECE_BUFFER_SIZE = 256;
// Fixed RNG seed for BitNet sampler (distinct from the llama_jni.cpp seed 0xC0FFEE42).
// "B17B1758" is a mnemonic for "BitNet b1.58", the 1-bit quantisation scheme.
static constexpr uint32_t SAMPLER_SEED       = 0xB17B1758;

// ── Context struct ───────────────────────────────────────────────────────────

struct BitnetCtx {
    llama_model*         model   = nullptr;
    llama_context*       ctx     = nullptr;
    std::atomic<bool>    stop    {false};
};

static inline BitnetCtx* to_ctx(jlong ptr) {
    return reinterpret_cast<BitnetCtx*>(static_cast<uintptr_t>(ptr));
}

// ── JNI helpers ──────────────────────────────────────────────────────────────

static std::string jstring_to_str(JNIEnv* env, jstring js) {
    if (!js) return {};
    const char* chars = env->GetStringUTFChars(js, nullptr);
    std::string s(chars);
    env->ReleaseStringUTFChars(js, chars);
    return s;
}

// ── JNI implementations ──────────────────────────────────────────────────────

extern "C" {

// ─── nativeIsStub ─────────────────────────────────────────────────────────
// Returns JNI_FALSE — this is the REAL BitNet.cpp library, not the stub.
JNIEXPORT jboolean JNICALL
Java_com_omnidev_workspace_data_localllm_BitnetInferenceEngine_nativeIsStub(
        JNIEnv*, jclass)
{
    return JNI_FALSE;
}

// ─── nativeLoadModel ──────────────────────────────────────────────────────
JNIEXPORT jlong JNICALL
Java_com_omnidev_workspace_data_localllm_BitnetInferenceEngine_nativeLoadModel(
        JNIEnv*  env,
        jobject  /* this */,
        jstring  jModelPath,
        jint     nThreads,
        jint     contextSize,
        jint     batchSize,
        jboolean useGpu)
{
    const std::string modelPath = jstring_to_str(env, jModelPath);
    LOGI("nativeLoadModel: path=%s threads=%d ctx=%d batch=%d gpu=%d",
         modelPath.c_str(), (int)nThreads, (int)contextSize, (int)batchSize, (int)useGpu);

    llama_backend_init();

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = useGpu ? GPU_ALL_LAYERS : 0;

    llama_model* model = llama_model_load_from_file(modelPath.c_str(), mparams);
    if (!model) {
        LOGE("nativeLoadModel: llama_model_load_from_file returned null");
        return 0;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx      = static_cast<uint32_t>(contextSize);
    cparams.n_batch    = static_cast<uint32_t>(batchSize);
    cparams.n_threads  = static_cast<int32_t>(nThreads);

    llama_context* ctx = llama_new_context_with_model(model, cparams);
    if (!ctx) {
        LOGE("nativeLoadModel: llama_new_context_with_model returned null");
        llama_model_free(model);
        return 0;
    }

    auto* bctx  = new BitnetCtx();
    bctx->model = model;
    bctx->ctx   = ctx;
    bctx->stop.store(false);

    LOGI("nativeLoadModel: success, ctx=0x%lx", (unsigned long)(uintptr_t)bctx);
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(bctx));
}

// ─── nativeStartGeneration ────────────────────────────────────────────────
JNIEXPORT void JNICALL
Java_com_omnidev_workspace_data_localllm_BitnetInferenceEngine_nativeStartGeneration(
        JNIEnv*  env,
        jobject  /* this */,
        jlong    ctxPtr,
        jstring  jPrompt,
        jint     maxNewTokens,
        jfloat   temperature,
        jfloat   topP,
        jfloat   repeatPenalty,
        jobject  callback)
{
    BitnetCtx* bctx = to_ctx(ctxPtr);
    if (!bctx || !bctx->ctx) {
        LOGE("nativeStartGeneration: invalid context pointer");
        return;
    }

    // Reset stop flag and KV cache for multi-turn correctness
    bctx->stop.store(false);
    llama_kv_self_clear(bctx->ctx);

    const std::string prompt = jstring_to_str(env, jPrompt);

    // Tokenise the prompt
    const int n_prompt_max = llama_n_ctx(bctx->ctx);
    std::vector<llama_token> tokens(n_prompt_max);
    int n_tokens = llama_tokenize(
        llama_get_model(bctx->ctx),
        prompt.c_str(), (int)prompt.size(),
        tokens.data(), n_prompt_max,
        /* add_bos */ true, /* special */ true
    );
    if (n_tokens < 0) {
        LOGE("nativeStartGeneration: tokenisation failed (n_tokens=%d)", n_tokens);
        return;
    }
    tokens.resize(n_tokens);

    // Build sampler chain (identical to llama_jni.cpp)
    llama_sampler* sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(sampler, llama_sampler_init_top_p(topP, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(sampler, llama_sampler_init_penalties(
        REPEAT_PENALTY_WINDOW,
        repeatPenalty,
        0.0f,  // freq penalty
        0.0f   // pres penalty
    ));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(SAMPLER_SEED));

    // Decode the prompt in one batch
    llama_batch batch = llama_batch_get_one(tokens.data(), (int32_t)tokens.size());
    if (llama_decode(bctx->ctx, batch) != 0) {
        LOGE("nativeStartGeneration: llama_decode (prompt) failed");
        llama_sampler_free(sampler);
        return;
    }

    // Cache JNI method IDs for the callback
    jclass    cbClass = env->GetObjectClass(callback);
    jmethodID onToken = env->GetMethodID(cbClass, "onToken", "(Ljava/lang/String;Z)V");
    if (!onToken) {
        LOGE("nativeStartGeneration: onToken method not found");
        llama_sampler_free(sampler);
        return;
    }

    // Generation loop
    char tokenBuf[TOKEN_PIECE_BUFFER_SIZE];
    int  generated = 0;

    while (!bctx->stop.load() && generated < (int)maxNewTokens) {
        llama_token new_token = llama_sampler_sample(sampler, bctx->ctx, -1);

        if (llama_vocab_is_eog(llama_get_model(bctx->ctx), new_token)) {
            // EOS — notify the Kotlin side and exit
            jstring empty = env->NewStringUTF("");
            env->CallVoidMethod(callback, onToken, empty, (jboolean)JNI_TRUE);
            env->DeleteLocalRef(empty);
            break;
        }

        int n = llama_token_to_piece(
            llama_get_model(bctx->ctx),
            new_token,
            tokenBuf, TOKEN_PIECE_BUFFER_SIZE - 1,
            /* lstrip */ 0, /* special */ false
        );
        if (n < 0) n = 0;
        tokenBuf[n] = '\0';

        jstring jToken = env->NewStringUTF(tokenBuf);
        env->CallVoidMethod(callback, onToken, jToken, (jboolean)JNI_FALSE);
        env->DeleteLocalRef(jToken);

        // Decode the newly-generated token to advance the KV cache
        llama_sampler_accept(sampler, new_token);
        llama_batch next_batch = llama_batch_get_one(&new_token, 1);
        if (llama_decode(bctx->ctx, next_batch) != 0) {
            LOGW("nativeStartGeneration: llama_decode (token) failed — stopping");
            break;
        }
        ++generated;
    }

    // Send final done=true if we exited due to max_tokens or stop flag
    if (bctx->stop.load() || generated >= (int)maxNewTokens) {
        jstring empty = env->NewStringUTF("");
        env->CallVoidMethod(callback, onToken, empty, (jboolean)JNI_TRUE);
        env->DeleteLocalRef(empty);
    }

    llama_sampler_free(sampler);
    LOGI("nativeStartGeneration: done (generated=%d)", generated);
}

// ─── nativeStopGeneration ─────────────────────────────────────────────────
JNIEXPORT void JNICALL
Java_com_omnidev_workspace_data_localllm_BitnetInferenceEngine_nativeStopGeneration(
        JNIEnv*, jobject, jlong ctxPtr)
{
    BitnetCtx* bctx = to_ctx(ctxPtr);
    if (bctx) bctx->stop.store(true);
}

// ─── nativeFreeModel ──────────────────────────────────────────────────────
JNIEXPORT void JNICALL
Java_com_omnidev_workspace_data_localllm_BitnetInferenceEngine_nativeFreeModel(
        JNIEnv*, jobject, jlong ctxPtr)
{
    BitnetCtx* bctx = to_ctx(ctxPtr);
    if (!bctx) return;
    if (bctx->ctx)   llama_free(bctx->ctx);
    if (bctx->model) llama_model_free(bctx->model);
    delete bctx;
    LOGI("nativeFreeModel: context freed");
}

} // extern "C"
