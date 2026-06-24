package com.ping.elderlyassistant.engine

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
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
 *   1. Download sherpa-onnx-<version>.aar from https://github.com/k2-fsa/sherpa-onnx/releases
 *      (NOT the -rknn or -static-link variants) and drop into app/libs/.
 *      Update app/build.gradle: implementation(name: 'sherpa-onnx-<version>', ext: 'aar')
 *
 *   2. Push model files to device:
 *        adb push sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/ \
 *          /sdcard/Android/data/com.ping.elderlyassistant.debug/files/models/sense_voice/
 *      Rename model.onnx → model.int8.onnx  (or use the int8 variant directly)
 *      Download: https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models
 */
class SenseVoiceEngine(private val context: Context) {

    companion object {
        private const val TAG = "SenseVoiceEngine"
    }

    @Volatile private var recognizer: OfflineRecognizer? = null
    private val loadMutex = Mutex()

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    data class LoadResult(val success: Boolean, val error: String? = null)

    /**
     * Loads the SenseVoice recognizer, trying each provider in
     * [ModelConfig.ASR_PROVIDER_PRIORITY] (nnapi → cpu) until one succeeds.
     */
    suspend fun load(): LoadResult = loadMutex.withLock {
        if (isLoaded()) return@withLock LoadResult(success = true)
        withContext(Dispatchers.IO) {
            val modelPath  = ModelConfig.senseVoiceModelPath(context)
            val tokensPath = ModelConfig.senseVoiceTokensPath(context)

            if (!File(modelPath).exists()) {
                return@withContext LoadResult(
                    success = false,
                    error   = "SenseVoice model not found: $modelPath"
                )
            }
            if (!File(tokensPath).exists()) {
                return@withContext LoadResult(
                    success = false,
                    error   = "Tokens file not found: $tokensPath"
                )
            }

            release()

            var lastError: Exception? = null
            for (provider in ModelConfig.ASR_PROVIDER_PRIORITY) {
                try {
                    val svConfig = OfflineSenseVoiceModelConfig(
                        model    = modelPath,
                        language = ModelConfig.ASR_LANGUAGE,
                        useItn   = ModelConfig.ASR_USE_ITN,
                    )
                    val modelConfig = OfflineModelConfig(
                        senseVoice = svConfig,
                        tokens     = tokensPath,
                        numThreads = ModelConfig.SENSE_VOICE_THREADS,
                        provider   = provider,
                    )
                    val recConfig = OfflineRecognizerConfig(
                        featConfig  = FeatureConfig(
                            sampleRate = ModelConfig.ASR_SAMPLE_RATE,
                            featureDim = 80,
                        ),
                        modelConfig = modelConfig,
                    )
                    recognizer = OfflineRecognizer(config = recConfig)
                    Log.i(TAG, "SenseVoice loaded (provider='$provider'  model=$modelPath)")
                    return@withContext LoadResult(success = true)
                } catch (ex: Exception) {
                    Log.w(TAG, "ASR provider '$provider' failed: ${ex.message}")
                    lastError = ex
                }
            }
            LoadResult(success = false, error = lastError?.message ?: "All ASR providers failed")
        }
    }

    fun isLoaded(): Boolean = recognizer != null

    fun release() {
        recognizer?.release()
        recognizer = null
    }

    // ── Transcription ─────────────────────────────────────────────────────────

    /**
     * Transcribe float32 PCM samples (16 kHz, mono, normalised to [-1, 1]).
     * Returns the recognised text.
     */
    suspend fun transcribe(
        samples: FloatArray,
        language: String = ModelConfig.ASR_LANGUAGE,  // language is set at model load time; param kept for API compatibility
    ): String = withContext(Dispatchers.IO) {
        val r = recognizer ?: run {
            Log.e(TAG, "transcribe() called before load()")
            return@withContext ""
        }
        if (samples.isEmpty()) return@withContext ""

        val t0 = System.currentTimeMillis()
        return@withContext try {
            val stream = r.createStream()
            try {
                stream.acceptSamples(samples)
                r.decode(stream)
                val result = r.getResult(stream)
                val text   = result.text
                val ms = System.currentTimeMillis() - t0
                Log.i(TAG, "SenseVoice transcribed ${samples.size / 16000f}s in ${ms}ms: \"$text\"")
                text
            } finally {
                runCatching { stream.release() }
            }
        } catch (ex: Exception) {
            Log.e(TAG, "transcribe() error: ${ex.message}", ex)
            ""
        }
    }
}
