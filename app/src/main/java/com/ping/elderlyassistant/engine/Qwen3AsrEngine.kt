package com.ping.elderlyassistant.engine

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineQwen3AsrModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * [AsrEngine] backed by Qwen3-ASR-0.6B-int8 via sherpa-onnx.
 *
 * Strengths: autoregressive transformer, stronger accuracy on noisy/accented
 * speech and Taiwanese code-switching; larger model (~600 MB).
 *
 * Model placement:
 *   .../files/models/qwen3_asr/
 *       conv_frontend.onnx
 *       encoder.int8.onnx
 *       decoder.int8.onnx
 *       tokenizer/         (directory with vocab files)
 * Download: sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25 release asset
 */
class Qwen3AsrEngine(private val context: Context) : AsrEngine {

    companion object {
        private const val TAG = "Qwen3AsrEngine"
    }

    @Volatile private var recognizer: OfflineRecognizer? = null
    private val loadMutex = Mutex()

    override fun isLoaded(): Boolean = recognizer != null

    override suspend fun load(): AsrEngine.LoadResult = loadMutex.withLock {
        if (isLoaded()) return@withLock AsrEngine.LoadResult(success = true)
        withContext(Dispatchers.IO) {
            val dir           = ModelConfig.qwen3AsrDir(context)
            val convFrontend  = ModelConfig.qwen3AsrConvFrontendPath(context)
            val encoder       = ModelConfig.qwen3AsrEncoderPath(context)
            val decoder       = ModelConfig.qwen3AsrDecoderPath(context)
            val tokenizer     = ModelConfig.qwen3AsrTokenizerDir(context)

            for (path in listOf(convFrontend, encoder, decoder)) {
                if (!File(path).exists()) return@withContext AsrEngine.LoadResult(
                    success = false,
                    error   = "Qwen3-ASR file not found: $path"
                )
            }
            if (!File(tokenizer).isDirectory) return@withContext AsrEngine.LoadResult(
                success = false,
                error   = "Qwen3-ASR tokenizer dir not found: $tokenizer"
            )

            release()

            var lastError: Exception? = null
            for (provider in ModelConfig.ASR_PROVIDER_PRIORITY) {
                try {
                    val qwen3Config = OfflineQwen3AsrModelConfig(
                        convFrontend, encoder, decoder, tokenizer
                    )
                    val modelConfig = OfflineModelConfig(
                        qwen3Asr   = qwen3Config,
                        numThreads = ModelConfig.QWEN3_ASR_THREADS,
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
                    Log.i(TAG, "Qwen3-ASR loaded (provider='$provider'  dir=$dir)")
                    return@withContext AsrEngine.LoadResult(success = true)
                } catch (ex: Exception) {
                    Log.w(TAG, "ASR provider '$provider' failed: ${ex.message}")
                    lastError = ex
                }
            }
            AsrEngine.LoadResult(success = false, error = lastError?.message ?: "All ASR providers failed")
        }
    }

    override fun release() {
        recognizer?.release()
        recognizer = null
    }

    override suspend fun transcribe(samples: FloatArray): String = withContext(Dispatchers.IO) {
        val r = recognizer ?: run {
            Log.e(TAG, "transcribe() called before load()")
            return@withContext ""
        }
        if (samples.isEmpty()) return@withContext ""

        val t0 = System.currentTimeMillis()
        return@withContext try {
            val stream = r.createStream()
            try {
                stream.acceptWaveform(samples, ModelConfig.ASR_SAMPLE_RATE)
                r.decode(stream)
                val text = r.getResult(stream).text
                Log.i(TAG, "Qwen3-ASR transcribed ${samples.size / 16000f}s in ${System.currentTimeMillis() - t0}ms: \"$text\"")
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
