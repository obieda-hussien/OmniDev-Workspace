/**
 * llama_jni_stub.cpp — Stub JNI library compiled when the llama.cpp submodule
 * is not present (e.g. fresh clone, CI without submodule init).
 *
 * All functions return failure values so LlamaCppInferenceEngine.loadModel()
 * returns Result.failure with a clear message instead of crashing with
 * UnsatisfiedLinkError.  The companion's nativeIsStub() returns JNI_TRUE,
 * causing isNativeAvailable to be false and loadModel() to reject loads
 * before even reaching the native layer.
 *
 * Activate: this file is compiled automatically by CMakeLists.txt when
 *           app/src/main/cpp/llama.cpp/CMakeLists.txt is absent.
 *           Run `git submodule update --init --recursive` then rebuild to
 *           switch to the real llama_jni.cpp implementation.
 */

#include <jni.h>
#include <android/log.h>

#define LOG_TAG "llama_jni_stub"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_omnidev_workspace_data_localllm_LlamaCppInferenceEngine_nativeIsStub(
        JNIEnv*, jclass)
{
    return JNI_TRUE; // stub build — real llama.cpp not compiled in
}

JNIEXPORT jlong JNICALL
Java_com_omnidev_workspace_data_localllm_LlamaCppInferenceEngine_nativeLoadModel(
        JNIEnv*, jobject, jstring, jint, jint, jint, jboolean)
{
    LOGW("nativeLoadModel: llama.cpp submodule not initialised — "
         "run `git submodule update --init --recursive` and rebuild.");
    return 0; // 0 → LlamaCppInferenceEngine.loadModel() returns Result.failure
}

JNIEXPORT void JNICALL
Java_com_omnidev_workspace_data_localllm_LlamaCppInferenceEngine_nativeStartGeneration(
        JNIEnv* env, jobject, jlong, jstring, jint, jfloat, jfloat, jfloat, jobject callback)
{
    LOGW("nativeStartGeneration: stub — no inference possible");
    jclass  cbClass  = env->GetObjectClass(callback);
    jmethodID onToken = env->GetMethodID(cbClass, "onToken", "(Ljava/lang/String;Z)V");
    if (onToken) {
        jstring msg = env->NewStringUTF(
            "[STUB] llama.cpp submodule not initialised. "
            "Run `git submodule update --init --recursive` and rebuild the app.");
        env->CallVoidMethod(callback, onToken, msg, (jboolean)JNI_TRUE);
        env->DeleteLocalRef(msg);
    }
}

JNIEXPORT void JNICALL
Java_com_omnidev_workspace_data_localllm_LlamaCppInferenceEngine_nativeStopGeneration(
        JNIEnv*, jobject, jlong) {}

JNIEXPORT void JNICALL
Java_com_omnidev_workspace_data_localllm_LlamaCppInferenceEngine_nativeFreeModel(
        JNIEnv*, jobject, jlong) {}

} // extern "C"
