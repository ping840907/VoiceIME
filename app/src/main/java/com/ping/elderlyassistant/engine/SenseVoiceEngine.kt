package com.ping.elderlyassistant.engine

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Offline ASR using SenseVoice-Small via the sherpa-onnx Android SDK.
 *
 * Why SenseVoice over Whisper.cpp (June 2026 research):
 *   • ~15× faster than Whisper-Large at similar accuracy
 *   • ~3× lower Chinese Character Error Rate than whisper.cpp tiny on Mandarin
 *   • Non-autoregressive: latency ∝ audio length, not model depth
 *   • Emotion + audio-event detection included (available via result.emotion)
 *   • Single prebuilt AAR — no custom NDK/CMake build required
 *
 * Setup:
 *   1. Add sherpa-onnx AAR to app/build.gradle:
 *        implementation 'com.k2fsa.sherpa.onnx:sherpa-onnx-android-arm64-v8a:1.10.34'
 *      (check latest version: https://github.com/k2-fsa/sherpa-onnx/releases)
 *
 *   2. Push model files to device:
 *        adb push sense_voice/ \
 *          /sdcard/Android/data/com.ping.elderlyassistant.debug/files/models/sense_voice/
 *      Download from:
 *        https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models
 *        → sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17.tar.bz2
 *        Rename model.onnx → model.int8.onnx  (or use the int8 variant directly)
 */
class SenseVoiceEngine(private val context: Context) {

    companion object {
        private const val TAG = "SenseVoiceEngine"

        val isSherpaAvailable: Boolean by lazy {
            try {
                Class.forName("com.k2fsa.sherpa.onnx.OfflineRecognizer")
                Log.i(TAG, "sherpa-onnx found on classpath")
                true
            } catch (_: ClassNotFoundException) {
                Log.w(TAG, "sherpa-onnx not found — ASR will be unavailable. " +
                        "Add 'com.k2fsa.sherpa.onnx:sherpa-onnx-android-arm64-v8a' to build.gradle")
                false
            }
        }
    }

    // Held as Any to avoid hard compile dependency when AAR is absent
    @Volatile private var recognizer: Any? = null   // com.k2fsa.sherpa.onnx.OfflineRecognizer
    @Volatile private var activeProvider = "cpu"    // set after successful load, for logging
    private val loadMutex = Mutex()

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    data class LoadResult(val success: Boolean, val error: String? = null)

    suspend fun load(): LoadResult = loadMutex.withLock {
        if (isLoaded()) return@withLock LoadResult(success = true)
        withContext(Dispatchers.IO) {
            if (!isSherpaAvailable) {
                return@withContext LoadResult(
                    success = false,
                    error = "sherpa-onnx AAR missing. See SenseVoiceEngine.kt setup."
                )
            }

            val modelPath  = ModelConfig.senseVoiceModelPath(context)
            val tokensPath = ModelConfig.senseVoiceTokensPath(context)

            if (!File(modelPath).exists()) {
                return@withContext LoadResult(
                    success = false,
                    error = "SenseVoice model not found: $modelPath"
                )
            }
            if (!File(tokensPath).exists()) {
                return@withContext LoadResult(
                    success = false,
                    error = "Tokens file not found: $tokensPath"
                )
            }

            release()   // clean up any previous instance

            try {
                val (rec, provider) = buildRecognizerWithFallback(modelPath, tokensPath)
                recognizer     = rec
                activeProvider = provider
                Log.i(TAG, "SenseVoice loaded (provider='$provider'  model=$modelPath)")
                LoadResult(success = true)
            } catch (ex: Exception) {
                Log.e(TAG, "Failed to load SenseVoice on all providers: ${ex.message}", ex)
                LoadResult(success = false, error = ex.message)
            }
        }
    }

    fun isLoaded(): Boolean = recognizer != null

    fun release() {
        recognizer?.let { r ->
            runCatching { r.javaClass.getMethod("release").invoke(r) }
        }
        recognizer = null
    }

    // ── Transcription ─────────────────────────────────────────────────────────

    /**
     * Transcribe float32 PCM samples (16 kHz, mono, normalised to [-1, 1]).
     * Returns the recognised text. Emotion tag is logged but not returned.
     */
    suspend fun transcribe(
        samples: FloatArray,
        language: String = ModelConfig.ASR_LANGUAGE
    ): String = withContext(Dispatchers.IO) {
        val r = recognizer
        if (r == null) {
            Log.e(TAG, "transcribe() called before load()")
            return@withContext ""
        }
        if (samples.isEmpty()) return@withContext ""

        val t0 = System.currentTimeMillis()

        return@withContext try {
            // Direct API (when sherpa-onnx AAR is on the classpath):
            //
            //   val stream = r.createStream()
            //   stream.acceptSamples(samples)
            //   r.decode(stream)
            //   val result = r.getResult(stream)
            //   stream.release()
            //   Log.i(TAG, "Emotion: ${result.emotion}  Lang: ${result.lang}")
            //   result.text
            //
            // Reflection-based fallback (AAR optional during dev):

            val streamObj  = r.javaClass.getMethod("createStream").invoke(r)!!
            val streamCls  = streamObj.javaClass

            // Use try-finally so the native stream is always released, even on exception.
            var text = ""
            try {
                streamCls.getMethod("acceptSamples", FloatArray::class.java)
                    .invoke(streamObj, samples)
                r.javaClass.getMethod("decode", streamCls).invoke(r, streamObj)
                val resultObj = r.javaClass.getMethod("getResult", streamCls)
                    .invoke(r, streamObj)!!
                text = resultObj.javaClass.getMethod("getText").invoke(resultObj)
                    as? String ?: ""
                runCatching {
                    val emotion = resultObj.javaClass.getMethod("getEmotion").invoke(resultObj)
                    val lang    = resultObj.javaClass.getMethod("getLang").invoke(resultObj)
                    Log.d(TAG, "emotion=$emotion  lang=$lang")
                }
            } finally {
                runCatching { streamCls.getMethod("release").invoke(streamObj) }
            }

            val ms = System.currentTimeMillis() - t0
            Log.i(TAG, "SenseVoice transcribed ${samples.size / 16000f}s audio in ${ms}ms: \"$text\"")
            text
        } catch (ex: Exception) {
            Log.e(TAG, "transcribe() error: ${ex.message}", ex)
            ""
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Tries each ONNX Runtime provider from [ModelConfig.ASR_PROVIDER_PRIORITY] in order.
     * Returns the first (recognizer, providerName) pair that constructs without error.
     * Throws if all providers fail.
     *
     * Provider priority:
     *   "nnapi" — Android NNAPI; delegates compatible ops to NPU/DSP/GPU.
     *             ONNX Runtime falls back per-op to CPU for unsupported ops, so
     *             construction should succeed on any Android 8.1+ device.
     *   "cpu"   — pure-software fallback; always works.
     */
    private fun buildRecognizerWithFallback(
        modelPath: String, tokensPath: String
    ): Pair<Any, String> {
        val pkg = "com.k2fsa.sherpa.onnx"

        val featCls  = Class.forName("$pkg.FeatureConfig")
        val feat     = featCls.getDeclaredConstructor(Int::class.java, Int::class.java)
            .newInstance(16000, 80)

        val svCls    = Class.forName("$pkg.OfflineSenseVoiceModelConfig")
        val sv       = svCls.getDeclaredConstructor(
            String::class.java, String::class.java, Boolean::class.java
        ).newInstance(modelPath, ModelConfig.ASR_LANGUAGE, ModelConfig.ASR_USE_ITN)

        val omCls    = Class.forName("$pkg.OfflineModelConfig")
        val orCfgCls = Class.forName("$pkg.OfflineRecognizerConfig")
        val recCls   = Class.forName("$pkg.OfflineRecognizer")

        var lastError: Exception? = null
        for (provider in ModelConfig.ASR_PROVIDER_PRIORITY) {
            try {
                val om    = buildOfflineModelConfig(omCls, pkg, sv, tokensPath, provider)
                val orCfg = orCfgCls.getDeclaredConstructor(featCls, omCls, String::class.java)
                    .newInstance(feat, om, "greedy_search")
                val rec   = recCls.getDeclaredConstructor(orCfgCls).newInstance(orCfg)
                Log.i(TAG, "ASR provider '$provider' OK")
                return rec to provider
            } catch (ex: Exception) {
                Log.w(TAG, "ASR provider '$provider' failed: ${ex.message}")
                lastError = ex
            }
        }
        throw lastError ?: RuntimeException("No compatible ASR provider found")
    }

    /**
     * Builds [OfflineModelConfig] for the given ONNX Runtime [provider].
     * Tries a compact 5-arg constructor first (newer sherpa-onnx builds),
     * then falls back to the full all-fields constructor (older builds).
     */
    private fun buildOfflineModelConfig(
        cls: Class<*>, pkg: String, senseVoice: Any, tokensPath: String, provider: String
    ): Any {
        val svCls = Class.forName("$pkg.OfflineSenseVoiceModelConfig")
        return runCatching {
            cls.getDeclaredConstructor(
                svCls, String::class.java, Int::class.java, String::class.java, Boolean::class.java
            ).newInstance(
                senseVoice, tokensPath,
                ModelConfig.SENSE_VOICE_THREADS, provider, false
            )
        }.getOrElse {
            val empty = ""
            cls.constructors.first().newInstance(
                /* transducer */ empty, empty, empty,
                /* paraformer */ empty,
                /* nemo ctc   */ empty,
                /* whisper    */ empty, empty, empty, empty,
                /* tdnn       */ empty,
                /* tokens     */ tokensPath,
                /* numThreads */ ModelConfig.SENSE_VOICE_THREADS,
                /* provider   */ provider,
                /* debug      */ false,
                /* modelType  */ empty,
                /* senseVoice */ senseVoice
            )
        }
    }
}
