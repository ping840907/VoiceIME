package com.ping.elderlyassistant.pipeline

import android.content.Context
import android.util.Log
import com.ping.elderlyassistant.AssistantAccessibilityService
import com.ping.elderlyassistant.engine.AudioRecorder
import com.ping.elderlyassistant.engine.LlmEngine
import com.ping.elderlyassistant.engine.MlcLlmEngine
import com.ping.elderlyassistant.engine.SenseVoiceEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Central coordinator for the voice-to-action pipeline.
 *
 * ┌──────────────┐   ┌──────────────────┐   ┌───────────────────┐   ┌─────────────────────┐
 * │ AudioRecorder│──>│ SenseVoice ASR   │──>│  Qwen3-1.7B LLM   │──>│ AccessibilityService│
 * │ (16 kHz mono)│   │ (sherpa-onnx AAR)│   │ (mlc4j / OpenCL)  │   │ (click / type / …)  │
 * └──────────────┘   └──────────────────┘   └───────────────────┘   └─────────────────────┘
 *
 * Multi-step loop (Phase 3):
 *   After transcription, the orchestrator enters an action loop:
 *   capture screen → LLM generates JSON → ActionExecutor runs it → repeat until
 *   ActionExecutor.Result.Done, Blocked, Failure, or AutomationGuard.MAX_STEPS is reached.
 */
class PipelineOrchestrator(private val context: Context) {

    companion object {
        private const val TAG = "Pipeline"
    }

    // ── Engines ───────────────────────────────────────────────────────────────
    private val asr      by lazy { SenseVoiceEngine(context) }
    private val llm      by lazy { MlcLlmEngine(context) }
    private val recorder = AudioRecorder()
    private val executor = ActionExecutor(context)

    // ── Observable state ──────────────────────────────────────────────────────
    sealed class State {
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
        data class Error(val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pipelineJob: Job? = null

    // ── Pre-warming ───────────────────────────────────────────────────────────

    /** Call once from FloatingBubbleService.onCreate() to warm engines in background. */
    fun preloadModels() {
        scope.launch {
            val asrResult = asr.load()
            if (asrResult.success) Log.i(TAG, "SenseVoice loaded ✓")
            else Log.w(TAG, "SenseVoice unavailable: ${asrResult.error}")

            if (MlcLlmEngine.isMlcAvailable) {
                val llmResult = llm.load()
                if (llmResult.success) Log.i(TAG, "Qwen3 loaded ✓  stats=${llm.lastStats()}")
                else Log.w(TAG, "Qwen3 unavailable: ${llmResult.error}")
            }
        }
    }

    // ── Pipeline control ──────────────────────────────────────────────────────

    fun startListening() {
        if (_state.value !is State.Idle) {
            Log.w(TAG, "Pipeline busy — ignoring startListening()")
            return
        }
        pipelineJob = scope.launch { runPipeline() }
    }

    fun stopRecordingEarly() = recorder.stopEarly()

    /** Abort any active pipeline step and return to Idle. */
    fun cancel() {
        pipelineJob?.cancel()
        recorder.stopEarly()
        _state.value = State.Idle
    }

    // ── Pipeline implementation ───────────────────────────────────────────────

    private suspend fun runPipeline() {

        // ── 1. Record ─────────────────────────────────────────────────────────
        _state.value = State.Recording
        val recording = recorder.recordUntilSilence()

        if (recording.samples.isEmpty() || recording.stopReason == AudioRecorder.StopReason.ERROR) {
            emitTerminal(State.Error("錄音失敗，請重試")); return
        }

        // ── 2. Transcribe (SenseVoice-Small) ─────────────────────────────────
        _state.value = State.Transcribing(recording.durationSeconds)

        if (!asr.isLoaded()) {
            val res = asr.load()
            if (!res.success) { emitTerminal(State.Error("語音辨識未就緒：${res.error}")); return }
        }

        val transcript = asr.transcribe(recording.samples)
        if (transcript.isBlank()) { emitTerminal(State.Error("未偵測到語音，請重說")); return }
        Log.i(TAG, "Transcript: \"$transcript\"")

        // ── 3. LLM + action multi-step loop (Qwen3-1.7B) ─────────────────────
        if (!llm.isLoaded()) {
            emitTerminal(State.Done(transcript = transcript, actionJson = null, llmStats = null))
            return
        }

        val history     = mutableListOf<Pair<String, String>>()
        var lastJson:  String?                   = null
        var lastStats: LlmEngine.InferenceStats? = null
        var finalError: String?                  = null

        val completed = AutomationGuard.withGuard {
            for (step in 0 until AutomationGuard.MAX_STEPS) {
                // Re-capture screen on every step so the LLM sees updated state
                val svc      = AssistantAccessibilityService.instance
                val nodeTree = svc?.captureNodeTreeForLlm()?.text ?: ""
                val prompt   = PromptBuilder.build(transcript, nodeTree, history)

                _state.value = State.Thinking(transcript)
                val rawResponse = llm.generate(prompt, onToken = {})
                lastStats       = llm.lastStats()
                Log.i(TAG, "Step ${step + 1}/${AutomationGuard.MAX_STEPS}  " +
                        "stats=$lastStats  raw=\"${rawResponse.take(120)}\"")

                val json = extractJson(rawResponse)
                if (json == null) {
                    finalError = "模型輸出格式錯誤，請重試"; break
                }
                lastJson         = json
                _state.value     = State.Executing(json)

                when (val result = executor.execute(json)) {
                    is ActionExecutor.Result.Done    -> break
                    is ActionExecutor.Result.Blocked -> { finalError = result.message; break }
                    is ActionExecutor.Result.Failure -> {
                        finalError = "執行失敗：${result.reason}"; break
                    }
                    is ActionExecutor.Result.Success -> {
                        history.add(transcript to json)
                        delay(AutomationGuard.STEP_SETTLE_MS)
                    }
                }
            }
        }

        // ── 4. Emit final state ───────────────────────────────────────────────
        when {
            completed == null  -> emitTerminal(State.Error("操作逾時，已自動停止"))
            finalError != null -> emitTerminal(State.Error(finalError!!))
            else               -> emitTerminal(State.Done(transcript, lastJson, lastStats))
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun extractJson(raw: String): String? {
        val s = raw.indexOf('{'); val e = raw.lastIndexOf('}')
        if (s < 0 || e <= s) return null
        val candidate = raw.substring(s, e + 1)
        return runCatching { JSONObject(candidate); candidate }.getOrNull()
    }

    /**
     * Set terminal state (Done / Error) and schedule an auto-reset to Idle
     * after 3.5 s so the UI does not stay stuck on a result screen.
     */
    private fun emitTerminal(s: State) {
        _state.value = s
        scope.launch {
            delay(3_500)
            if (_state.value is State.Done || _state.value is State.Error)
                _state.value = State.Idle
        }
    }

    fun destroy() {
        recorder.stopEarly()
        asr.release()
        llm.unload()
        scope.cancel()
    }
}
