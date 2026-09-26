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

    enum class StopReason { SILENCE, INITIAL_TIMEOUT, TIMEOUT, MANUAL, ERROR }

    data class Recording(
        val samples: FloatArray,
        val durationSeconds: Float,
        val stopReason: StopReason,
    )

    @Volatile private var shouldStop = false

    @SuppressLint("MissingPermission")
    suspend fun recordUntilSilence(
        maxSeconds:            Float = ModelConfig.MAX_RECORD_SECONDS,
        silenceSeconds:        Float = ModelConfig.VAD_SILENCE_SECONDS,
        minSeconds:            Float = ModelConfig.MIN_RECORD_SECONDS,
        silenceThreshold:      Float = ModelConfig.VAD_SILENCE_THRESHOLD,
        initialTimeoutSeconds: Float = ModelConfig.VAD_INITIAL_TIMEOUT_SECONDS,
        speechThreshold:       Float = ModelConfig.VAD_SPEECH_THRESHOLD,
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

        val maxFrames             = (maxSeconds * SAMPLE_RATE).toInt()
        val minFrames             = (minSeconds * SAMPLE_RATE).toInt()
        val trailingSilenceFrames = (silenceSeconds * SAMPLE_RATE).toInt()
        val initialTimeoutFrames  = (initialTimeoutSeconds * SAMPLE_RATE).toInt()
        val minSpeechFrames       = (0.10f * SAMPLE_RATE).toInt() // ~100ms 語音確認開口
        val startupIgnoreFrames   = (0.08f * SAMPLE_RATE).toInt() // 前 80ms 忽略按鍵物理敲擊震動

        val allSamples  = ShortArray(maxFrames)
        var sampleCount = 0
        val chunkBuffer = ShortArray(CHUNK_FRAMES)
        var stopReason   = StopReason.TIMEOUT

        var hasSpoken = false
        var consecutiveSpeechFrames = 0
        var trailingSilenceCount = 0
        var initialSilenceCount = 0

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

                if (!hasSpoken) {
                    // 階段一：等待使用者開口說話（給予使用者充足的起話機會，不以停頓短秒數誤殺）
                    if (sampleCount > startupIgnoreFrames) {
                        if (rms >= speechThreshold) {
                            consecutiveSpeechFrames += read
                            if (consecutiveSpeechFrames >= minSpeechFrames) {
                                hasSpoken = true
                                trailingSilenceCount = 0
                            }
                        } else {
                            consecutiveSpeechFrames = maxOf(0, consecutiveSpeechFrames - read / 2)
                            initialSilenceCount += read
                            if (initialSilenceCount >= initialTimeoutFrames) {
                                stopReason = StopReason.INITIAL_TIMEOUT
                                break
                            }
                        }
                    }
                } else {
                    // 階段二：使用者已開口，正式啟用斷句停頓偵測 (VAD)
                    if (rms < silenceThreshold) {
                        trailingSilenceCount += read
                    } else {
                        trailingSilenceCount = 0
                    }

                    if (sampleCount >= minFrames && trailingSilenceCount >= trailingSilenceFrames) {
                        stopReason = StopReason.SILENCE
                        break
                    }
                }

                if (sampleCount >= maxFrames) {
                    stopReason = StopReason.TIMEOUT
                    break
                }
            }
            if (shouldStop) stopReason = StopReason.MANUAL
        } finally {
            recorder.stop()
            recorder.release()
        }

        val finalSamples = if (stopReason == StopReason.INITIAL_TIMEOUT) {
            FloatArray(0)
        } else {
            convertToFloat(allSamples, sampleCount)
        }

        Recording(finalSamples, sampleCount.toFloat() / SAMPLE_RATE, stopReason)
    }

    fun stopEarly() { shouldStop = true }

    @SuppressLint("MissingPermission")
    suspend fun recordStreaming(
        silenceThreshold:      Float = ModelConfig.VAD_SILENCE_THRESHOLD,
        silenceSeconds:        Float = 0f,
        minSeconds:            Float = 0.5f,
        maxSeconds:            Float = ModelConfig.MAX_RECORD_SECONDS,
        initialTimeoutSeconds: Float = ModelConfig.VAD_INITIAL_TIMEOUT_SECONDS,
        speechThreshold:       Float = ModelConfig.VAD_SPEECH_THRESHOLD,
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

        val maxFrames             = (maxSeconds * SAMPLE_RATE).toInt()
        val minFrames             = (minSeconds * SAMPLE_RATE).toInt()
        val trailingSilenceFrames = (silenceSeconds * SAMPLE_RATE).toInt()
        val initialTimeoutFrames  = (initialTimeoutSeconds * SAMPLE_RATE).toInt()
        val minSpeechFrames       = (0.10f * SAMPLE_RATE).toInt()
        val startupIgnoreFrames   = (0.08f * SAMPLE_RATE).toInt()

        var totalFrames   = 0
        val chunkBuffer   = ShortArray(CHUNK_FRAMES)
        var stopReason    = StopReason.TIMEOUT

        var hasSpoken = false
        var consecutiveSpeechFrames = 0
        var trailingSilenceCount = 0
        var initialSilenceCount = 0

        try {
            recorder.startRecording()
            while (coroutineContext.isActive && !shouldStop) {
                val read = recorder.read(chunkBuffer, 0, CHUNK_FRAMES)
                if (read <= 0) continue

                val floats = FloatArray(read) { chunkBuffer[it] / 32768.0f }
                onChunk(floats)
                totalFrames += read

                val rms = computeRms(chunkBuffer, read)
                onRmsUpdate?.invoke(rms)

                if (silenceSeconds > 0f) {
                    if (!hasSpoken) {
                        // 階段一：等待使用者開口說話（給予使用者充足的起話機會）
                        if (totalFrames > startupIgnoreFrames) {
                            if (rms >= speechThreshold) {
                                consecutiveSpeechFrames += read
                                if (consecutiveSpeechFrames >= minSpeechFrames) {
                                    hasSpoken = true
                                    trailingSilenceCount = 0
                                }
                            } else {
                                consecutiveSpeechFrames = maxOf(0, consecutiveSpeechFrames - read / 2)
                                initialSilenceCount += read
                                if (initialSilenceCount >= initialTimeoutFrames) {
                                    stopReason = StopReason.INITIAL_TIMEOUT
                                    break
                                }
                            }
                        }
                    } else {
                        // 階段二：使用者已開口，正式啟用斷句停頓偵測 (VAD)
                        if (rms < silenceThreshold) {
                            trailingSilenceCount += read
                        } else {
                            trailingSilenceCount = 0
                        }

                        if (totalFrames >= minFrames && trailingSilenceCount >= trailingSilenceFrames) {
                            stopReason = StopReason.SILENCE
                            break
                        }
                    }
                }

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
