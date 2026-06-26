package com.ping.elderlyassistant.pipeline

import android.content.Context
import android.util.Log
import com.github.houbb.opencc4j.util.ZhConverterUtil
import com.ping.elderlyassistant.AssistantAccessibilityService
import com.ping.elderlyassistant.NodeSerializer
import com.ping.elderlyassistant.ServicePrefs
import com.ping.elderlyassistant.engine.AsrEngine
import com.ping.elderlyassistant.engine.AudioRecorder
import com.ping.elderlyassistant.engine.GemmaEngine
import com.ping.elderlyassistant.engine.LlmEngine
import com.ping.elderlyassistant.engine.ModelConfig
import com.ping.elderlyassistant.engine.Qwen3AsrEngine
import com.ping.elderlyassistant.engine.SenseVoiceEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Central coordinator for the voice-to-action pipeline.
 *
 * Two entry points:
 *   [startListening]  — records audio → SenseVoice ASR → Gemma 4 action loop
 *   [startWithText]   — accepts typed text, skips record/ASR, goes straight to LLM
 *
 * Multi-step loop (≤ [AutomationGuard.MAX_STEPS], timeout [AutomationGuard.TIMEOUT_MS]):
 *   capture screen → LLM generates JSON action → ActionExecutor runs it → repeat
 *   until Result.Done, Blocked, Failure, max-steps, or timeout.
 */
class PipelineOrchestrator(private val context: Context) {

    companion object {
        private const val TAG = "Pipeline"
    }

    // ── Engines ───────────────────────────────────────────────────────────────
    private val asr: AsrEngine by lazy {
        when (ServicePrefs.getAsrEngine(context)) {
            ServicePrefs.ASR_ENGINE_QWEN3 -> Qwen3AsrEngine(context)
            else                           -> SenseVoiceEngine(context)
        }
    }
    private val llm by lazy { GemmaEngine(context) }
    private val recorder = AudioRecorder()
    private val executor = ActionExecutor(context)

    // ── Observable state ──────────────────────────────────────────────────────
    sealed class State {
        /** Models are being loaded in the background; [message] describes current progress. */
        data class ModelLoading(val message: String) : State()
        object Idle       : State()
        object Recording  : State()
        data class Transcribing(val durationSec: Float) : State()
        data class Thinking(val transcript: String)     : State()
        data class Executing(val actionJson: String)    : State()
        data class Done(
            val transcript: String,
            val actionJson: String?,
            val llmStats: LlmEngine.InferenceStats?
        ) : State()
        /** Model responded with plain text instead of a JSON action. */
        data class Reply(val text: String) : State()
        data class Error(val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    @Volatile private var _llmBackend = ""
    /** Returns the active LLM hardware backend ("NPU" / "GPU" / "CPU"), or "" if not yet loaded. */
    fun llmBackend(): String = _llmBackend

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pipelineJob: Job? = null

    // ── Pre-warming ───────────────────────────────────────────────────────────

    /** Call once from FloatingBubbleService.onCreate() to warm engines in background. */
    fun preloadModels() {
        scope.launch {
            _state.value = State.ModelLoading("準備中（1／2）：語音辨識…")
            val asrResult = asr.load()
            val asrName = asr::class.simpleName
            if (asrResult.success) Log.i(TAG, "$asrName ready ✓")
            else Log.w(TAG, "$asrName unavailable: ${asrResult.error}")

            _state.value = State.ModelLoading("準備中（2／2）：AI 模型，首次約需 30–60 秒…")
            val llmResult = llm.load()
            if (llmResult.success) {
                _llmBackend = llm.activeBackend()
                Log.i(TAG, "Gemma 4 E2B ready ✓  backend=$_llmBackend")
            } else Log.w(TAG, "Gemma unavailable: ${llmResult.error}")

            // Pre-warm OpenCC dictionary so first transcription doesn't pay the load cost.
            runCatching { ZhConverterUtil.toTraditional("预热") }

            _state.value = State.Idle
        }
    }

    // ── Pipeline control ──────────────────────────────────────────────────────

    /** Record audio via microphone → transcribe → run LLM action loop. */
    fun startListening() {
        val s = _state.value
        if (s is State.ModelLoading) {
            Log.w(TAG, "Models still loading — ignoring startListening()"); return
        }
        if (s !is State.Idle) {
            Log.w(TAG, "Pipeline busy — ignoring startListening()")
            return
        }
        pipelineJob = scope.launch { runPipeline() }
    }

    /**
     * Skip recording/ASR and use [text] as the user instruction directly.
     * Useful for keyboard input mode.
     */
    fun startWithText(text: String) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return
        val s = _state.value
        if (s is State.ModelLoading) {
            Log.w(TAG, "Models still loading — ignoring startWithText()"); return
        }
        if (s !is State.Idle) {
            Log.w(TAG, "Pipeline busy — ignoring startWithText()")
            return
        }
        pipelineJob = scope.launch { runLlmLoop(trimmed) }
    }

    fun stopRecordingEarly() = recorder.stopEarly()

    /** Abort any active step and return to Idle. */
    fun cancel() {
        pipelineJob?.cancel()
        recorder.stopEarly()
        _state.value = State.Idle
    }

