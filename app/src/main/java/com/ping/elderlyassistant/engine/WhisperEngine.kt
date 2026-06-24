package com.ping.elderlyassistant.engine

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Offline ASR using whisper.cpp via JNI.
 *
 * Native library: whisper_android (.so compiled by app/src/main/cpp/CMakeLists.txt)
 * Model file   : ggml-tiny.int4.bin  (place at ModelConfig.whisperModelPath(ctx))
 *
 * Build the model file:
 *   ./models/download-ggml-model.sh tiny
 *   python3 models/convert-whisper-to-coreml.py --model tiny --int4   # optional quant
 *   # or download from HuggingFace:
 *   # https://huggingface.co/ggerganov/whisper.cpp/tree/main
 */
class WhisperEngine(private val context: Context) {

    companion object {
        private const val TAG = "WhisperEngine"

        val isNativeAvailable: Boolean by lazy {
            try {
                System.loadLibrary("whisper_android")
                Log.i(TAG, "whisper_android native library loaded")
                true
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "whisper_android .so not found — ASR unavailable: ${e.message}")
                false
            }
        }

        // ── JNI declarations ─────────────────────────────────────────────────
        @JvmStatic private external fun nativeInit(modelPath: String): Long
        @JvmStatic private external fun nativeTranscribe(
            ctxPtr: Long, samples: FloatArray, sampleCount: Int, language: String
        ): String
        @JvmStatic private external fun nativeGetTimings(ctxPtr: Long): String
        @JvmStatic private external fun nativeFree(ctxPtr: Long)
    }

    private var ctxPtr: Long = 0L

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    data class LoadResult(val success: Boolean, val error: String? = null)

    suspend fun load(): LoadResult = withContext(Dispatchers.IO) {
        if (!isNativeAvailable) {
            return@withContext LoadResult(
                success = false,
                error = "whisper_android.so missing. Build the NDK target first."
            )
        }

        val modelPath = ModelConfig.whisperModelPath(context)
        if (!File(modelPath).exists()) {
            return@withContext LoadResult(
                success = false,
                error = "Whisper model not found at: $modelPath"
            )
        }

        if (ctxPtr != 0L) free()   // reload

        Log.i(TAG, "Loading Whisper model: $modelPath")
        ctxPtr = nativeInit(modelPath)
        return@withContext if (ctxPtr != 0L) {
            Log.i(TAG, "Whisper ready")
            LoadResult(success = true)
        } else {
            LoadResult(success = false, error = "whisper_init_from_file returned null")
        }
    }

    fun isLoaded(): Boolean = ctxPtr != 0L

    fun free() {
        if (ctxPtr != 0L) {
            nativeFree(ctxPtr)
            ctxPtr = 0L
        }
    }

    // ── Transcription ─────────────────────────────────────────────────────────

    /**
     * Transcribe float32 PCM samples (16 kHz, mono) to text.
     *
     * @param samples  Output of [AudioRecorder.recordUntilSilence]
     * @param language BCP-47 code, e.g. "zh" or "auto"
     * @return Recognised text, or empty string on failure.
     */
    suspend fun transcribe(
        samples: FloatArray,
        language: String = ModelConfig.WHISPER_LANGUAGE
    ): String = withContext(Dispatchers.IO) {
        if (ctxPtr == 0L) {
            Log.e(TAG, "transcribe() called before model is loaded")
            return@withContext ""
        }
        if (samples.isEmpty()) return@withContext ""

        Log.d(TAG, "Transcribing ${samples.size} samples (${samples.size / 16000f}s)…")
        val result = nativeTranscribe(ctxPtr, samples, samples.size, language)
        val timings = nativeGetTimings(ctxPtr)
        Log.i(TAG, "Transcription timings (load,sample,encode,decode,total): $timings")
        result
    }

    /**
     * Convenience: record from mic then transcribe in one call.
     * Returns the recognised text and the recording metadata.
     */
    suspend fun listenAndTranscribe(
        recorder: AudioRecorder,
        language: String = ModelConfig.WHISPER_LANGUAGE
    ): Pair<String, AudioRecorder.Recording> {
        val recording = recorder.recordUntilSilence()
        val text = if (recording.samples.isNotEmpty()) {
            transcribe(recording.samples, language)
        } else ""
        return text to recording
    }
}
