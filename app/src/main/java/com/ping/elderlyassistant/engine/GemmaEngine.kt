package com.ping.elderlyassistant.engine

import android.content.Context
import android.os.Build
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.LogSeverity
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import com.ping.elderlyassistant.pipeline.PromptBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * [LlmEngine] backed by Google LiteRT LM (litertlm-android:0.12.0).
 *
 * Backend priority:
 *   Qualcomm / MediaTek: NPU → GPU → CPU
 *   Google Tensor (Pixel):  GPU → CPU  (Tensor NPU requires an AOT model variant)
 *   Unknown:                GPU → CPU
 *
 *   cacheDir = null — let LiteRT LM manage its own shader cache; passing an
 *   explicit app-cache path triggers permission failures on some builds.
 *   SamplerConfig = null for NPU (required by LiteRT LM).
 */
class GemmaEngine(private val context: Context) : LlmEngine {

    companion object {
        private const val TAG = "GemmaEngine"
    }

    @Volatile private var engine: Engine? = null
    @Volatile private var _loaded = false
    @Volatile private var _activeBackend = ""
    private var _stats = LlmEngine.InferenceStats()

    override fun isLoaded(): Boolean = _loaded

    override suspend fun load(): LlmEngine.LoadResult = withContext(Dispatchers.IO) {
        if (_loaded) return@withContext LlmEngine.LoadResult(success = true)

        Engine.setNativeMinLogSeverity(LogSeverity.ERROR)

        val modelPath    = ModelConfig.gemmaModelPath(context)
        val nativeLibDir = context.applicationInfo.nativeLibraryDir

        // Detect SoC family so we can skip NPU on Google Tensor devices:
        // Tensor NPU requires a separately compiled AOT model; the standard
        // .litertlm file only contains GPU/CPU delegates.
        val socMfr   = Build.SOC_MANUFACTURER.lowercase()
        val socModel = Build.SOC_MODEL.lowercase()
        val brand    = Build.BRAND.lowercase()
        val isGoogleTensor = brand == "google" || socMfr.contains("google") ||
                socModel.startsWith("gs") || socModel.startsWith("zuma") ||
                socModel == "tango" || socModel == "rio"
        val isQualcomm  = socMfr.contains("qualcomm")
        val isMediaTek  = socMfr.contains("mediatek")
        Log.i(TAG, "SoC: mfr=$socMfr model=$socModel brand=$brand " +
                "(tensor=$isGoogleTensor qualcomm=$isQualcomm mediatek=$isMediaTek)")

        // cacheDir = null: let LiteRT LM use its own default shader cache location.
        // Passing an explicit app-cache path has caused permission failures on some builds.
        // visionBackend: required by EngineConfig in litertlm 0.12.0. We don't send images,
        // but the param must be provided. CPU is used as a no-op fallback for vision.
        data class BackendSpec(val name: String, val compute: Backend, val vision: Backend, val context_tokens: Int)

        val candidates = mutableListOf<BackendSpec>()
        if (isQualcomm || isMediaTek) {
            candidates += BackendSpec("NPU", Backend.NPU(nativeLibDir), Backend.CPU(), ModelConfig.LLM_MAX_CONTEXT_TOKENS)
        } else if (isGoogleTensor) {
            Log.i(TAG, "Google Tensor NPU requires AOT model — skipping NPU, trying GPU")
        }
        candidates += BackendSpec("GPU", Backend.GPU(), Backend.GPU(), ModelConfig.LLM_MAX_CONTEXT_TOKENS)
        candidates += BackendSpec("CPU", Backend.CPU(), Backend.CPU(), ModelConfig.LLM_MAX_CONTEXT_TOKENS)

        var lastError: Exception? = null
        for (spec in candidates) {
            try {
                val config = EngineConfig(
                    modelPath     = modelPath,
                    backend       = spec.compute,
                    visionBackend = spec.vision,
                    maxNumTokens  = spec.context_tokens,
                    cacheDir      = null,
                )
                val e = Engine(config)
                e.initialize()
                engine         = e
                _activeBackend = spec.name
                _loaded        = true
                Log.i(TAG, "Gemma 4 E2B loaded  backend='${spec.name}'  model=$modelPath")
                return@withContext LlmEngine.LoadResult(success = true)
            } catch (ex: Exception) {
                Log.w(TAG, "Backend '${spec.name}' failed (${ex.javaClass.simpleName}): ${ex.message}")
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
        Log.d(TAG, "generate(): system=${PromptBuilder.SYSTEM_INSTRUCTION.length} chars, " +
                "prompt=${prompt.length} chars, maxCtx=${ModelConfig.LLM_MAX_CONTEXT_TOKENS}")

        try {
            val convConfig = ConversationConfig(
                // SamplerConfig must be null for NPU backend (LiteRT LM requirement)
                samplerConfig = if (_activeBackend == "NPU") null else SamplerConfig(
                    topK        = ModelConfig.LLM_TOP_K,
                    topP        = ModelConfig.LLM_TOP_P,
                    temperature = temperature.toDouble(),
                ),
                // GPU uses the compact instruction to leave more KV-cache budget for output.
                systemInstruction = Contents.of(mutableListOf(Content.Text(
                    if (_activeBackend == "GPU") PromptBuilder.SYSTEM_INSTRUCTION_COMPACT
                    else PromptBuilder.SYSTEM_INSTRUCTION
                ))),
                tools           = emptyList(),
                initialMessages = emptyList(),
            )

            val conversation = e.createConversation(convConfig)

            suspendCancellableCoroutine<Unit> { cont ->
                conversation.sendMessageAsync(
                    Contents.of(mutableListOf(Content.Text(prompt))),
                    object : MessageCallback {
                        override fun onMessage(message: Message) {
                            val partial = message.toString()
                            sb.append(partial)
                            onToken(partial)
                        }
                        override fun onDone() {
                            if (sb.isEmpty()) Log.w(TAG,
                                "generate() onDone with 0 tokens — context overflow? " +
                                "maxCtx=${ModelConfig.LLM_MAX_CONTEXT_TOKENS}")
                            if (cont.isActive) cont.resume(Unit)
                        }
                        override fun onError(throwable: Throwable) {
                            if (cont.isActive) cont.resumeWithException(throwable)
                        }
                    },
                    emptyMap()
                )
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

    override fun activeBackend(): String = _activeBackend
}
