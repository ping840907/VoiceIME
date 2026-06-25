package com.ping.elderlyassistant.engine

/**
 * Common interface for on-device ASR backends.
 * Implementations: [SenseVoiceEngine], [Qwen3AsrEngine].
 */
interface AsrEngine {

    data class LoadResult(val success: Boolean, val error: String? = null)

    fun isLoaded(): Boolean

    suspend fun load(): LoadResult

    /** Release native resources. Safe to call when not loaded. */
    fun release()

    /**
     * Transcribe float32 PCM samples (16 kHz, mono, normalised to [-1, 1]).
     * Returns the recognised text, or an empty string on failure.
     */
    suspend fun transcribe(samples: FloatArray): String
}
