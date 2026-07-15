package com.ping.voiceim.engine

import android.content.Context

/** Where to fetch each engine's model files from, and how to lay them out on disk. */
object ModelDownloadSpec {

    data class RemoteFile(val url: String, val relativePath: String)

    /**
     * [archiveUrl] set → single tar.bz2 downloaded and extracted (first path
     * component stripped) into the engine's model directory.
     * [files] set → each entry downloaded individually into its relativePath.
     * Exactly one of the two is populated.
     */
    data class DownloadTarget(
        val engine: String,
        val archiveUrl: String? = null,
        val files: List<RemoteFile> = emptyList(),
    )

    /**
     * NOTE: the 1.7B archive filename/date below is inferred from the same GitHub release
     * naming convention as the verified 0.6B asset — it has not been confirmed against the
     * actual release listing (no network access at authoring time). If downloading it fails,
     * check https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models for the real filename
     * and update this URL.
     */
    fun qwen3(context: Context): DownloadTarget {
        val url = if (ModelConfig.qwen3ModelSize(context) == ModelConfig.QWEN3_SIZE_17B) {
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/" +
                "sherpa-onnx-qwen3-asr-1.7B-int8-2026-03-25.tar.bz2"
        } else {
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/" +
                "sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25.tar.bz2"
        }
        return DownloadTarget(engine = ModelConfig.ENGINE_QWEN3, archiveUrl = url)
    }

    fun xAsr(): DownloadTarget = DownloadTarget(
        engine = ModelConfig.ENGINE_X_ASR,
        files = listOf(
            RemoteFile(
                "https://huggingface.co/Luigi/x-asr-zh-tw-en-streaming-ft75m/resolve/main/${ModelConfig.X_ASR_ENCODER}",
                ModelConfig.X_ASR_ENCODER,
            ),
            RemoteFile(
                "https://huggingface.co/Luigi/x-asr-zh-tw-en-streaming-ft75m/resolve/main/${ModelConfig.X_ASR_DECODER}",
                ModelConfig.X_ASR_DECODER,
            ),
            RemoteFile(
                "https://huggingface.co/Luigi/x-asr-zh-tw-en-streaming-ft75m/resolve/main/${ModelConfig.X_ASR_JOINER}",
                ModelConfig.X_ASR_JOINER,
            ),
            RemoteFile(
                "https://huggingface.co/Luigi/x-asr-zh-tw-en-streaming-ft75m/resolve/main/${ModelConfig.X_ASR_TOKENS}",
                ModelConfig.X_ASR_TOKENS,
            ),
        ),
    )

    fun forEngine(context: Context, engine: String): DownloadTarget =
        if (engine == ModelConfig.ENGINE_X_ASR) xAsr() else qwen3(context)
}
