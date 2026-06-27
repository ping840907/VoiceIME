package com.ping.voiceim

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.net.Uri
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.github.houbb.opencc4j.util.ZhConverterUtil
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
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

    // IME runs under the bare system theme; wrap it so AppCompat/Material widgets inflate correctly.
    private val themedCtx by lazy { ContextThemeWrapper(this, R.style.Theme_VoiceAssistant) }

    private val scope    = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val asr      = lazy { Qwen3AsrEngine(this) }
    private val recorder = AudioRecorder()

    private var recordingJob: Job? = null
    private var isRecording = false
    private var pendingText = ""

    // Selection state
    private var selStart = 0
    private var selEnd    = 0

    // Anchor/focus model for arrow-key selection adjustment
    // anchor: the fixed character index set when the panel opens
    // focus:  the moving character index controlled by ◀/▶
    private var selAnchor = 0
    private var selFocus  = 0

    // Views
    private lateinit var tvTranscription: SelectableTextView
    private lateinit var tvStatus: TextView
    private lateinit var btnMic: ImageButton
    private lateinit var btnBackspace: ImageButton
    private lateinit var btnEnter: ImageButton
    private lateinit var btnClear: TextView
    private lateinit var btnCommit: TextView
    private lateinit var btnSpace: TextView
    private lateinit var btnSettings: ImageButton
    private lateinit var progressBar: ProgressBar
    private lateinit var layoutNormalControls: LinearLayout
    private lateinit var layoutCandidates: LinearLayout
    private lateinit var tvSelectedRange: TextView
    private lateinit var llCandidates: ChipGroup
    private lateinit var btnSelExpandLeft: TextView
    private lateinit var btnSelExpandRight: TextView
    private lateinit var btnCancelSelection: TextView

    private enum class State { IDLE, LOADING, RECORDING, PROCESSING }
    private var state = State.IDLE

    override fun onCreateInputView(): View {
        val view = LayoutInflater.from(themedCtx).inflate(R.layout.ime_keyboard, null)

        tvTranscription      = view.findViewById(R.id.tv_transcription)
        tvStatus             = view.findViewById(R.id.tv_status)
        btnMic               = view.findViewById(R.id.btn_mic)
        btnBackspace         = view.findViewById(R.id.btn_backspace)
        btnEnter             = view.findViewById(R.id.btn_enter)
        btnClear             = view.findViewById(R.id.btn_clear)
        btnCommit            = view.findViewById(R.id.btn_commit)
        btnSpace             = view.findViewById(R.id.btn_space)
        btnSettings          = view.findViewById(R.id.btn_settings)
        progressBar          = view.findViewById(R.id.progress_bar)
        layoutNormalControls = view.findViewById(R.id.layout_normal_controls)
        layoutCandidates     = view.findViewById(R.id.layout_candidates)
        tvSelectedRange      = view.findViewById(R.id.tv_selected_range)
        llCandidates         = view.findViewById(R.id.ll_candidates)
        btnSelExpandLeft     = view.findViewById(R.id.btn_sel_expand_left)
        btnSelExpandRight    = view.findViewById(R.id.btn_sel_expand_right)
        btnCancelSelection   = view.findViewById(R.id.btn_cancel_selection)

        btnMic.setOnClickListener { onMicClick() }
        btnBackspace.setOnClickListener { sendBackspace() }
        btnBackspace.setOnLongClickListener { clearCurrentWord(); true }
        btnEnter.setOnClickListener { sendEnter() }
        btnClear.setOnClickListener { clearPending() }
        btnCommit.setOnClickListener { commitPending() }
        btnSpace.setOnClickListener { commitText(" ") }
        btnSettings.setOnClickListener { openDictSettings() }
        btnCancelSelection.setOnClickListener { collapseSelection() }
        btnSelExpandLeft.setOnClickListener  { adjustSelection(delta = -1) }
        btnSelExpandRight.setOnClickListener { adjustSelection(delta = +1) }

        tvTranscription.onSelectionChanged = { start, end ->
            selStart = start
            selEnd   = end
            if (start < end && pendingText.isNotEmpty()) {
                if (layoutCandidates.visibility == View.VISIBLE) {
                    // Panel already open — just update highlight and label
                    updateSelectionHighlight()
                } else {
                    showCandidatePanel()
                }
            } else {
                collapseSelection()
            }
        }

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
            if (result.success) {
                val providerLabel = providerDisplayName(result.provider)
                tvStatus.text = "模型就緒（$providerLabel）"
            } else {
                Log.e(TAG, "Model load failed: ${result.error}")
                showToast("模型載入失敗: ${result.error}")
            }
        }
    }

    private fun providerDisplayName(provider: String) = when (provider) {
        "nnapi" -> "NNAPI（NPU/GPU 加速）"
        "cpu"   -> "CPU"
        else    -> provider
    }

    private fun hasMicPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

    private fun startRecording() {
        if (!hasMicPermission()) {
            showToast("請先在設定頁授予麥克風權限")
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            return
        }
        if (!asr.value.isLoaded()) { preloadModel(); return }

        isRecording = true
        collapseSelection()
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

    private fun cancelRecording() {
        recorder.stopEarly()
        recordingJob?.cancel()
        isRecording = false
        if (state == State.RECORDING) setState(State.IDLE)
    }

    private fun onTranscriptionDone(text: String) {
        if (text.isBlank()) { setState(State.IDLE); return }
        pendingText = text
        tvTranscription.text = text
        setState(State.IDLE)
    }

    // ── Selection / candidate panel ───────────────────────────────────────────

    private fun showCandidatePanel() {
        if (selStart >= selEnd || pendingText.isEmpty()) return

        val selected = pendingText.substring(
            selStart.coerceIn(0, pendingText.length),
            selEnd.coerceIn(0, pendingText.length)
        )
        if (selected.isEmpty()) return

        // Initialise anchor/focus for arrow-key adjustment
        selAnchor = selStart
        selFocus  = selEnd - 1

        // Highlight selection in preview
        val spannable = SpannableString(pendingText)
        spannable.setSpan(
            BackgroundColorSpan(0x4429B6F6),
            selStart.coerceIn(0, pendingText.length),
            selEnd.coerceIn(0, pendingText.length),
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        tvTranscription.text = spannable

        tvSelectedRange.text = "選取範圍：「$selected」"

        // Populate candidate chips from user dictionary
        llCandidates.removeAllViews()
        val dict = UserDictionary.load(this)
        if (dict.isEmpty()) {
            llCandidates.addView(makeChip("（詞典為空，請新增）", enabled = false))
        } else {
            dict.entries.sortedBy { it.key }.forEach { (_, to) ->
                makeChip(to).also { chip ->
                    chip.setOnClickListener { applyCandidate(to) }
                    llCandidates.addView(chip)
                }
            }
        }

        layoutNormalControls.visibility = View.GONE
        layoutCandidates.visibility     = View.VISIBLE
    }

    private fun adjustSelection(delta: Int) {
        val len = pendingText.length
        selFocus = (selFocus + delta).coerceIn(0, len - 1)
        // Derive selStart/selEnd from anchor and focus
        if (selFocus >= selAnchor) {
            selStart = selAnchor
            selEnd   = selFocus + 1
        } else {
            selStart = selFocus
            selEnd   = selAnchor + 1
        }
        updateSelectionHighlight()
    }

    private fun updateSelectionHighlight() {
        val s = selStart.coerceIn(0, pendingText.length)
        val e = selEnd.coerceIn(0, pendingText.length)
        val spannable = SpannableString(pendingText)
        spannable.setSpan(BackgroundColorSpan(0x4429B6F6), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        tvTranscription.text = spannable
        tvSelectedRange.text = "選取範圍：「${pendingText.substring(s, e)}」"
    }

    private fun applyCandidate(replacement: String) {
        val s = selStart.coerceIn(0, pendingText.length)
        val e = selEnd.coerceIn(0, pendingText.length)
        val original = pendingText.substring(s, e)
        val newText  = pendingText.substring(0, s) + replacement + pendingText.substring(e)
        pendingText = newText
        tvTranscription.text = newText
        collapseSelection()
        tvStatus.text = "已替換「$original」→「$replacement」"
    }

    private fun collapseSelection() {
        selStart = 0; selEnd = 0
        if (!::layoutCandidates.isInitialized) return
        layoutCandidates.visibility     = View.GONE
        layoutNormalControls.visibility = View.VISIBLE
        if (::tvTranscription.isInitialized) tvTranscription.text = pendingText
    }

    private fun makeChip(label: String, enabled: Boolean = true): Chip {
        val chip = Chip(themedCtx)
        chip.text = label
        chip.textSize = 13f
        chip.isEnabled = enabled
        chip.alpha = if (enabled) 1f else 0.5f
        chip.isClickable = enabled
        chip.isCheckable = false
        chip.chipBackgroundColor = android.content.res.ColorStateList.valueOf(
            ContextCompat.getColor(this, R.color.ime_accent)
        )
        chip.setTextColor(ContextCompat.getColor(this, R.color.ime_accent_text))
        chip.chipMinHeight = (32 * resources.displayMetrics.density)
        chip.chipStartPadding = (8 * resources.displayMetrics.density)
        chip.chipEndPadding   = (8 * resources.displayMetrics.density)
        chip.isCloseIconVisible = false
        return chip
    }

    // ── Text pipeline ─────────────────────────────────────────────────────────

    private fun postProcess(raw: String): String {
        val traditional = try {
            ZhConverterUtil.toTraditional(raw)
        } catch (ex: Exception) {
            Log.w(TAG, "OpenCC failed: ${ex.message}")
            raw
        }
        return traditional
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
        collapseSelection()
        if (::tvTranscription.isInitialized) tvTranscription.text = ""
        if (::btnCommit.isInitialized) updateUi()
    }

    private fun commitText(text: String) { currentInputConnection?.commitText(text, 1) }

    private fun sendBackspace() { currentInputConnection?.deleteSurroundingText(1, 0) }

    private fun clearCurrentWord() { currentInputConnection?.deleteSurroundingText(100, 0) }

    private fun sendEnter() {
        val ei     = currentInputEditorInfo
        val action = ei?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
        if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            currentInputConnection?.performEditorAction(action)
        } else {
            commitText("\n")
        }
    }

    private fun openDictSettings() {
        startActivity(Intent(this, DictSettingsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    private fun setState(s: State) { state = s; updateUi() }

    private fun updateUi() {
        if (!::btnMic.isInitialized) return
        when (state) {
            State.IDLE -> {
                if (pendingText.isEmpty())
                    tvStatus.text = "點擊麥克風開始語音輸入"
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

    private fun showToast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
