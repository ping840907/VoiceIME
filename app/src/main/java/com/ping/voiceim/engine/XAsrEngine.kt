package com.ping.voiceim.engine

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

class XAsrEngine(private val context: Context) {

    @Volatile private var recognizer: OnlineRecognizer? = null
    private val loadMutex = Mutex()
    // True only when the recognizer was built with modified_beam_search (hotwords capable)
    @Volatile var hotwordsEnabled: Boolean = false
        private set

    fun isLoaded() = recognizer != null

    suspend fun load(): LoadResult = loadMutex.withLock {
        if (isLoaded()) return@withLock LoadResult(true)
        withContext(Dispatchers.IO) {
            val encoder = ModelConfig.xAsrEncoderPath(context)
            val decoder = ModelConfig.xAsrDecoderPath(context)
            val joiner  = ModelConfig.xAsrJoinerPath(context)
            val tokens  = ModelConfig.xAsrTokensPath(context)

            for (path in listOf(encoder, decoder, joiner, tokens)) {
                if (!File(path).exists())
                    return@withContext LoadResult(false, error = "File not found: $path")
            }

            release()

            // Try modified_beam_search first (required for per-stream hotwords);
            // fall back to greedy_search if the model/library doesn't support it.
            val methods = listOf(
                "modified_beam_search" to true,
                "greedy_search"        to false,
            )
            for ((method, supportsHotwords) in methods) {
                try {
                    recognizer = OnlineRecognizer(
                        config = OnlineRecognizerConfig(
                            featConfig = FeatureConfig(
                                sampleRate = ModelConfig.ASR_SAMPLE_RATE,
                                featureDim = 80,
                            ),
                            modelConfig = OnlineModelConfig(
                                transducer = OnlineTransducerModelConfig(
                                    encoder = encoder,
                                    decoder = decoder,
                                    joiner  = joiner,
                                ),
                                tokens     = tokens,
                                numThreads = ModelConfig.X_ASR_THREADS,
                                provider   = "cpu",
                            ),
                            endpointConfig = EndpointConfig(
                                rule1 = EndpointRule(false, 2.4f, 0f),
                                rule2 = EndpointRule(true,  1.2f, 10f),
                                rule3 = EndpointRule(false, 0f,   20f),
                            ),
                            enableEndpoint = true,
                            decodingMethod = method,
                            maxActivePaths = 4,
                            hotwordsScore  = if (supportsHotwords) ModelConfig.X_ASR_HOTWORDS_SCORE else 0f,
                        )
                    )
                    hotwordsEnabled = supportsHotwords
                    Log.i(TAG, "Loaded — threads=${ModelConfig.X_ASR_THREADS}  decoding=$method")
                    return@withContext LoadResult(true)
                } catch (ex: Exception) {
                    Log.w(TAG, "decoding=$method failed: ${ex.message}")
                    release()
                }
            }
            LoadResult(false, error = "All decoding methods failed")
        }
    }

    fun release() {
        recognizer?.release()
        recognizer = null
        hotwordsEnabled = false
    }

    /**
     * Create a new streaming session. When [hotwordsEnabled] is true (modified_beam_search),
     * [hotwords] is passed through for contextual biasing; otherwise it is ignored.
     * Caller owns the returned stream (must call release()).
     */
    fun createStream(hotwords: String = ""): OnlineStream? =
        recognizer?.createStream(if (hotwordsEnabled) hotwords else "")

    companion object {
        private const val TAG = "XAsrEngine"

        /**
         * Convert a list of plain words into the space-tokenised format required
         * by the transducer hotwords API (one character/letter per token, words
         * separated by newlines).
         *
         * Examples:
         *   "台灣"   → "台 灣"
         *   "iPhone" → "i P h o n e"   (letters space-separated)
         */
        fun formatHotwords(words: Iterable<String>): String =
            words.filter { it.isNotBlank() }
                .joinToString("\n") { word -> word.trim().map { it }.joinToString(" ") }
    }

    /** Feed a chunk of PCM samples into an active stream. */
    fun acceptWaveform(stream: OnlineStream, samples: FloatArray) {
        stream.acceptWaveform(samples, ModelConfig.ASR_SAMPLE_RATE)
    }

    /** Signal end of audio input so the encoder can flush its state. */
    fun inputFinished(stream: OnlineStream) {
        stream.inputFinished()
    }

    /** Decode all pending frames in the stream. */
    fun decode(stream: OnlineStream) {
        recognizer?.decode(stream)
    }

    /** Return current partial/final text from the stream. */
    fun getResult(stream: OnlineStream): String =
        recognizer?.getResult(stream)?.text?.trim() ?: ""

    /** True if the endpoint detector has fired (natural pause detected). */
    fun isEndpoint(stream: OnlineStream): Boolean =
        recognizer?.isEndpoint(stream) ?: false

    /** Reset endpoint state so the stream can continue after an endpoint. */
    fun reset(stream: OnlineStream) {
        recognizer?.reset(stream)
    }

    /** True if the stream has enough frames buffered to produce output. */
    fun isReady(stream: OnlineStream): Boolean =
        recognizer?.isReady(stream) ?: false

    data class LoadResult(
        val success: Boolean,
        val error: String? = null,
    )
}
