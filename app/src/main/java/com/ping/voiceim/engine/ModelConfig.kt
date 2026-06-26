package com.ping.voiceim.engine

import android.content.Context

object ModelConfig {

    // Qwen3-ASR-0.6B-int8
    const val QWEN3_ASR_DIR           = "qwen3_asr"
    const val QWEN3_ASR_CONV_FRONTEND = "conv_frontend.onnx"
    const val QWEN3_ASR_ENCODER       = "encoder.int8.onnx"
    const val QWEN3_ASR_DECODER       = "decoder.int8.onnx"
    const val QWEN3_ASR_TOKENIZER_DIR = "tokenizer"
    const val QWEN3_ASR_THREADS       = 4

    const val ASR_SAMPLE_RATE = 16_000
    val ASR_PROVIDER_PRIORITY = listOf("nnapi", "cpu")

    // Recording / VAD
    const val MAX_RECORD_SECONDS    = 15f
    const val MIN_RECORD_SECONDS    = 0.4f
    const val VAD_SILENCE_SECONDS   = 1.5f
    const val VAD_SILENCE_THRESHOLD = 0.012f

    // Model placement:
    //   /sdcard/Android/data/com.ping.voiceim[.debug]/files/models/qwen3_asr/
    //       conv_frontend.onnx  encoder.int8.onnx  decoder.int8.onnx
    //       tokenizer/  (directory with vocab files)

    fun modelsDir(context: Context): String =
        context.getExternalFilesDir("models")?.absolutePath
            ?: context.filesDir.absolutePath + "/models"

    fun qwen3AsrDir(context: Context)             = "${modelsDir(context)}/$QWEN3_ASR_DIR"
    fun qwen3AsrConvFrontendPath(context: Context) = "${qwen3AsrDir(context)}/$QWEN3_ASR_CONV_FRONTEND"
    fun qwen3AsrEncoderPath(context: Context)      = "${qwen3AsrDir(context)}/$QWEN3_ASR_ENCODER"
    fun qwen3AsrDecoderPath(context: Context)      = "${qwen3AsrDir(context)}/$QWEN3_ASR_DECODER"
    fun qwen3AsrTokenizerDir(context: Context)     = "${qwen3AsrDir(context)}/$QWEN3_ASR_TOKENIZER_DIR"
}
