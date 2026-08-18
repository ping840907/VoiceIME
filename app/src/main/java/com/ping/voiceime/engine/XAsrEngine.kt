package com.ping.voiceime.engine

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

    /** Provider that successfully initialized the recognizer, e.g. "cpu". */
    var activeProvider: String = "unknown"
        private set

    fun isLoaded() = recognizer != null

    suspend fun load(): LoadResult = loadMutex.withLock {
        if (isLoaded()) return@withLock LoadResult(true, provider = activeProvider)
        withContext(Dispatchers.IO) {
            val encoder = ModelConfig.xAsrEncoderPath(context)
            val decoder = ModelConfig.xAsrDecoderPath(context)
            val joiner  = ModelConfig.xAsrJoinerPath(context)
            val tokens  = ModelConfig.xAsrTokensPath(context)

            for (path in listOf(encoder, decoder, joiner, tokens)) {
                if (!File(path).exists())
                    return@withContext LoadResult(false, error = "File not found: $path")
            }

            // A truncated/mismatched download can leave onnxruntime able to "load" a model
            // that never produces real tokens, without ever throwing — dump file sizes and
            // a tokens.txt line count so a bad download is visible without needing another
            // recording test.
            for (path in listOf(encoder, decoder, joiner, tokens)) {
                Log.i(TAG, "model file: $path  size=${File(path).length()} bytes")
            }
            runCatching {
                val lineCount = File(tokens).readLines().size
                Log.i(TAG, "tokens.txt line count (vocab size) = $lineCount")
            }

            release()

            // Try NNAPI first, falling back to CPU. Both providers reproduced the same
            // empty-output symptom in testing, so NNAPI is not the root cause after all —
            // restored per user request.
            var lastError: Exception? = null
            for (provider in ModelConfig.ASR_PROVIDER_PRIORITY) {
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
                                provider   = provider,
                            ),
                            endpointConfig = EndpointConfig(
                                rule1 = EndpointRule(false, 2.4f, 0f),
                                rule2 = EndpointRule(true,  1.2f, 10f),
                                rule3 = EndpointRule(false, 0f,   20f),
                            ),
                            enableEndpoint = true,
                            decodingMethod = "greedy_search",
                            maxActivePaths = 4,
                        )
                    )
                    activeProvider = provider
                    Log.i(TAG, "Loaded — provider=$provider  threads=${ModelConfig.X_ASR_THREADS}")
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
        activeProvider = "unknown"
    }

    fun createStream(): OnlineStream? = recognizer?.createStream()

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
        val provider: String = "unknown",
    )

    companion object {
        private const val TAG = "XAsrEngine"
    }
}
