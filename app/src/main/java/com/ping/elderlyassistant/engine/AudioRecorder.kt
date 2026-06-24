package com.ping.elderlyassistant.engine

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.sqrt

/**
 * Microphone capture utility.
 *
 * Records 16 kHz mono PCM-16 audio and converts it to a float32 array
 * normalised to [-1, 1], which is exactly what Whisper expects.
 *
 * VAD (Voice Activity Detection) stops recording automatically when the
 * signal RMS falls below [ModelConfig.VAD_SILENCE_THRESHOLD] for
 * [ModelConfig.VAD_SILENCE_SECONDS] consecutive seconds.
 *
 * Requires: android.permission.RECORD_AUDIO
 */
class AudioRecorder {

    companion object {
        private const val TAG = "AudioRecorder"
        private const val SAMPLE_RATE  = ModelConfig.ASR_SAMPLE_RATE
        private const val CHANNEL_CFG  = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        // Chunk size: 64 ms → 1024 samples at 16 kHz
        private const val CHUNK_FRAMES = 1024
    }

    enum class StopReason { SILENCE, TIMEOUT, MANUAL, ERROR }

    data class Recording(
        val samples: FloatArray,
        val durationSeconds: Float,
        val stopReason: StopReason
    )

    @Volatile private var shouldStop = false

    /**
     * Start recording and return when silence is detected or limits are reached.
     * Must be called from a coroutine (suspends on Dispatchers.IO).
     */
    @SuppressLint("MissingPermission")
    suspend fun recordUntilSilence(
        maxSeconds: Float        = ModelConfig.MAX_RECORD_SECONDS,
        silenceSeconds: Float    = ModelConfig.VAD_SILENCE_SECONDS,
        minSeconds: Float        = ModelConfig.MIN_RECORD_SECONDS,
        silenceThreshold: Float  = ModelConfig.VAD_SILENCE_THRESHOLD
    ): Recording = withContext(Dispatchers.IO) {

        shouldStop = false

        val bufSize = maxOf(
            AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CFG, AUDIO_FORMAT),
            CHUNK_FRAMES * 2   // bytes (2 bytes per 16-bit sample)
        )

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE, CHANNEL_CFG, AUDIO_FORMAT, bufSize
        )

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord init failed")
            recorder.release()   // must release even on failed init to free kernel buffer
            return@withContext Recording(FloatArray(0), 0f, StopReason.ERROR)
        }

        val maxFrames     = (maxSeconds * SAMPLE_RATE).toInt()
        val minFrames     = (minSeconds * SAMPLE_RATE).toInt()
        val silenceFrames = (silenceSeconds * SAMPLE_RATE).toInt()

        val allSamples  = ArrayList<Short>(maxFrames)
        val chunkBuffer = ShortArray(CHUNK_FRAMES)
        var silenceCount = 0
        var stopReason   = StopReason.TIMEOUT

        try {
            recorder.startRecording()
            Log.i(TAG, "Recording started (max ${maxSeconds}s)")

            while (coroutineContext.isActive && !shouldStop) {
                val read = recorder.read(chunkBuffer, 0, CHUNK_FRAMES)
                if (read <= 0) continue

                for (i in 0 until read) allSamples.add(chunkBuffer[i])

                val rms = computeRms(chunkBuffer, read)
                val isSilent = rms < silenceThreshold

                if (isSilent) silenceCount += read else silenceCount = 0

                val totalFrames = allSamples.size
                if (totalFrames >= maxFrames) {
                    stopReason = StopReason.TIMEOUT; break
                }
                if (totalFrames >= minFrames && silenceCount >= silenceFrames) {
                    stopReason = StopReason.SILENCE; break
                }
            }

            if (shouldStop) stopReason = StopReason.MANUAL

        } finally {
            recorder.stop()
            recorder.release()
        }

        val floatSamples = convertToFloat(allSamples)
        val durationSec  = allSamples.size.toFloat() / SAMPLE_RATE
        Log.i(TAG, "Recording stopped (${durationSec.format()}s, $stopReason, ${allSamples.size} samples)")
        Recording(floatSamples, durationSec, stopReason)
    }

    /** Signal to stop recording early (e.g., user releases button). */
    fun stopEarly() { shouldStop = true }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun computeRms(buf: ShortArray, len: Int): Float {
        var sum = 0.0
        for (i in 0 until len) sum += (buf[i] / 32768.0) * (buf[i] / 32768.0)
        return sqrt(sum / len).toFloat()
    }

    private fun convertToFloat(shorts: ArrayList<Short>): FloatArray {
        val out = FloatArray(shorts.size)
        for (i in shorts.indices) out[i] = shorts[i] / 32768.0f
        return out
    }

    private fun Float.format() = "%.2f".format(this)
}
