package com.ping.voiceim

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.github.houbb.opencc4j.util.ZhConverterUtil
import com.ping.voiceim.engine.AudioRecorder
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

    private val scope    = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val asr      = lazy { Qwen3AsrEngine(this) }
    private val recorder = AudioRecorder()

    private var recordingJob: Job? = null
    private var isRecording = false
    private var pendingText = ""

    private lateinit var tvTranscription: TextView
    private lateinit var tvStatus: TextView
    private lateinit var btnMic: ImageButton
    private lateinit var btnBackspace: ImageButton
    private lateinit var btnEnter: ImageButton
    private lateinit var btnClear: TextView
    private lateinit var btnCommit: TextView
    private lateinit var btnSpace: TextView
    private lateinit var btnSettings: ImageButton
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
        btnSettings     = view.findViewById(R.id.btn_settings)
        progressBar     = view.findViewById(R.id.progress_bar)

        btnMic.setOnClickListener { onMicClick() }
        btnBackspace.setOnClickListener { sendBackspace() }
        btnBackspace.setOnLongClickListener { clearCurrentWord(); true }
        btnEnter.setOnClickListener { sendEnter() }
        btnClear.setOnClickListener { clearPending() }
        btnCommit.setOnClickListener { commitPending() }
        btnSpace.setOnClickListener { commitText(" ") }
        btnSettings.setOnClickListener { openDictSettings() }

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
        cancelRecording()
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelRecording()
        if (asr.isInitialized()) asr.value.release()
        scope.coroutineContext[Job]?.cancel()
    }

    // ── Mic logic ─────────────────────────────────────────────────────────────

    private fun onMicClick() {
        when {
            isRecording -> {
                // Signal early stop — coroutine continues and still transcribes
                recorder.stopEarly()
                tvStatus.text = "提早停止，辨識中…"
            }
            state == State.LOADING    -> { /* wait */ }
            state == State.PROCESSING -> { /* wait */ }
            else -> startRecording()
        }
    }

    private fun preloadModel() {
        if (asr.value.isLoaded()) return
        setState(State.LOADING)
        scope.launch {
            val result = asr.value.load()
            setState(State.IDLE)
            if (!result.success) {
                Log.e(TAG, "Model load failed: ${result.error}")
                showToast("模型載入失敗: ${result.error}")
            }
        }
    }

    private fun hasMicPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

    private fun startRecording() {
        if (!hasMicPermission()) {
            showToast("請先在「語音輸入法」設定頁授予麥克風權限")
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            return
        }
        if (!asr.value.isLoaded()) {
            preloadModel()
            return
        }
        isRecording = true
        setState(State.RECORDING)
        recordingJob = scope.launch {
            val recording = withContext(Dispatchers.IO) {
                recorder.recordUntilSilence(onRmsUpdate = { rms -> updateRmsBar(rms) })
            }
            isRecording = false
            if (recording.samples.isNotEmpty()) {
                setState(State.PROCESSING)
                val raw  = asr.value.transcribe(recording.samples)
                val text = if (raw.isNotBlank()) postProcess(raw) else ""
                onTranscriptionDone(text)
            } else {
                setState(State.IDLE)
            }
        }
    }

    /** Hard cancel: used when IME is dismissed or destroyed. */
    private fun cancelRecording() {
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

    // ── Text pipeline ─────────────────────────────────────────────────────────

    /** OpenCC → user dictionary */
    private fun postProcess(raw: String): String {
        val traditional = try {
            ZhConverterUtil.toTraditional(raw)
        } catch (ex: Exception) {
            Log.w(TAG, "OpenCC failed: ${ex.message}")
            raw
        }
        val dict = UserDictionary.load(this)
        return UserDictionary.apply(traditional, dict)
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
        if (::tvTranscription.isInitialized) tvTranscription.text = ""
        if (::btnCommit.isInitialized) updateUi()
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

    private fun openDictSettings() {
        val intent = Intent(this, DictSettingsActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
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
                btnClear.isEnabled  = false
            }
            State.RECORDING -> {
                tvStatus.text = "錄音中… 再次點擊提早停止"
                btnMic.setImageResource(R.drawable.ic_mic_active)
                btnMic.alpha = 1f
                progressBar.visibility = View.VISIBLE
                progressBar.isIndeterminate = false
                btnCommit.isEnabled = false
                btnClear.isEnabled  = false
            }
            State.PROCESSING -> {
                tvStatus.text = "辨識中…"
                btnMic.alpha = 0.4f
                progressBar.visibility = View.VISIBLE
                progressBar.isIndeterminate = true
                btnCommit.isEnabled = false
                btnClear.isEnabled  = false
            }
        }
    }

    private fun updateRmsBar(rms: Float) {
        if (state != State.RECORDING) return
        progressBar.progress = (rms / 0.1f * 100).toInt().coerceIn(0, 100)
    }

    private fun showToast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }
}
