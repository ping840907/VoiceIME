package com.ping.voiceim

import android.inputmethodservice.InputMethodService
import android.os.Build
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.github.houbb.opencc4j.util.ZhConverterUtil
import com.ping.voiceim.engine.AudioRecorder
import com.ping.voiceim.engine.ModelConfig
import com.ping.voiceim.engine.Qwen3AsrEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VoiceImeService : InputMethodService() {

    companion object {
        private const val TAG = "VoiceImeService"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val asr   = lazy { Qwen3AsrEngine(this) }
    private val recorder = AudioRecorder()

    private var recordingJob: Job? = null
    private var isRecording = false
    private var pendingText = ""

    // Views (set in onCreateInputView)
    private lateinit var tvTranscription: TextView
    private lateinit var tvStatus: TextView
    private lateinit var btnMic: ImageButton
    private lateinit var btnBackspace: ImageButton
    private lateinit var btnEnter: ImageButton
    private lateinit var btnClear: TextView
    private lateinit var btnCommit: TextView
    private lateinit var btnSpace: TextView
    private lateinit var progressBar: ProgressBar

    private enum class State { IDLE, LOADING, RECORDING, PROCESSING }
    private var state = State.IDLE

    override fun onCreateInputView(): View {
        val view = layoutInflater.inflate(R.layout.ime_keyboard, null)

        tvTranscription = view.findViewById(R.id.tv_transcription)
        tvStatus        = view.findViewById(R.id.tv_status)
        btnMic          = view.findViewById(R.id.btn_mic)
        btnBackspace    = view.findViewById(R.id.btn_backspace)
        btnEnter        = view.findViewById(R.id.btn_enter)
        btnClear        = view.findViewById(R.id.btn_clear)
        btnCommit       = view.findViewById(R.id.btn_commit)
        btnSpace        = view.findViewById(R.id.btn_space)
        progressBar     = view.findViewById(R.id.progress_bar)

        btnMic.setOnClickListener { onMicClick() }
        btnBackspace.setOnClickListener { sendBackspace() }
        btnBackspace.setOnLongClickListener { clearCurrentWord(); true }
        btnEnter.setOnClickListener { sendEnter() }
        btnClear.setOnClickListener { clearPending() }
        btnCommit.setOnClickListener { commitPending() }
        btnSpace.setOnClickListener { commitText(" ") }

        updateUi()
        preloadModel()
        return view
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        clearPending()
    }

    override fun onFinishInput() {
        super.onFinishInput()
        stopRecording()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRecording()
        if (asr.isInitialized()) asr.value.release()
        scope.coroutineContext[Job]?.cancel()
    }

    // ── Mic logic ─────────────────────────────────────────────────────────────

    private fun onMicClick() {
        when {
            isRecording -> stopRecording()
            state == State.LOADING -> { /* wait */ }
            state == State.PROCESSING -> { /* wait */ }
            else -> startRecording()
        }
    }

    private fun preloadModel() {
        if (asr.value.isLoaded()) return
        setState(State.LOADING)
        scope.launch {
            val result = asr.value.load()
            if (result.success) {
                setState(State.IDLE)
            } else {
                Log.e(TAG, "Model load failed: ${result.error}")
                setState(State.IDLE)
                showToast("模型載入失敗: ${result.error}")
            }
        }
    }

    private fun startRecording() {
        if (!asr.value.isLoaded()) {
            preloadModel()
            return
        }
        isRecording = true
        setState(State.RECORDING)
        recordingJob = scope.launch {
            val recording = withContext(Dispatchers.IO) {
                recorder.recordUntilSilence(
                    onRmsUpdate = { rms -> updateRmsBar(rms) }
                )
            }
            isRecording = false
            if (recording.samples.isNotEmpty()) {
                setState(State.PROCESSING)
                val raw = asr.value.transcribe(recording.samples)
                val text = if (raw.isNotBlank()) convertToTraditional(raw) else ""
                onTranscriptionDone(text)
            } else {
                setState(State.IDLE)
            }
        }
    }

    private fun stopRecording() {
        recorder.stopEarly()
        recordingJob?.cancel()
        isRecording = false
        if (state == State.RECORDING) setState(State.IDLE)
    }

    private fun onTranscriptionDone(text: String) {
        if (text.isBlank()) {
            setState(State.IDLE)
            return
        }
        pendingText = text
        tvTranscription.text = text
        setState(State.IDLE)
    }

    // ── Text actions ──────────────────────────────────────────────────────────

    private fun commitPending() {
        if (pendingText.isNotEmpty()) {
            commitText(pendingText)
            clearPending()
        }
    }

    private fun clearPending() {
        pendingText = ""
        tvTranscription.text = ""
    }

    private fun commitText(text: String) {
        currentInputConnection?.commitText(text, 1)
    }

    private fun sendBackspace() {
        currentInputConnection?.deleteSurroundingText(1, 0)
    }

    private fun clearCurrentWord() {
        currentInputConnection?.deleteSurroundingText(100, 0)
    }

    private fun sendEnter() {
        val ei = currentInputEditorInfo
        val action = ei?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
        if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            currentInputConnection?.performEditorAction(action)
        } else {
            commitText("\n")
        }
    }

    // ── OpenCC ────────────────────────────────────────────────────────────────

    private fun convertToTraditional(text: String): String = try {
        ZhConverterUtil.toTraditional(text)
    } catch (ex: Exception) {
        Log.w(TAG, "OpenCC conversion failed: ${ex.message}")
        text
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    private fun setState(s: State) {
        state = s
        updateUi()
    }

    private fun updateUi() {
        if (!::btnMic.isInitialized) return
        when (state) {
            State.IDLE -> {
                tvStatus.text = if (pendingText.isNotEmpty()) "識別完成 — 點確認插入" else "點擊麥克風開始語音輸入"
                btnMic.setImageResource(R.drawable.ic_mic)
                btnMic.alpha = 1f
                progressBar.visibility = View.GONE
                btnCommit.isEnabled = pendingText.isNotEmpty()
                btnClear.isEnabled  = pendingText.isNotEmpty()
            }
            State.LOADING -> {
                tvStatus.text = "正在載入模型…"
                btnMic.alpha = 0.4f
                progressBar.visibility = View.VISIBLE
                progressBar.isIndeterminate = true
                btnCommit.isEnabled = false
            }
            State.RECORDING -> {
                tvStatus.text = "錄音中… 靜音自動停止"
                btnMic.setImageResource(R.drawable.ic_mic_active)
                btnMic.alpha = 1f
                progressBar.visibility = View.VISIBLE
                progressBar.isIndeterminate = false
                btnCommit.isEnabled = false
            }
            State.PROCESSING -> {
                tvStatus.text = "辨識中…"
                btnMic.alpha = 0.4f
                progressBar.visibility = View.VISIBLE
                progressBar.isIndeterminate = true
                btnCommit.isEnabled = false
            }
        }
    }

    private fun updateRmsBar(rms: Float) {
        if (state != State.RECORDING) return
        val level = (rms / 0.1f * 100).toInt().coerceIn(0, 100)
        progressBar.progress = level
    }

    private fun showToast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }
}
