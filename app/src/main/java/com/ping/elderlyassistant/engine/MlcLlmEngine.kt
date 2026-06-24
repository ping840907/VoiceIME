package com.ping.elderlyassistant.engine

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * LlmEngine implementation backed by MLC LLM (mlc4j).
 *
 * Setup checklist (one-time, before first build):
 *   1. Download mlc4j AAR from https://github.com/mlc-ai/mlc-llm/releases
 *      and place it in app/libs/mlc4j-<version>.aar
 *   2. In app/build.gradle, uncomment:
 *        implementation fileTree(dir: 'libs', include: ['*.aar'])
 *   3. Build the Qwen2.5-1.5B model for Android:
 *        https://llm.mlc.ai/docs/deploy/android.html
 *      Copy the compiled .so into the AAR or app/src/main/jniLibs/arm64-v8a/
 *   4. Push model weights to device:
 *        adb push Qwen2.5-1.5B-Instruct-q4f16_1/ \
 *            /sdcard/Android/data/com.ping.elderlyassistant.debug/files/models/
 *
 * mlc4j API reference (v0.1.x):
 *   https://github.com/mlc-ai/mlc-llm/tree/main/android
 *
 * The imports below are from the mlc4j AAR. If your version uses different
 * class names (e.g. ai.mlc.mlcllm.MLCEngine vs ai.mlc.llm.LLMEngine),
 * adjust accordingly — the interface contract here stays the same.
 */
class MlcLlmEngine(private val context: Context) : LlmEngine {

    companion object {
        private const val TAG = "MlcLlmEngine"

        /** True when the mlc4j native library was found at startup. */
        val isMlcAvailable: Boolean by lazy {
            try {
                // Trigger class loading; throws if AAR / .so is absent
                Class.forName("ai.mlc.mlcllm.MLCEngine")
                true
            } catch (_: ClassNotFoundException) {
                Log.w(TAG, "mlc4j not found — LLM inference unavailable")
                false
            }
        }
    }

    // ── Lazy engine reference (avoids hard dependency at class load time) ────
    // We use reflection so the code compiles even when mlc4j AAR is absent.
    // In production (AAR present), direct imports are cleaner — replace the
    // reflection calls with:
    //
    //   import ai.mlc.mlcllm.MLCEngine
    //   private var engine: MLCEngine? = null

    private var engineInstance: Any? = null   // ai.mlc.mlcllm.MLCEngine
    private var _loaded = false
    private var _stats = LlmEngine.InferenceStats()

    // ── LlmEngine ─────────────────────────────────────────────────────────────

    override fun isLoaded(): Boolean = _loaded

    override suspend fun load(): LlmEngine.LoadResult = withContext(Dispatchers.IO) {
        if (!isMlcAvailable) {
            return@withContext LlmEngine.LoadResult(
                success = false,
                error = "mlc4j AAR not found. See MlcLlmEngine.kt setup checklist."
            )
        }

        val modelDir = ModelConfig.qwenModelDir(context)
        val modelLib = ModelConfig.QWEN_MODEL_LIB
        Log.i(TAG, "Loading model from $modelDir  lib=$modelLib")

        try {
            val engineClass = Class.forName("ai.mlc.mlcllm.MLCEngine")
            val inst = engineClass.getDeclaredConstructor().newInstance()

            // engine.reload(modelPath: String, modelLib: String)
            engineClass.getMethod("reload", String::class.java, String::class.java)
                .invoke(inst, modelDir, modelLib)

            engineInstance = inst
            _loaded = true
            Log.i(TAG, "Model loaded successfully")
            LlmEngine.LoadResult(success = true)
        } catch (ex: Exception) {
            Log.e(TAG, "load() failed: ${ex.message}", ex)
            _loaded = false
            LlmEngine.LoadResult(success = false, error = ex.message)
        }
    }

    override fun unload() {
        if (!_loaded) return
        try {
            engineInstance?.let { inst ->
                inst.javaClass.getMethod("unload").invoke(inst)
            }
        } catch (_: Exception) { /* ignore */ }
        engineInstance = null
        _loaded = false
        Log.i(TAG, "Model unloaded")
    }

