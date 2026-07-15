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
        private const val TAG        = "Qwen3AsrEngine"
        private const val PREF_NAME  = "asr_engine"
        private const val KEY_PROVIDER = "active_provider"

        /** Read the last-used provider from SharedPreferences (for settings display). */
        fun savedProvider(context: Context): String =
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getString(KEY_PROVIDER, "未載入") ?: "未載入"
    }

    @Volatile private var recognizer: OfflineRecognizer? = null
    private val loadMutex = Mutex()
    @Volatile var loadedHotwords: String = ""
        private set

    /** Provider that successfully initialized the recognizer, e.g. "nnapi" or "cpu". */
    var activeProvider: String = "unknown"
        private set

    fun isLoaded() = recognizer != null

    /**
     * Load (or reload) the recognizer.
     * [hotwords] is a newline-separated list of words/phrases to boost during decoding.
     * If the engine is already loaded with the same hotwords, this is a no-op.
     */
    suspend fun load(hotwords: String = ""): LoadResult = loadMutex.withLock {
        if (isLoaded() && loadedHotwords == hotwords) return@withLock LoadResult(true, provider = activeProvider)
        withContext(Dispatchers.IO) {
            val convFrontend = ModelConfig.qwen3AsrConvFrontendPath(context)
            val encoder      = ModelConfig.qwen3AsrEncoderPath(context)
            val decoder      = ModelConfig.qwen3AsrDecoderPath(context)
            val tokenizer    = ModelConfig.qwen3AsrTokenizerDir(context)

            for (path in listOf(convFrontend, encoder, decoder)) {
                if (!File(path).exists())
                    return@withContext LoadResult(false, error = "File not found: $path")
            }
            if (!File(tokenizer).isDirectory)
                return@withContext LoadResult(false, error = "Tokenizer dir not found: $tokenizer")

            release()

            var lastError: Exception? = null
            for (provider in ModelConfig.ASR_PROVIDER_PRIORITY) {
                try {
                    recognizer = OfflineRecognizer(
                        config = OfflineRecognizerConfig(
                            featConfig  = FeatureConfig(
                                sampleRate = ModelConfig.ASR_SAMPLE_RATE,
                                featureDim = 80,
                            ),
                            modelConfig = OfflineModelConfig(
                                qwen3Asr   = OfflineQwen3AsrModelConfig(
                                    convFrontend = convFrontend,
                                    encoder      = encoder,
                                    decoder      = decoder,
                                    tokenizer    = tokenizer,
                                    hotwords     = hotwords,
                                ),
                                numThreads = ModelConfig.QWEN3_ASR_THREADS,
                                provider   = provider,
                            ),
                        )
                    )
                    loadedHotwords = hotwords
                    activeProvider = provider
                    // Persist so ImeSettingsActivity can read it without binding the service
                    context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                        .edit().putString(KEY_PROVIDER, provider).apply()
                    Log.i(TAG, "Loaded — provider=$provider  threads=${ModelConfig.QWEN3_ASR_THREADS}  hotwords=${hotwords.lines().size} words")
                    return@withContext LoadResult(true, provider = provider)
                } catch (ex: Exception) {
                    Log.w(TAG, "Provider '$provider' failed: ${ex.message}")
                    lastError = ex
                }
            }
            LoadResult(false, error = lastError?.message ?: "All providers failed")
        }
    }

    fun release() {
        recognizer?.release()
        recognizer = null
        loadedHotwords = ""
    }

    suspend fun transcribe(samples: FloatArray): String = withContext(Dispatchers.IO) {
        val r = recognizer ?: return@withContext ""
        if (samples.isEmpty()) return@withContext ""
        val t0 = System.currentTimeMillis()
        try {
            val stream = r.createStream()
            try {
                stream.acceptWaveform(samples, ModelConfig.ASR_SAMPLE_RATE)
                r.decode(stream)
                val text = r.getResult(stream).text
                Log.i(TAG, "Transcribed ${samples.size / 16000f}s → ${System.currentTimeMillis()-t0}ms: \"$text\"")
                text
            } finally {
                runCatching { stream.release() }
            }
        } catch (ex: Exception) {
            Log.e(TAG, "transcribe error: ${ex.message}", ex)
            ""
        }
    }

    data class LoadResult(
        val success: Boolean,
        val error: String?   = null,
        val provider: String = "unknown",
    )
}
