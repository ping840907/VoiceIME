package com.ping.voiceime.engine

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

class AudioRecorder {

    companion object {
        private const val TAG          = "AudioRecorder"
        private const val SAMPLE_RATE  = ModelConfig.ASR_SAMPLE_RATE
        private const val CHANNEL_CFG  = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val CHUNK_FRAMES = 1024
    }

    enum class StopReason { SILENCE, TIMEOUT, MANUAL, ERROR }

    data class Recording(
        val samples: FloatArray,
        val durationSeconds: Float,
        val stopReason: StopReason,
    )

    @Volatile private var shouldStop = false

    @SuppressLint("MissingPermission")
    suspend fun recordUntilSilence(
        maxSeconds:       Float = ModelConfig.MAX_RECORD_SECONDS,
        silenceSeconds:   Float = ModelConfig.VAD_SILENCE_SECONDS,
        minSeconds:       Float = ModelConfig.MIN_RECORD_SECONDS,
        silenceThreshold: Float = ModelConfig.VAD_SILENCE_THRESHOLD,
        onRmsUpdate: ((Float) -> Unit)? = null,
    ): Recording = withContext(Dispatchers.IO) {

        shouldStop = false

        val bufSize = maxOf(
            AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CFG, AUDIO_FORMAT),
            CHUNK_FRAMES * 2
        )

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE, CHANNEL_CFG, AUDIO_FORMAT, bufSize
        )

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord init failed")
            recorder.release()
            return@withContext Recording(FloatArray(0), 0f, StopReason.ERROR)
        }

        val maxFrames     = (maxSeconds * SAMPLE_RATE).toInt()
        val minFrames     = (minSeconds * SAMPLE_RATE).toInt()
        val silenceFrames = (silenceSeconds * SAMPLE_RATE).toInt()

        val allSamples  = ShortArray(maxFrames)
        var sampleCount = 0
        val chunkBuffer = ShortArray(CHUNK_FRAMES)
        var silenceCount = 0
        var stopReason   = StopReason.TIMEOUT

        try {
            recorder.startRecording()
            while (coroutineContext.isActive && !shouldStop) {
                val read = recorder.read(chunkBuffer, 0, CHUNK_FRAMES)
                if (read <= 0) continue

                val copy = minOf(read, maxFrames - sampleCount)
                System.arraycopy(chunkBuffer, 0, allSamples, sampleCount, copy)
                sampleCount += copy

                val rms = computeRms(chunkBuffer, read)
                onRmsUpdate?.invoke(rms)

                if (rms < silenceThreshold) silenceCount += read else silenceCount = 0

                if (sampleCount >= maxFrames) { stopReason = StopReason.TIMEOUT; break }
                if (sampleCount >= minFrames && silenceCount >= silenceFrames) {
                    stopReason = StopReason.SILENCE; break
                }
            }
            if (shouldStop) stopReason = StopReason.MANUAL
        } finally {
            recorder.stop()
            recorder.release()
        }

        Recording(convertToFloat(allSamples, sampleCount), sampleCount.toFloat() / SAMPLE_RATE, stopReason)
    }

    fun stopEarly() { shouldStop = true }

    @SuppressLint("MissingPermission")
    suspend fun recordStreaming(
        maxSeconds:       Float = ModelConfig.MAX_RECORD_SECONDS,
        onChunk:          (FloatArray) -> Unit,
        onRmsUpdate:      ((Float) -> Unit)? = null,
    ): StopReason = withContext(Dispatchers.IO) {

        shouldStop = false

        val bufSize = maxOf(
            AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CFG, AUDIO_FORMAT),
            CHUNK_FRAMES * 2
        )

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE, CHANNEL_CFG, AUDIO_FORMAT, bufSize
        )

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord init failed")
            recorder.release()
            return@withContext StopReason.ERROR
        }

        val maxFrames   = (maxSeconds * SAMPLE_RATE).toInt()
        var totalFrames = 0
        val chunkBuffer = ShortArray(CHUNK_FRAMES)
        var stopReason  = StopReason.TIMEOUT

        try {
            recorder.startRecording()
            while (coroutineContext.isActive && !shouldStop) {
                val read = recorder.read(chunkBuffer, 0, CHUNK_FRAMES)
                if (read <= 0) continue

                val floats = FloatArray(read) { chunkBuffer[it] / 32768.0f }
                onChunk(floats)

                val rms = computeRms(chunkBuffer, read)
                onRmsUpdate?.invoke(rms)

                totalFrames += read
                if (totalFrames >= maxFrames) { stopReason = StopReason.TIMEOUT; break }
            }
            if (shouldStop) stopReason = StopReason.MANUAL
        } finally {
            recorder.stop()
            recorder.release()
        }

        stopReason
    }

    private fun computeRms(buf: ShortArray, len: Int): Float {
        var sum = 0.0
        for (i in 0 until len) sum += (buf[i] / 32768.0) * (buf[i] / 32768.0)
        return sqrt(sum / len).toFloat()
    }

    private fun convertToFloat(shorts: ShortArray, count: Int) = FloatArray(count) { shorts[it] / 32768.0f }
}
