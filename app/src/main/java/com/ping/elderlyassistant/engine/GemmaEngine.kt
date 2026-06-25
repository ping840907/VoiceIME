package com.ping.elderlyassistant.engine

import android.content.Context
import android.util.Log
import com.google.ai.edge.litert.lm.Backend
import com.google.ai.edge.litert.lm.Conversation
import com.google.ai.edge.litert.lm.ConversationConfig
import com.google.ai.edge.litert.lm.Engine
import com.google.ai.edge.litert.lm.EngineConfig
import com.google.ai.edge.litert.lm.MessageCallback
import com.ping.elderlyassistant.pipeline.PromptBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * LlmEngine backed by Google LiteRT LM (litert-lm-android AAR).
 *
 * Model: Gemma 4 E2B  (gemma4-e2b-it-int4.task — ~1.3 GB)
 *
 * ── Model file placement ──────────────────────────────────────────────────────
 *   /sdcard/Android/data/com.ping.elderlyassistant[.debug]/files/models/
 *       gemma4-e2b-it-int4.task
 *
 * ── Setup checklist ───────────────────────────────────────────────────────────
 *   1. Add to app/build.gradle:
 *        implementation 'com.google.ai.edge.litert:litert-lm-android:1.0.0'
 *   2. Download the model:
 *        https://huggingface.co/google/gemma-4-e2b-it-litert-preview
 *      or via Google AI Edge Gallery's "Download" flow.
 *   3. Push to device:
 *        adb push gemma4-e2b-it-int4.task \
 *          /sdcard/Android/data/com.ping.elderlyassistant.debug/files/models/
 *
 * ── Backend priority ──────────────────────────────────────────────────────────
 *   1. GPU  (OpenCL — Adreno / Mali; ~8–20 t/s on mid-range SoC)
 *   2. CPU  (always available; ~1–3 t/s — last resort)
 *   NPU skipped: requires Qualcomm QNN native libs bundled in APK.
 *
 * ── LiteRT LM API reference ───────────────────────────────────────────────────
 *   https://github.com/google-ai-edge/gallery
 *   Package: com.google.ai.edge.litert.lm
 *   Key classes: Engine, EngineConfig, Conversation, ConversationConfig,
 *                MessageCallback, Backend
 */
class GemmaEngine(private val context: Context) : LlmEngine {

    companion object {
        private const val TAG = "GemmaEngine"
    }

    @Volatile private var engine: Engine? = null
    @Volatile private var _loaded = false
    @Volatile private var _activeBackend = ""
    private var _stats = LlmEngine.InferenceStats()

    // ── LlmEngine ─────────────────────────────────────────────────────────────

    override fun isLoaded(): Boolean = _loaded

    /**
     * Loads Gemma 4 E2B, trying GPU then CPU.
     * Each failed backend is logged and skipped; the first success is used.
     */
    override suspend fun load(): LlmEngine.LoadResult = withContext(Dispatchers.IO) {
        if (_loaded) return@withContext LlmEngine.LoadResult(success = true)

        val modelPath = ModelConfig.gemmaModelPath(context)
        val cacheDir  = (context.externalCacheDir ?: context.cacheDir).absolutePath

        // GPU first (fast), CPU fallback (always available)
        val backends = listOf<Pair<String, () -> Backend>>(
            "GPU" to { Backend.GPU() },
            "CPU" to { Backend.CPU() },
        )

        var lastError: Exception? = null
        for ((name, backendFactory) in backends) {
            try {
                val config = EngineConfig.Builder()
                    .modelPath(modelPath)
                    .backend(backendFactory())
                    .cacheDir(cacheDir)
                    .build()
                val e = Engine(context, config)
                e.initialize()
                engine         = e
                _activeBackend = name
                _loaded        = true
                Log.i(TAG, "Gemma 4 E2B loaded  backend='$name'  model=$modelPath")
                return@withContext LlmEngine.LoadResult(success = true)
            } catch (ex: Exception) {
                Log.w(TAG, "Backend '$name' failed: ${ex.message}")
                lastError = ex
            }
        }

        val error = lastError?.message ?: "All backends failed"
        Log.e(TAG, "GemmaEngine load failed: $error")
        LlmEngine.LoadResult(success = false, error = error)
    }

    override fun unload() {
        if (!_loaded) return
        runCatching { engine?.close() }
        engine  = null
        _loaded = false
        Log.i(TAG, "Gemma unloaded")
    }

    /**
     * Generate a response for [prompt].
     *
     * Each call creates a fresh [Conversation] so history state doesn't accumulate
     * across pipeline steps (history is already embedded in the prompt by
     * [PromptBuilder.build]).  The system instruction is injected once per
     * conversation via [ConversationConfig].
     */
    override suspend fun generate(
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        onToken: (String) -> Unit
    ): String = withContext(Dispatchers.IO) {
        val e = engine ?: run {
            Log.w(TAG, "generate() called before load()")
            return@withContext ""
        }

        val t0 = System.currentTimeMillis()
        val sb = StringBuilder()

        try {
            val convConfig: ConversationConfig = ConversationConfig.Builder()
                .topK(ModelConfig.LLM_TOP_K)
                .temperature(temperature)
                .maxOutputTokens(maxTokens)
                .systemInstruction(PromptBuilder.SYSTEM_INSTRUCTION)
                .build()

            val conversation: Conversation = e.createConversation(convConfig)

            suspendCancellableCoroutine<Unit> { cont ->
                conversation.sendMessageAsync(prompt, object : MessageCallback {
                    override fun onMessage(partial: String) {
                        sb.append(partial)
                        onToken(partial)
                    }
                    override fun onDone() {
                        if (cont.isActive) cont.resume(Unit)
                    }
                    override fun onError(ex: Exception) {
                        if (cont.isActive) cont.resumeWithException(ex)
                    }
                })
                cont.invokeOnCancellation { runCatching { conversation.cancelProcess() } }
            }
        } catch (ex: Exception) {
            Log.e(TAG, "generate() error: ${ex.message}", ex)
        }

        val totalMs = System.currentTimeMillis() - t0
        val tokens  = sb.length / 4
        _stats = LlmEngine.InferenceStats(
            prefillTokens  = prompt.length / 4,
            decodeTokens   = tokens,
            decodeSpeedTps = if (totalMs > 0) tokens / (totalMs / 1000f) else 0f,
            totalMs        = totalMs
        )
        Log.i(TAG, "Inference done: backend=$_activeBackend  $_stats")
        sb.toString()
    }

    override fun lastStats(): LlmEngine.InferenceStats = _stats
}
