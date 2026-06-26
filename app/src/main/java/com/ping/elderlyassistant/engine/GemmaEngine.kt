package com.ping.elderlyassistant.engine

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
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
 * [LlmEngine] backed by Google LiteRT LM (litertlm-android:0.11.0).
 *
 * Backend priority: NPU (QNN) → GPU (OpenCL) → CPU
 *   cacheDir = null  — LiteRT LM manages its own GPU kernel cache internally;
 *   passing an explicit path can cause permission failures on external storage.
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

        val modelPath    = ModelConfig.gemmaModelPath(context)
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        // Internal cache for GPU compiled shaders — always writable, no extra permissions needed.
        val shaderCacheDir = context.cacheDir.absolutePath

        val backends = listOf<Pair<String, () -> Backend>>(
            "NPU" to { Backend.NPU(nativeLibDir) },
            "GPU" to { Backend.GPU() },
            "CPU" to { Backend.CPU() },
        )

        var lastError: Exception? = null
        for ((name, backendFactory) in backends) {
            try {
                val config = EngineConfig(
                    modelPath    = modelPath,
                    backend      = backendFactory(),
                    maxNumTokens = ModelConfig.LLM_MAX_CONTEXT_TOKENS,
                    cacheDir     = shaderCacheDir,
                )
                val e = Engine(config)
                e.initialize()
                engine         = e
                _activeBackend = name
                _loaded        = true
                Log.i(TAG, "Gemma 4 E2B loaded  backend='$name'  model=$modelPath")
                return@withContext LlmEngine.LoadResult(success = true)
            } catch (ex: Exception) {
                Log.w(TAG, "Backend '$name' failed (${ex.javaClass.simpleName}): ${ex.message}")
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
                systemInstruction = Contents.of(
                    mutableListOf(Content.Text(PromptBuilder.SYSTEM_INSTRUCTION))
                ),
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
