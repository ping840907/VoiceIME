package com.ping.elderlyassistant.engine

/**
 * Abstraction over the on-device LLM backend (currently mlc4j / MLC LLM).
 *
 * Keeping inference behind this interface means:
 *  - Phase 2: load + bench with pure text, no AccessibilityService involvement
 *  - Phase 3: generate() receives the composed prompt and streams action JSON
 *  - Future: swap backend (llama.cpp, MNN, etc.) without touching the pipeline
 */
interface LlmEngine {

    // ── State ──────────────────────────────────────────────────────────────────

    fun isLoaded(): Boolean

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    /**
     * Load the model into memory and warm up the GPU kernel.
     * Must be called on a background thread.
     */
    suspend fun load(): LoadResult

    /** Release native resources. Safe to call when not loaded. */
    fun unload()

    // ── Inference ─────────────────────────────────────────────────────────────

    /**
     * Generate a response for [prompt], streaming each new token to [onToken].
     * Returns the full concatenated response.
     *
     * The [prompt] is expected to already be in the model's chat template format
     * (ChatML for Qwen). [PipelineOrchestrator] constructs the prompt.
     */
    suspend fun generate(
        prompt: String,
        maxTokens: Int  = ModelConfig.LLM_MAX_NEW_TOKENS,
        temperature: Float = ModelConfig.LLM_TEMPERATURE,
        onToken: (String) -> Unit = {}
    ): String

    // ── Benchmarking ──────────────────────────────────────────────────────────

    fun lastStats(): InferenceStats

    // ── Data classes ──────────────────────────────────────────────────────────

    data class LoadResult(
        val success: Boolean,
        val error: String? = null
    )

    data class InferenceStats(
        val prefillTokens: Int = 0,
        val decodeTokens: Int = 0,
        /** Decode speed in tokens per second. Target: > 10 t/s on POCO F6. */
        val decodeSpeedTps: Float = 0f,
        val totalMs: Long = 0L
    ) {
        override fun toString() =
            "prefill=$prefillTokens decode=$decodeTokens " +
            "speed=%.1f t/s total=${totalMs}ms".format(decodeSpeedTps)
    }
}