    override suspend fun generate(
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        onToken: (String) -> Unit
    ): String = withContext(Dispatchers.IO) {
        val inst = engineInstance
        if (!_loaded || inst == null) {
            Log.w(TAG, "generate() called before model is loaded")
            return@withContext ""
        }

        val t0 = System.currentTimeMillis()
        val sb = StringBuilder()

        try {
            // Direct API call when mlc4j AAR is on the classpath:
            //
            //   val req = OpenAIProtocol.ChatCompletionRequest(
            //       messages = listOf(OpenAIProtocol.ChatCompletionMessage("user", prompt)),
            //       max_tokens = maxTokens,
            //       temperature = temperature.toDouble(),
            //       stream = true
            //   )
            //   for (chunk in engine.chat.completions.create(req)) {
            //       val delta = chunk.choices.firstOrNull()?.delta?.content ?: continue
            //       sb.append(delta); onToken(delta)
            //   }
            //
            // Reflection-based fallback used while AAR is optional:
            val engineClass = inst.javaClass

            // Try streaming chat.completions API first (mlc4j >= 0.1)
            runCatching {
                invokeStreamingApi(inst, engineClass, prompt, maxTokens, temperature, sb, onToken)
            }.onFailure { ex ->
                // Fallback: blocking generate(String) API (older mlc4j)
                Log.d(TAG, "Streaming API failed (${ex.message}), trying blocking generate()")
                val result = engineClass
                    .getMethod("generate", String::class.java)
                    .invoke(inst, prompt) as? String ?: ""
                sb.append(result)
                onToken(result)
            }
        } catch (ex: Exception) {
            Log.e(TAG, "generate() error: ${ex.message}", ex)
        }

        val totalMs = System.currentTimeMillis() - t0
        val tokens  = sb.length / 4   // rough token estimate
        _stats = LlmEngine.InferenceStats(
            prefillTokens  = prompt.length / 4,
            decodeTokens   = tokens,
            decodeSpeedTps = if (totalMs > 0) tokens / (totalMs / 1000f) else 0f,
            totalMs        = totalMs
        )
        Log.i(TAG, "Inference done: $_stats")
        sb.toString()
    }

    override fun lastStats(): LlmEngine.InferenceStats = _stats

    // ── Reflection helper for streaming API ───────────────────────────────────

    private fun invokeStreamingApi(
        inst: Any, engineClass: Class<*>,
        prompt: String, maxTokens: Int, temperature: Float,
        sb: StringBuilder, onToken: (String) -> Unit
    ) {
        // Construct request via reflection:
        //   OpenAIProtocol.ChatCompletionRequest(messages, max_tokens, temperature, stream)
        val protocolClass = Class.forName("ai.mlc.mlcllm.OpenAIProtocol")
        val messageClass  = Class.forName("ai.mlc.mlcllm.OpenAIProtocol\$ChatCompletionMessage")
        val requestClass  = Class.forName("ai.mlc.mlcllm.OpenAIProtocol\$ChatCompletionRequest")

        val message = messageClass
            .getDeclaredConstructor(String::class.java, String::class.java)
            .newInstance("user", prompt)

        val messages = listOf(message)
        val request  = requestClass
            .getDeclaredConstructor(List::class.java, Int::class.java, Double::class.java, Boolean::class.java)
            .newInstance(messages, maxTokens, temperature.toDouble(), true)

        // engine.chat.completions.create(request) returns Iterable<ChatCompletionStreamResponse>
        val chatObj        = engineClass.getMethod("getChat").invoke(inst)
        val completionsObj = chatObj!!.javaClass.getMethod("getCompletions").invoke(chatObj)
        val stream         = completionsObj!!.javaClass
            .getMethod("create", requestClass).invoke(completionsObj, request)

        for (chunk in (stream as Iterable<*>)) {
            val choices = chunk!!.javaClass.getMethod("getChoices").invoke(chunk) as List<*>
            val delta   = choices.firstOrNull()?.let { choice ->
                val d = choice.javaClass.getMethod("getDelta").invoke(choice)
                d?.javaClass?.getMethod("getContent")?.invoke(d) as? String
            } ?: continue
            sb.append(delta)
            onToken(delta)
        }
    }
}
