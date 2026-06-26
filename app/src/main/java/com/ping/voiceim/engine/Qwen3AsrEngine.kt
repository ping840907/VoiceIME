package com.ping.voiceim.engine

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

class Qwen3AsrEngine(private val context: Context) {

    companion object {
        private const val TAG = "Qwen3AsrEngine"
    }

    @Volatile private var recognizer: OfflineRecognizer? = null
    private val loadMutex = Mutex()

    fun isLoaded() = recognizer != null

    suspend fun load(): LoadResult = loadMutex.withLock {
        if (isLoaded()) return@withLock LoadResult(true)
        withContext(Dispatchers.IO) {
            val convFrontend = ModelConfig.qwen3AsrConvFrontendPath(context)
            val encoder      = ModelConfig.qwen3AsrEncoderPath(context)
            val decoder      = ModelConfig.qwen3AsrDecoderPath(context)
            val tokenizer    = ModelConfig.qwen3AsrTokenizerDir(context)

            for (path in listOf(convFrontend, encoder, decoder)) {
                if (!File(path).exists()) return@withContext LoadResult(false, "File not found: $path")
            }
            if (!File(tokenizer).isDirectory)
                return@withContext LoadResult(false, "Tokenizer dir not found: $tokenizer")

            release()

            var lastError: Exception? = null
            for (provider in ModelConfig.ASR_PROVIDER_PRIORITY) {
                try {
                    recognizer = OfflineRecognizer(
                        config = OfflineRecognizerConfig(
                            featConfig = FeatureConfig(
                                sampleRate = ModelConfig.ASR_SAMPLE_RATE,
                                featureDim = 80,
                            ),
                            modelConfig = OfflineModelConfig(
                                qwen3Asr   = OfflineQwen3AsrModelConfig(convFrontend, encoder, decoder, tokenizer),
                                numThreads = ModelConfig.QWEN3_ASR_THREADS,
                                provider   = provider,
                            ),
                        )
                    )
                    Log.i(TAG, "Loaded (provider=$provider)")
                    return@withContext LoadResult(true)
                } catch (ex: Exception) {
                    Log.w(TAG, "Provider '$provider' failed: ${ex.message}")
                    lastError = ex
                }
            }
            LoadResult(false, lastError?.message ?: "All providers failed")
        }
    }

    fun release() {
        recognizer?.release()
        recognizer = null
    }

    suspend fun transcribe(samples: FloatArray): String = withContext(Dispatchers.IO) {
        val r = recognizer ?: return@withContext ""
        if (samples.isEmpty()) return@withContext ""
        try {
            val stream = r.createStream()
            try {
                stream.acceptWaveform(samples, ModelConfig.ASR_SAMPLE_RATE)
                r.decode(stream)
                r.getResult(stream).text
            } finally {
                runCatching { stream.release() }
            }
        } catch (ex: Exception) {
            Log.e(TAG, "transcribe error: ${ex.message}", ex)
            ""
        }
    }

    data class LoadResult(val success: Boolean, val error: String? = null)
}
