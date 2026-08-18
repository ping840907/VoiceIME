package com.ping.voiceime.engine

import android.content.Context

object ModelConfig {

    // ── Engine selection ──────────────────────────────────────────────────────
    const val PREF_ENGINE   = "asr_engine_selection"
    const val KEY_ENGINE    = "engine"
    const val ENGINE_QWEN3  = "qwen3"
    const val ENGINE_X_ASR  = "x_asr"

    // ── Qwen3-ASR-0.6B-int8 (offline, Simplified→Traditional via OpenCC) ─────
    const val QWEN3_ASR_DIR           = "qwen3_asr"
    const val QWEN3_ASR_CONV_FRONTEND = "conv_frontend.onnx"
    const val QWEN3_ASR_ENCODER       = "encoder.int8.onnx"
    const val QWEN3_ASR_DECODER       = "decoder.int8.onnx"
    const val QWEN3_ASR_TOKENIZER_DIR = "tokenizer"
    const val QWEN3_ASR_THREADS       = 4

    // ── X-ASR streaming transducer (online, native Traditional Chinese) ───────
    // Model files from: https://huggingface.co/Luigi/x-asr-zh-tw-en-streaming-ft75m
    // Place under: <modelsDir>/x_asr/
    //   encoder.int8.onnx   decoder.onnx   joiner.int8.onnx   tokens.txt
    const val X_ASR_DIR     = "x_asr"
    const val X_ASR_ENCODER = "encoder.int8.onnx"
    const val X_ASR_DECODER = "decoder.onnx"
    const val X_ASR_JOINER  = "joiner.int8.onnx"
    const val X_ASR_TOKENS  = "tokens.txt"
    const val X_ASR_THREADS = 4

    // ── Shared ────────────────────────────────────────────────────────────────
    const val ASR_SAMPLE_RATE = 16_000
    val ASR_PROVIDER_PRIORITY = listOf("nnapi", "cpu")

    // Recording / VAD
    const val MAX_RECORD_SECONDS    = 15f
    const val MIN_RECORD_SECONDS    = 0.4f
    const val VAD_SILENCE_SECONDS   = 1.5f
    const val VAD_SILENCE_THRESHOLD = 0.012f
    const val VAD_SILENCE_MIN       = 0.5f
    const val VAD_SILENCE_MAX       = 3.0f

    private const val PREF_VAD      = "vad_settings"
    private const val KEY_VAD_SILENCE = "silence_seconds"

    /** User-configurable pause length (seconds) before Qwen3 offline recording auto-stops. */
    fun vadSilenceSeconds(context: Context): Float =
        context.getSharedPreferences(PREF_VAD, Context.MODE_PRIVATE)
            .getFloat(KEY_VAD_SILENCE, VAD_SILENCE_SECONDS)

    fun setVadSilenceSeconds(context: Context, seconds: Float) =
        context.getSharedPreferences(PREF_VAD, Context.MODE_PRIVATE)
            .edit().putFloat(KEY_VAD_SILENCE, seconds.coerceIn(VAD_SILENCE_MIN, VAD_SILENCE_MAX)).apply()

    fun modelsDir(context: Context): String =
        context.getExternalFilesDir("models")?.absolutePath
            ?: context.filesDir.absolutePath + "/models"

    // Qwen3
    fun qwen3AsrDir(context: Context)             = "${modelsDir(context)}/$QWEN3_ASR_DIR"
    fun qwen3AsrConvFrontendPath(context: Context) = "${qwen3AsrDir(context)}/$QWEN3_ASR_CONV_FRONTEND"
    fun qwen3AsrEncoderPath(context: Context)      = "${qwen3AsrDir(context)}/$QWEN3_ASR_ENCODER"
    fun qwen3AsrDecoderPath(context: Context)      = "${qwen3AsrDir(context)}/$QWEN3_ASR_DECODER"
    fun qwen3AsrTokenizerDir(context: Context)     = "${qwen3AsrDir(context)}/$QWEN3_ASR_TOKENIZER_DIR"

    // X-ASR
    fun xAsrDir(context: Context)         = "${modelsDir(context)}/$X_ASR_DIR"
    fun xAsrEncoderPath(context: Context) = "${xAsrDir(context)}/$X_ASR_ENCODER"
    fun xAsrDecoderPath(context: Context) = "${xAsrDir(context)}/$X_ASR_DECODER"
    fun xAsrJoinerPath(context: Context)  = "${xAsrDir(context)}/$X_ASR_JOINER"
    fun xAsrTokensPath(context: Context)  = "${xAsrDir(context)}/$X_ASR_TOKENS"

    // Engine selection helpers
    fun selectedEngine(context: Context): String =
        context.getSharedPreferences(PREF_ENGINE, Context.MODE_PRIVATE)
            .getString(KEY_ENGINE, ENGINE_QWEN3) ?: ENGINE_QWEN3

    fun setSelectedEngine(context: Context, engine: String) =
        context.getSharedPreferences(PREF_ENGINE, Context.MODE_PRIVATE)
            .edit().putString(KEY_ENGINE, engine).apply()
}