    // ── Voice pipeline ────────────────────────────────────────────────────────

    private suspend fun runPipeline() {

        // 1. Record
        _state.value = State.Recording
        val recording = recorder.recordUntilSilence()

        if (recording.samples.isEmpty() || recording.stopReason == AudioRecorder.StopReason.ERROR) {
            emitTerminal(State.Error("錄音失敗，請重試")); return
        }

        // If cancel() was called during recording, stop here rather than overwriting the
        // Idle state that cancel() already set with a new Transcribing state.
        currentCoroutineContext().ensureActive()

        // 2. Transcribe (SenseVoice-Small)
        _state.value = State.Transcribing(recording.durationSeconds)

        if (!asr.isLoaded()) {
            val res = asr.load()
            if (!res.success) { emitTerminal(State.Error("語音辨識準備中，請稍候再試")); return }
        }

        val rawTranscript = asr.transcribe(recording.samples)
        if (rawTranscript.isBlank()) { emitTerminal(State.Error("未偵測到語音，請重說")); return }
        val transcript = runCatching { ZhConverterUtil.toTraditional(rawTranscript) }
            .getOrDefault(rawTranscript)
        Log.i(TAG, "Transcript: \"$transcript\"")

        // 3. LLM + action loop
        runLlmLoop(transcript)
    }

    // ── LLM action loop (shared by voice and text-input paths) ────────────────

    private suspend fun runLlmLoop(transcript: String) {
        if (!llm.isLoaded()) {
            emitTerminal(State.Error("AI 模型準備中，請稍候再試"))
            return
        }

        val history     = mutableListOf<Pair<String, String>>()
        var lastJson:  String?                   = null
        var lastStats: LlmEngine.InferenceStats? = null
        var finalError: String?                  = null
        var finalReply: String?                  = null

        val completed = AutomationGuard.withGuard {
            for (step in 0 until AutomationGuard.MAX_STEPS) {
                // Re-capture screen every step; panel has FLAG_NOT_FOCUSABLE so this
                // always returns the foreground app's window, not the bubble's own tree.
                // GPU uses fewer nodes to stay within the 1024-token KV cache.
                val svc      = AssistantAccessibilityService.instance
                val maxNodes = if (_llmBackend == "GPU") ModelConfig.MAX_NODES_LLM_GPU
                               else NodeSerializer.MAX_NODES_LLM
                val nodeTree = svc?.captureNodeTreeForLlm(maxNodes)?.text ?: ""
                val prompt   = PromptBuilder.build(transcript, nodeTree, history)

                _state.value = State.Thinking(transcript)
                val rawResponse = llm.generate(prompt, onToken = {})
                lastStats       = llm.lastStats()
                Log.i(TAG, "Step ${step + 1}/${AutomationGuard.MAX_STEPS}  " +
                        "stats=$lastStats  raw=\"${rawResponse.take(120)}\"")

                val json = extractJson(rawResponse)
                if (json == null) {
                    val reply = rawResponse.trim()
                    Log.i(TAG, "No JSON in response — treating as text reply. reply=${reply.take(80)}")
                    if (reply.isNotBlank()) finalReply = reply
                    else finalError = "助理沒有回應，請重試"
                    break
                }
                lastJson = json
                // ensureActive: if cancel() was called while the LLM was generating, throw
                // CancellationException here rather than overwriting the Idle state that
                // cancel() already set.
                currentCoroutineContext().ensureActive()
                _state.value = State.Executing(json)

                when (val result = executor.execute(json)) {
                    is ActionExecutor.Result.Done    -> break
                    is ActionExecutor.Result.Blocked -> { finalError = result.message; break }
                    is ActionExecutor.Result.Failure -> {
                        finalError = result.reason; break
                    }
                    is ActionExecutor.Result.Success -> {
                        history.add(transcript to json)
                        delay(AutomationGuard.STEP_SETTLE_MS)
                    }
                }
            }
        }

        when {
            completed == null   -> emitTerminal(State.Error("操作時間過長，已自動停止，請再試一次"))
            finalError != null  -> emitTerminal(State.Error(finalError!!))
            finalReply != null  -> emitTerminal(State.Reply(finalReply!!))
            else                -> emitTerminal(State.Done(transcript, lastJson, lastStats))
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun extractJson(raw: String): String? {
        val s = raw.indexOf('{'); val e = raw.lastIndexOf('}')
        if (s < 0 || e <= s) return null
        val candidate = raw.substring(s, e + 1)
        return runCatching { JSONObject(candidate); candidate }.getOrNull()
    }

    private fun emitTerminal(s: State) {
        _state.value = s
        scope.launch {
            delay(3_500)
            if (_state.value is State.Done || _state.value is State.Error || _state.value is State.Reply)
                _state.value = State.Idle
        }
    }

    fun destroy() {
        // Cancel the active pipeline job and stop the recorder BEFORE releasing native
        // resources. asr.release() / llm.unload() must not run while a coroutine may
        // still be inside asr.transcribe() or llm.generate() on a Dispatchers.IO thread.
        cancel()
        scope.cancel()
        asr.release()
        llm.unload()
    }
}
