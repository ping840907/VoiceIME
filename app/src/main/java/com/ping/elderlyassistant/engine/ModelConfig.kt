package com.ping.elderlyassistant.engine

import android.content.Context
import android.os.Build

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
     * "auto" enables SenseVoice's built-in language detection, which handles
     * code-switching between Taiwan Mandarin (台灣國語) and Taiwanese Hokkien (台語).
     * SenseVoice does not have a dedicated "tai" code; "auto" is the best choice
     * for mixed Mandarin/Taiwanese speech. Use "zh" for pure Mandarin only.
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

    // ── Qwen3-1.7B via MLC LLM ───────────────────────────────────────────────
    const val QWEN_MODEL_NAME = "Qwen3-1.7B-q4f16_1"
    const val QWEN_MODEL_LIB  = "Qwen3_1_7B_q4f16_1_android"   // OpenCL GPU (standard build)

    /**
     * Optional NPU model-lib variants.  Each maps to a lib<name>.so that must be
     * compiled separately and placed in app/src/main/jniLibs/arm64-v8a/.
     * Absent .so files throw at reload() and are caught automatically — the next
     * candidate in [llmLibCandidates] is tried instead.
     *
     * Backend build notes (as of June 2026):
     *
     *   Qualcomm QNN  — Requires Qualcomm AI Engine Direct (QNN) SDK + custom
     *                   MLC-LLM compile.  Not included in the public mlc4j AAR.
     *                   mlc_llm compile --device qnn --quantization q4f16_1 …
     *
     *   MediaTek APU  — Requires MediaTek NeuroPilot SDK + custom MLC-LLM compile.
     *                   Not publicly available; academic use only.
     *
     *   OpenCL GPU    — PRIMARY path. Included in the standard mlc4j AAR release.
     *                   Works on Adreno (Qualcomm) and Mali (ARM) GPUs.
     *                   mlc_llm compile --device android --quantization q4f16_1 …
     *
     *   ARM64 CPU     — FALLBACK. Always available; ~10–50× slower than OpenCL.
     *                   mlc_llm compile --target llvm -mtriple arm64-linux-android …
     *
     * Note: Vulkan backend is NOT supported in standard MLC-LLM Android builds
     * (open upstream issue as of 2026); do not add it to candidates.
     */
    const val QWEN_LIB_QNN = "Qwen3_1_7B_q4f16_1_qnn"   // Qualcomm Hexagon NPU (custom build)
    const val QWEN_LIB_MTK = "Qwen3_1_7B_q4f16_1_mtk"   // MediaTek APU (custom build)
    const val QWEN_LIB_CPU = "Qwen3_1_7B_q4f16_1_arm64"  // ARM64 CPU / LLVM (very slow)

    /**
     * Returns MLC model-lib candidates in best-to-worst priority order for the
     * current device.  [MlcLlmEngine] tries each in sequence and uses the first
     * one whose reload() call succeeds.
     *
     * Priority:
     *   1. Device NPU  (Qualcomm QNN or MediaTek APU) — requires custom build, optional
     *   2. OpenCL GPU  (standard MLC Android build)   — works on virtually all Android GPUs
     *   3. ARM64 CPU   (LLVM, ~10–50× slower than GPU) — last resort
     *
     * Vulkan is intentionally excluded: it is not supported in the standard mlc4j
     * AAR for Android and would always fail, wasting load time.
     */
    fun llmLibCandidates(): List<String> = buildList {
        val soc = detectSocString()
        if ("qualcomm" in soc || "qcom" in soc || "msm" in soc || " sm" in soc)
            add(QWEN_LIB_QNN)
        if ("mediatek" in soc || "dimensity" in soc || " mt" in soc)
            add(QWEN_LIB_MTK)
        add(QWEN_MODEL_LIB)   // OpenCL GPU — standard mlc4j AAR, works on Adreno + Mali
        add(QWEN_LIB_CPU)
    }.distinct()

    /**
     * Normalised SoC identifier used for backend selection.
     * Uses [Build.SOC_MANUFACTURER]/[Build.SOC_MODEL] on API 31+;
     * falls back to [Build.HARDWARE]/[Build.BOARD] on older devices.
     */
    fun detectSocString(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}".lowercase()
        else
            "${Build.HARDWARE} ${Build.BOARD}".lowercase()

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
