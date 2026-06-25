package com.ping.elderlyassistant.engine

import android.content.Context

/**
 * Single source of truth for model paths and inference hyper-parameters.
 *
 * ── Model selection rationale (June 2026) ──────────────────────────────────
 *
 * ASR (selectable in app settings):
 *   SenseVoice-Small (FunAudioLLM / Alibaba, 2024) — default
 *   • 15× faster than Whisper-Large; ~3× lower CER on Chinese vs whisper.cpp tiny
 *   • Non-autoregressive → latency scales with audio length, not model depth
 *   • Supports Mandarin, Taiwanese, Cantonese, 50+ languages
 *   • Model: model.int8.onnx (~234 MB) + tokens.txt
 *   Qwen3-ASR-0.6B-int8 (Alibaba, 2026)
 *   • Autoregressive transformer; stronger on noisy/accented speech (~600 MB)
 *   • Model dir: sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25/
 *
 * LLM: Gemma 4 E2B (Google, 2025)
 *   • Efficient 2B model — strong reasoning / instruction-following at low latency
 *   • INT4 quantised .task bundle (~1.3 GB); runs on GPU (OpenCL) or NPU (QNN)
 *   • Delivered as a single .task file via LiteRT LM (litert-lm-android AAR)
 *   • Model: https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm
 *
 * ── Model file placement ───────────────────────────────────────────────────
 *   /sdcard/Android/data/com.ping.elderlyassistant[.debug]/files/models/
 *       sense_voice/
 *           model.int8.onnx
 *           tokens.txt
 *       qwen3_asr/
 *           conv_frontend.onnx
 *           encoder.int8.onnx
 *           decoder.int8.onnx
 *           tokenizer/
 *       gemma-4-E2B-it-litert-lm.task
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
     * "auto" enables SenseVoice's built-in language detection for code-switching
     * between Taiwan Mandarin (台灣國語) and Taiwanese Hokkien (台語).
     * Switch to "zh" for pure Mandarin to lower CER; SenseVoice has no "tai" code.
     */
    const val ASR_LANGUAGE   = "auto"
    const val ASR_USE_ITN    = true   // inverse text normalisation (三點五 → 3.5)

    /** Sample rate Whisper / SenseVoice both expect: 16 kHz mono PCM. */
    const val ASR_SAMPLE_RATE = 16_000

    /**
     * ONNX Runtime execution providers tried in order for SenseVoice ASR.
     *
     *  "nnapi" — Android Neural Networks API (Android 8.1+).  Delegates
     *            compatible ops to whatever hardware accelerator the device
     *            exposes: Qualcomm Hexagon DSP, Adreno GPU, MediaTek APU, etc.
     *            ONNX Runtime silently falls back per-op to CPU for any op the
     *            device's NNAPI implementation doesn't support, so recognizer
     *            construction should always succeed on Android 8.1+.
     *  "cpu"   — Software ONNX Runtime; always available, always works.
     *
     * [SenseVoiceEngine] tries each in order; if construction throws (e.g., NNAPI
     * is missing or the ONNX delegate rejects the int8 model on that device),
     * the next provider is attempted.
     */
    val ASR_PROVIDER_PRIORITY = listOf("nnapi", "cpu")

    // ── Qwen3-ASR-0.6B-int8 ──────────────────────────────────────────────────
    const val QWEN3_ASR_DIR              = "qwen3_asr"
    const val QWEN3_ASR_CONV_FRONTEND    = "conv_frontend.onnx"
    const val QWEN3_ASR_ENCODER         = "encoder.int8.onnx"
    const val QWEN3_ASR_DECODER         = "decoder.int8.onnx"
    const val QWEN3_ASR_TOKENIZER_DIR   = "tokenizer"
    const val QWEN3_ASR_THREADS         = 4

    // ── Gemma 4 E2B via LiteRT LM ────────────────────────────────────────────
    /** Single .task file containing weights + tokenizer for Gemma 4 E2B INT4.
     *  Source: https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm
     */
    const val GEMMA_MODEL_FILE = "gemma-4-E2B-it.litertlm"

    /** Max new tokens per generation — 256 is ample for a JSON action object. */
    const val LLM_MAX_NEW_TOKENS = 256

    /** Very low temperature → near-deterministic JSON output. */
    const val LLM_TEMPERATURE = 0.05f

    /** Top-K sampling — 40 is Google's recommended default for Gemma. */
    const val LLM_TOP_K = 40

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

    fun qwen3AsrDir(context: Context): String =
        "${modelsDir(context)}/$QWEN3_ASR_DIR"

    fun qwen3AsrConvFrontendPath(context: Context): String =
        "${qwen3AsrDir(context)}/$QWEN3_ASR_CONV_FRONTEND"

    fun qwen3AsrEncoderPath(context: Context): String =
        "${qwen3AsrDir(context)}/$QWEN3_ASR_ENCODER"

    fun qwen3AsrDecoderPath(context: Context): String =
        "${qwen3AsrDir(context)}/$QWEN3_ASR_DECODER"

    fun qwen3AsrTokenizerDir(context: Context): String =
        "${qwen3AsrDir(context)}/$QWEN3_ASR_TOKENIZER_DIR"

    fun gemmaModelPath(context: Context): String =
        "${modelsDir(context)}/$GEMMA_MODEL_FILE"
}
