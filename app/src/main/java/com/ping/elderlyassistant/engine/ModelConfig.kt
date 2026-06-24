package com.ping.elderlyassistant.engine

import android.content.Context

/**
 * Single source of truth for model paths and inference hyper-parameters.
 *
 * ── Model selection rationale (June 2026) ──────────────────────────────────
 *
 * ASR: SenseVoice-Small (FunAudioLLM / Alibaba, 2024)
 *   • 15× faster than Whisper-Large; ~3× lower CER on Chinese vs whisper.cpp tiny
 *   • Non-autoregressive → latency scales with audio length, not model depth
 *   • Supports Mandarin, Taiwanese, Cantonese, 50+ languages
 *   • Runs via sherpa-onnx AAR on CPU — no custom NDK build required
 *   • Model: model.int8.onnx (~234 MB) + tokens.txt
 *
 * LLM: Qwen3-1.7B (Alibaba, April 2025)
 *   • Replaces Qwen2.5-1.5B — better reasoning at the same parameter count
 *   • Native function-calling / tool-use schema → more reliable JSON output
 *   • Hybrid thinking: add `/no_think` in system prompt for fast agent mode
 *   • Q4F16_1 quantisation ≈ 1.1 GB; fits comfortably in 12 GB POCO F6 RAM
 *   • Available via MLC LLM for Android (OpenCL / GPU acceleration)
 *
 * ── Model file placement ───────────────────────────────────────────────────
 *   /sdcard/Android/data/com.ping.elderlyassistant[.debug]/files/models/
 *       sense_voice/
 *           model.int8.onnx
 *           tokens.txt
 *       Qwen3-1.7B-q4f16_1/
 *           mlc-chat-config.json
 *           ndarray-cache.json
 *           params_shard_*.bin
 *
 * No READ_EXTERNAL_STORAGE permission needed (app-specific external storage).
 */
object ModelConfig {

    // ── SenseVoice ASR ────────────────────────────────────────────────────────
    const val SENSE_VOICE_MODEL_FILE  = "model.int8.onnx"
    const val SENSE_VOICE_TOKENS_FILE = "tokens.txt"
    const val SENSE_VOICE_THREADS     = 2

    /**
     * Primary language for ASR.
     * sherpa-onnx / SenseVoice language codes: "zh", "yue", "en", "auto"
     * "zh" covers Standard Mandarin and handles accented speech well.
     */
    const val ASR_LANGUAGE   = "zh"
    const val ASR_USE_ITN    = true   // inverse text normalisation (三點五 → 3.5)

    /** Sample rate Whisper / SenseVoice both expect: 16 kHz mono PCM. */
    const val ASR_SAMPLE_RATE = 16_000

    // ── Qwen3-1.7B via MLC LLM ───────────────────────────────────────────────
    const val QWEN_MODEL_NAME = "Qwen3-1.7B-q4f16_1"
    const val QWEN_MODEL_LIB  = "Qwen3_1_7B_q4f16_1_android"

    /** Max new tokens per generation — 256 is ample for a JSON action object. */
    const val LLM_MAX_NEW_TOKENS = 256

    /**
     * Very low temperature → near-deterministic JSON output.
     * Qwen3's `/no_think` system prompt further suppresses reasoning chains.
     */
    const val LLM_TEMPERATURE = 0.05f

    // ── Recording / VAD ──────────────────────────────────────────────────────
    const val MAX_RECORD_SECONDS    = 10f
    const val MIN_RECORD_SECONDS    = 0.4f
    /** Auto-stop after this many seconds of consecutive silence. */
    const val VAD_SILENCE_SECONDS   = 1.4f
    /** Normalised RMS below this is silence. Tune on the target device. */
    const val VAD_SILENCE_THRESHOLD = 0.012f

    // ── Path helpers ──────────────────────────────────────────────────────────

    fun modelsDir(context: Context): String =
        context.getExternalFilesDir("models")?.absolutePath
            ?: context.filesDir.absolutePath + "/models"

    fun senseVoiceDir(context: Context): String =
        "${modelsDir(context)}/sense_voice"

    fun senseVoiceModelPath(context: Context): String =
        "${senseVoiceDir(context)}/$SENSE_VOICE_MODEL_FILE"

    fun senseVoiceTokensPath(context: Context): String =
        "${senseVoiceDir(context)}/$SENSE_VOICE_TOKENS_FILE"

    fun qwenModelDir(context: Context): String =
        "${modelsDir(context)}/$QWEN_MODEL_NAME"
}
