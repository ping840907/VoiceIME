#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <chrono>

#include "whisper.h"

#define LOG_TAG "WhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ── Helper: JNI method name mangling for com.ping.elderlyassistant.engine.WhisperEngine
#define JNIFUNC(name) \
    Java_com_ping_elderlyassistant_engine_WhisperEngine_##name

extern "C" {

/**
 * Load a ggml whisper model from disk.
 * Returns a native pointer cast to jlong, or 0 on failure.
 */
JNIEXPORT jlong JNICALL
JNIFUNC(nativeInit)(JNIEnv *env, jobject /* obj */, jstring modelPath) {
    const char *path = env->GetStringUTFChars(modelPath, nullptr);

    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;   // Whisper runs on CPU; GPU is reserved for the LLM

    whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(modelPath, path);

    if (!ctx) {
        LOGE("Failed to initialise Whisper context from: %s", path);
        return 0L;
    }

    LOGI("Whisper model loaded — system info: %s", whisper_print_system_info());
    return reinterpret_cast<jlong>(ctx);
}

/**
 * Transcribe a float[] PCM buffer (16 kHz, mono, normalised to [-1, 1]).
 * Returns the recognised text, or an empty string on failure.
 *
 * @param language  BCP-47 language code, e.g. "zh", "auto"
 */
JNIEXPORT jstring JNICALL
JNIFUNC(nativeTranscribe)(JNIEnv *env, jobject /* obj */,
                          jlong ctxPtr, jfloatArray samples,
                          jint sampleCount, jstring language) {
    auto *ctx = reinterpret_cast<whisper_context *>(ctxPtr);
    if (!ctx) {
        LOGE("nativeTranscribe called with null context");
        return env->NewStringUTF("");
    }

    jfloat *data = env->GetFloatArrayElements(samples, nullptr);
    const char *lang = env->GetStringUTFChars(language, nullptr);

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime   = false;
    params.print_progress   = false;
    params.print_timestamps = false;
    params.language         = lang;        // "zh" for Mandarin/Taiwanese
    params.translate        = false;
    params.no_context       = true;
    params.single_segment   = false;
    params.max_tokens       = 0;
    params.temperature      = 0.0f;
    params.temperature_inc  = 0.0f;       // disable temperature fallback for speed

    auto t0 = std::chrono::steady_clock::now();
    int ret = whisper_full(ctx, params, data, static_cast<int>(sampleCount));
    auto t1 = std::chrono::steady_clock::now();

    env->ReleaseFloatArrayElements(samples, data, JNI_ABORT);
    env->ReleaseStringUTFChars(language, lang);

    if (ret != 0) {
        LOGE("whisper_full returned error: %d", ret);
        return env->NewStringUTF("");
    }

    long ms = std::chrono::duration_cast<std::chrono::milliseconds>(t1 - t0).count();
    int n   = whisper_full_n_segments(ctx);
    LOGI("Transcription done in %ld ms, %d segment(s)", ms, n);

    std::string result;
    result.reserve(256);
    for (int i = 0; i < n; ++i) {
        const char *seg = whisper_full_get_segment_text(ctx, i);
        if (seg) result += seg;
    }

    // Strip leading/trailing whitespace that Whisper sometimes produces
    size_t start = result.find_first_not_of(" \t\n\r");
    if (start == std::string::npos) return env->NewStringUTF("");
    size_t end = result.find_last_not_of(" \t\n\r");
    result = result.substr(start, end - start + 1);

    LOGI("Transcript: %s", result.c_str());
    return env->NewStringUTF(result.c_str());
}

/**
 * Return timing info from the last whisper_full() call as a CSV string:
 *   "load_ms,sample_ms,encode_ms,decode_ms,total_ms"
 */
JNIEXPORT jstring JNICALL
JNIFUNC(nativeGetTimings)(JNIEnv *env, jobject /* obj */, jlong ctxPtr) {
    auto *ctx = reinterpret_cast<whisper_context *>(ctxPtr);
    if (!ctx) return env->NewStringUTF("0,0,0,0,0");

    const whisper_timings *t = whisper_get_timings(ctx);
    if (!t) return env->NewStringUTF("0,0,0,0,0");

    char buf[128];
    snprintf(buf, sizeof(buf), "%.0f,%.0f,%.0f,%.0f,%.0f",
             t->load_ms, t->sample_ms, t->encode_ms, t->decode_ms,
             t->load_ms + t->sample_ms + t->encode_ms + t->decode_ms);
    return env->NewStringUTF(buf);
}

/**
 * Release the whisper context to free native memory.
 */
JNIEXPORT void JNICALL
JNIFUNC(nativeFree)(JNIEnv * /* env */, jobject /* obj */, jlong ctxPtr) {
    auto *ctx = reinterpret_cast<whisper_context *>(ctxPtr);
    if (ctx) {
        whisper_free(ctx);
        LOGI("Whisper context freed");
    }
}

} // extern "C"
