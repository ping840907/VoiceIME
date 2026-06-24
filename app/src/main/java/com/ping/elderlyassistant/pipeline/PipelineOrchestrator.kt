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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Central coordinator for the voice assistant pipeline.
 *
 * ┌──────────────┐   ┌──────────────┐   ┌───────────────────┐   ┌─────────────────────┐
 * │ AudioRecorder│──>│SenseVoice ASR│──>│  Qwen3-1.7B LLM   │──>│ AccessibilityService│
 * │ (16kHz mono) │   │ (sherpa-onnx)│   │ (mlc4j / OpenCL)  │   │ (click / type)      │
 * └──────────────┘   └──────────────┘   └───────────────────┘   └─────────────────────┘
 *
 * Phase 2: Steps 1-2 (record → transcribe) fully functional.
 *          Step 3 (LLM) loads when mlc4j AAR + Qwen3-1.7B weights are present.
 *          Step 4 (execute) — stubs present, fully wired in Phase 3.
 */
class PipelineOrchestrator(private val context: Context) {

    companion object {
        private const val TAG = "Pipeline"

        // ── Qwen3 system prompt ───────────────────────────────────────────────
        // `/no_think` disables Qwen3's chain-of-thought mode → raw JSON, faster.
        // Few-shot examples added in Phase 3 once real node-tree formats are known.
        private val SYSTEM_PROMPT = """
            /no_think
            你是一位專為長輩設計的手機操作助理。
            根據使用者的語音指令和畫面節點資訊，輸出一個 JSON 動作物件。
            只輸出 JSON，不要任何說明或 Markdown 包裝。

            支援的動作格式（擇一輸出）：
            {"action":"click","id":"<viewIdResourceName>"}
            {"action":"type","id":"<viewIdResourceName>","text":"<input text>"}
            {"action":"scroll","direction":"up|down"}
            {"action":"back"}
            {"action":"home"}
            {"action":"unknown","reason":"<中文說明無法執行的原因>"}
        """.trimIndent()
    }

    // ── Engines ───────────────────────────────────────────────────────────────
    private val asr      by lazy { SenseVoiceEngine(context) }
    private val llm      by lazy { MlcLlmEngine(context) }
    private val recorder = AudioRecorder()

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

    private val scope = CoroutineScope(Dispatchers.Main + Job())

    // ── Pre-warming ───────────────────────────────────────────────────────────

    /** Call once from FloatingBubbleService.onCreate() to warm engines in background. */
    fun preloadModels() {
        scope.launch {
            // ASR
            val asrResult = asr.load()
            if (asrResult.success) Log.i(TAG, "SenseVoice loaded ✓")
            else Log.w(TAG, "SenseVoice unavailable: ${asrResult.error}")

            // LLM (only if mlc4j is on the classpath)
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
        scope.launch { runPipeline() }
    }

    fun stopRecordingEarly() = recorder.stopEarly()

    // ── Pipeline implementation ───────────────────────────────────────────────

    private suspend fun runPipeline() {

        // ── 1. Record ─────────────────────────────────────────────────────────
        _state.value = State.Recording
        val recording = recorder.recordUntilSilence()

        if (recording.samples.isEmpty() || recording.stopReason == AudioRecorder.StopReason.ERROR) {
            emit(State.Error("錄音失敗，請重試")); return
        }

        // ── 2. Transcribe (SenseVoice) ────────────────────────────────────────
        _state.value = State.Transcribing(recording.durationSeconds)

        if (!asr.isLoaded()) {
            val res = asr.load()
            if (!res.success) { emit(State.Error("語音辨識未就緒：${res.error}")); return }
        }

        val transcript = asr.transcribe(recording.samples)
        if (transcript.isBlank()) { emit(State.Error("未偵測到語音，請重說")); return }
        Log.i(TAG, "Transcript: \"$transcript\"")

        // ── 3. LLM inference (Qwen3-1.7B) ────────────────────────────────────
        if (!llm.isLoaded()) {
            // Phase 2: show transcript without LLM action
            emit(State.Done(transcript = transcript, actionJson = null, llmStats = null))
            return
        }

        _state.value = State.Thinking(transcript)
        val nodeTree = AssistantAccessibilityService.instance?.captureNodeTree()
        val prompt   = buildPrompt(transcript, nodeTree?.text)

        val fullResponse = llm.generate(prompt, onToken = { /* streaming UI can hook here */ })
        val stats        = llm.lastStats()
        Log.i(TAG, "LLM: $stats")

        // ── 4. Execute action ─────────────────────────────────────────────────
        val json = extractJson(fullResponse)
        if (json != null) {
            _state.value = State.Executing(json)
            executeAction(json)
        }

        emit(State.Done(transcript = transcript, actionJson = json, llmStats = stats))
    }

    // ── Qwen3 ChatML prompt ───────────────────────────────────────────────────

    private fun buildPrompt(instruction: String, nodeTree: String?): String {
        val nodes = if (!nodeTree.isNullOrBlank())
            "## 當前畫面節點\n$nodeTree"
        else
            "## 當前畫面節點\n(無法取得 — 請確認無障礙服務已啟用)"

        return buildString {
            append("<|im_start|>system\n")
            append(SYSTEM_PROMPT)
            append("\n<|im_end|>\n")
            append("<|im_start|>user\n")
            append("## 使用者指令\n$instruction\n\n")
            append(nodes)
            append("\n<|im_end|>\n")
            append("<|im_start|>assistant\n")
        }
    }

    // ── Action execution ──────────────────────────────────────────────────────

    private fun executeAction(json: String) {
        val svc = AssistantAccessibilityService.instance ?: run {
            Log.w(TAG, "AccessibilityService not connected — cannot execute action")
            return
        }
        try {
            val obj = JSONObject(json)
            when (val action = obj.optString("action")) {
                "click" -> {
                    val id = obj.getString("id")
                    val ok = svc.performClickById(id)
                    Log.i(TAG, "click id=$id ok=$ok")
                }
                "type" -> {
                    val id   = obj.getString("id")
                    val text = obj.getString("text")
                    svc.performTypeById(id, text)
                    Log.i(TAG, "type id=$id text=$text")
                }
                "back"   -> svc.performGlobalAction(
                    android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                "home"   -> svc.performGlobalAction(
                    android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
                "scroll" -> Log.d(TAG, "scroll reserved for Phase 3")
                "unknown"-> Log.w(TAG, "LLM unknown: ${obj.optString("reason")}")
                else     -> Log.w(TAG, "Unrecognised action: $action")
            }
        } catch (ex: Exception) {
            Log.e(TAG, "executeAction error: ${ex.message}")
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun extractJson(raw: String): String? {
        val s = raw.indexOf('{'); val e = raw.lastIndexOf('}')
        if (s < 0 || e <= s) return null
        val candidate = raw.substring(s, e + 1)
        return runCatching { JSONObject(candidate); candidate }.getOrNull()
    }

    private fun emit(s: State) {
        _state.value = s
        scope.launch {
            kotlinx.coroutines.delay(3_500)
            if (_state.value is State.Done || _state.value is State.Error)
                _state.value = State.Idle
        }
    }

    fun destroy() {
        recorder.stopEarly()
        asr.release()
        llm.unload()
    }
}
