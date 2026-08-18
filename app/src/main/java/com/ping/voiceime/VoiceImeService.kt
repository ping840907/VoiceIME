package com.ping.voiceime

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.github.houbb.opencc4j.util.ZhConverterUtil
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.ping.voiceime.engine.AudioRecorder
import com.ping.voiceime.engine.ModelConfig
import com.ping.voiceime.engine.Qwen3AsrEngine
import com.ping.voiceime.engine.XAsrEngine
import com.k2fsa.sherpa.onnx.OnlineStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Recognized text is committed straight into the focused field as soon as it's ready.
 * Corrections happen in-place: the user selects text with the host field's own native
 * selection gesture (long-press + drag), which this service observes via
 * [onUpdateSelection], and the 詞彙 candidate panel edits that live selection through
 * [android.view.inputmethod.InputConnection] — there's no separate "confirm insert" step.
 */
class VoiceImeService : InputMethodService() {

    // IME runs under the bare system theme; wrap it so AppCompat/Material widgets inflate correctly.
    private val themedCtx by lazy { ContextThemeWrapper(this, R.style.Theme_VoiceIME) }

    private val scope     = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val qwen3Asr  = lazy { Qwen3AsrEngine(this) }
    private val xAsr      = lazy { XAsrEngine(this) }
    private val recorder  = AudioRecorder()

    private var recordingJob: Job? = null
    private var isRecording = false

    // Active streaming session for X-ASR
    private var activeStream: OnlineStream? = null

    // How many characters of the current not-yet-finalized X-ASR live preview are
    // sitting in the host field (see showLiveText). 0 when nothing is pending.
    private var composedLength = 0

    // Repeat-delete for backspace long-press
    private val repeatDeleteHandler = Handler(Looper.getMainLooper())
    private val repeatDeleteRunnable: Runnable = object : Runnable {
        override fun run() {
            sendBackspace()
            repeatDeleteHandler.postDelayed(this, REPEAT_DELETE_INTERVAL_MS)
        }
    }

    companion object {
        private const val TAG = "VoiceImeService"
        private const val REPEAT_DELETE_DELAY_MS   = 400L
        private const val REPEAT_DELETE_INTERVAL_MS = 50L
        private const val REPEAT_MOVE_DELAY_MS      = 400L
        private const val REPEAT_MOVE_INTERVAL_MS   = 80L
    }

    // Last-known selection in the host field, kept in sync via onUpdateSelection.
    private var selStart = 0
    private var selEnd   = 0

    // Candidate panel state
    private var isCursorMode = false
    private var cursorPos    = 0
    private var isShiftOn    = false
    private var shiftAnchor  = 0

    // Fuzzy correction suggestions reference the text/position of the last commit.
    private var lastCommittedText  = ""
    private var lastCommittedStart = 0

    // Views
    private lateinit var tvStatus: TextView
    private lateinit var btnMic: ImageButton
    private lateinit var btnBackspace: TextView
    private lateinit var btnEnter: TextView
    private lateinit var btnSpace: TextView
    private lateinit var btnDictInsert: TextView
    private lateinit var btnSettings: ImageButton
    private lateinit var progressBar: ProgressBar
    private lateinit var layoutMicRow: LinearLayout
    private lateinit var layoutCandidateTop: LinearLayout
    private lateinit var tvSelectedRange: TextView
    private lateinit var llCandidates: ChipGroup
    private lateinit var btnShift: TextView
    private lateinit var btnSelExpandLeft: TextView
    private lateinit var btnSelExpandRight: TextView
    private lateinit var btnCancelSelection: TextView
    private lateinit var scrollSuggestions: ScrollView
    private lateinit var llSuggestions: ChipGroup

    // Repeat-move for selection expand buttons (long-press)
    private val repeatMoveHandler = Handler(Looper.getMainLooper())
    private var repeatMoveDelta = 0
    private val repeatMoveRunnable: Runnable = object : Runnable {
        override fun run() {
            adjustSelection(repeatMoveDelta)
            repeatMoveHandler.postDelayed(this, REPEAT_MOVE_INTERVAL_MS)
        }
    }

    private enum class State { IDLE, LOADING, RECORDING, PROCESSING }
    private var state = State.IDLE

    override fun onCreateInputView(): View {
        val view = LayoutInflater.from(themedCtx).inflate(R.layout.ime_keyboard, null)

        tvStatus             = view.findViewById(R.id.tv_status)
        btnMic               = view.findViewById(R.id.btn_mic)
        btnBackspace         = view.findViewById(R.id.btn_backspace)
        btnEnter             = view.findViewById(R.id.btn_enter)
        btnSpace             = view.findViewById(R.id.btn_space)
        btnDictInsert        = view.findViewById(R.id.btn_dict_insert)
        btnSettings          = view.findViewById(R.id.btn_settings)
        progressBar          = view.findViewById(R.id.progress_bar)
        layoutMicRow         = view.findViewById(R.id.layout_mic_row)
        layoutCandidateTop   = view.findViewById(R.id.layout_candidate_top)
        tvSelectedRange      = view.findViewById(R.id.tv_selected_range)
        llCandidates         = view.findViewById(R.id.ll_candidates)
        btnShift             = view.findViewById(R.id.btn_shift)
        btnSelExpandLeft     = view.findViewById(R.id.btn_sel_expand_left)
        btnSelExpandRight    = view.findViewById(R.id.btn_sel_expand_right)
        btnCancelSelection   = view.findViewById(R.id.btn_cancel_selection)
        scrollSuggestions    = view.findViewById(R.id.scroll_suggestions)
        llSuggestions        = view.findViewById(R.id.ll_suggestions)

        btnMic.setOnClickListener { onMicClick() }
        btnBackspace.setOnClickListener { sendBackspace() }
        btnBackspace.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    repeatDeleteHandler.postDelayed(repeatDeleteRunnable, REPEAT_DELETE_DELAY_MS)
                    false // let onClick fire normally
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    repeatDeleteHandler.removeCallbacks(repeatDeleteRunnable)
                    false
                }
                else -> false
            }
        }
        btnEnter.setOnClickListener { sendEnter() }
        btnSpace.setOnClickListener { commitText(" ") }
        btnDictInsert.setOnClickListener { showCandidatePanel() }
        btnSettings.setOnClickListener { openMainSettings() }
        btnSettings.setOnLongClickListener {
            val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showInputMethodPicker()
            true
        }
        btnShift.setOnClickListener { toggleShift() }
        btnCancelSelection.setOnClickListener {
            if (isShiftOn) {
                // Cancel selection: cursor returns to the anchor position
                cursorPos = shiftAnchor
                setShift(false)
                currentInputConnection?.setSelection(cursorPos, cursorPos)
                updatePanelLabel()
            } else {
                closeCandidatePanel()
            }
        }
        btnSelExpandLeft.setOnClickListener  { adjustSelection(delta = -1) }
        btnSelExpandRight.setOnClickListener { adjustSelection(delta = +1) }
        attachRepeatMove(btnSelExpandLeft,  delta = -1)
        attachRepeatMove(btnSelExpandRight, delta = +1)

        updateUi()
        preloadModel()
        return view
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        closeCandidatePanel()
        hideSuggestions()
    }

    override fun onFinishInput() {
        super.onFinishInput()
        cancelRecording()
        closeCandidatePanel()
        hideSuggestions()
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int,
        newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        selStart = newSelStart
        selEnd   = newSelEnd
        if (!isShiftOn) cursorPos = newSelEnd

        if (!::layoutCandidateTop.isInitialized) return
        if (layoutCandidateTop.visibility == View.VISIBLE) {
            updatePanelLabel()
        } else if (!isRecording && state == State.IDLE && newSelStart < newSelEnd) {
            // User selected text natively (long-press + drag in the host field) — offer
            // the dictionary candidates for it. Silent no-op if the dictionary is empty,
            // since this can fire for an unrelated copy/paste selection.
            showCandidatePanel(showEmptyDictMessage = false)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelRecording()
        if (qwen3Asr.isInitialized()) qwen3Asr.value.release()
        if (xAsr.isInitialized()) xAsr.value.release()
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
        val engine = ModelConfig.selectedEngine(this)
        if (engine == ModelConfig.ENGINE_X_ASR) {
            if (xAsr.value.isLoaded()) return
            setState(State.LOADING)
            scope.launch {
                val result = xAsr.value.load()
                setState(State.IDLE)
                if (!result.success) {
                    Log.e(TAG, "X-ASR load failed: ${result.error}")
                    showToast("X-ASR 模型載入失敗: ${result.error}")
                }
            }
        } else {
            if (qwen3Asr.value.isLoaded() && !qwen3NeedsReload()) return
            setState(State.LOADING)
            scope.launch {
                val result = qwen3Asr.value.load(buildHotwords())
                setState(State.IDLE)
                if (!result.success) {
                    Log.e(TAG, "Model load failed: ${result.error}")
                    showToast("模型載入失敗: ${result.error}")
                }
            }
        }
    }

    /** True if the loaded Qwen3 recognizer no longer matches the current hotwords selection. */
    private fun qwen3NeedsReload(): Boolean =
        buildHotwords() != qwen3Asr.value.loadedHotwords

    /** Hardware provider label for whichever engine is currently selected, or "" if not loaded. */
    private fun currentAcceleratorLabel(): String {
        val engine = ModelConfig.selectedEngine(this)
        val provider = if (engine == ModelConfig.ENGINE_X_ASR) {
            if (xAsr.isInitialized() && xAsr.value.isLoaded()) xAsr.value.activeProvider else null
        } else {
            if (qwen3Asr.isInitialized() && qwen3Asr.value.isLoaded()) qwen3Asr.value.activeProvider else null
        }
        return provider?.let { providerDisplayName(it) } ?: ""
    }

    // NNAPI routes to whichever accelerator the device vendor exposes (NPU and/or GPU);
    // there's no reliable way to tell which one it actually picked, so describe both.
    private fun providerDisplayName(provider: String) = when (provider) {
        "nnapi" -> "NPU/GPU"
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
        val engine = ModelConfig.selectedEngine(this)
        if (engine == ModelConfig.ENGINE_X_ASR) {
            if (!xAsr.value.isLoaded()) { preloadModel(); return }
            startStreamingRecording()
        } else {
            // (Re)load if not loaded yet, or if hotwords no longer match — a no-op check
            // inside load() itself makes this cheap when nothing changed.
            if (!qwen3Asr.value.isLoaded() || qwen3NeedsReload()) {
                setState(State.LOADING)
                scope.launch {
                    val result = qwen3Asr.value.load(buildHotwords())
                    if (result.success) {
                        startOfflineRecording()
                    } else {
                        setState(State.IDLE)
                        showToast("模型載入失敗: ${result.error}")
                    }
                }
                return
            }
            startOfflineRecording()
        }
    }

    private fun startOfflineRecording() {
        isRecording = true
        closeCandidatePanel()
        hideSuggestions()
        setState(State.RECORDING)

        recordingJob = scope.launch {
            val recording = withContext(Dispatchers.IO) {
                recorder.recordUntilSilence(
                    silenceSeconds = ModelConfig.vadSilenceSeconds(this@VoiceImeService),
                    onRmsUpdate = { rms -> updateRmsBar(rms) }
                )
            }
            isRecording = false
            if (recording.samples.isNotEmpty()) {
                setState(State.PROCESSING)
                val raw  = qwen3Asr.value.transcribe(recording.samples)
                val text = if (raw.isNotBlank()) postProcess(raw) else ""
                onTranscriptionDone(text)
            } else {
                setState(State.IDLE)
                showToast("未偵測到語音，請再試一次")
            }
        }
    }

    private fun startStreamingRecording() {
        val engine = xAsr.value
        val stream = engine.createStream() ?: run {
            showToast("無法建立 X-ASR 串流")
            return
        }
        activeStream = stream

        isRecording = true
        closeCandidatePanel()
        hideSuggestions()
        setState(State.RECORDING)

        composedLength  = 0

        // engine.reset(stream) clears the recognizer's internal decode state (and thus
        // getResult()) whenever a natural pause is detected — but recording itself keeps
        // going until the user manually stops or the max duration is hit. Without tracking
        // what was already transcribed before each reset, any pause mid-recording silently
        // discards everything said before it. Accumulate finalized segments locally so both
        // the live preview and the final commit include everything.
        var accumulated = ""
        var lastShown   = ""

        recordingJob = scope.launch {
            withContext(Dispatchers.IO) {
                recorder.recordStreaming(
                    onChunk = { chunk ->
                        engine.acceptWaveform(stream, chunk)
                        while (engine.isReady(stream)) {
                            engine.decode(stream)
                        }
                        val partial  = engine.getResult(stream)
                        val combined = accumulated + partial
                        if (combined.isNotBlank() && combined != lastShown) {
                            lastShown = combined
                            Handler(Looper.getMainLooper()).post { showLiveText(combined) }
                        }
                        if (engine.isEndpoint(stream)) {
                            if (partial.isNotBlank()) accumulated += partial
                            engine.reset(stream)
                        }
                    },
                    onRmsUpdate = { rms -> updateRmsBar(rms) }
                )
            }

            isRecording = false
            activeStream = null

            // Signal end of audio then drain all remaining frames
            runCatching { engine.inputFinished(stream) }
            runCatching {
                while (engine.isReady(stream)) {
                    engine.decode(stream)
                }
            }
            val finalSegment = engine.getResult(stream).trim()
            runCatching { stream.release() }

            onTranscriptionDone((accumulated + finalSegment).trim())
        }
    }

    /**
     * Shows live-revising text in the host field WITHOUT relying on composing-region
     * support (setComposingText): some EditText implementations (custom views, certain
     * cross-platform frameworks, etc.) don't render/update composing spans reliably, which
     * made X-ASR's streaming preview silently show nothing in practice. Delete-then-commit
     * only uses the two most basic, universally-supported InputConnection primitives.
     * [composedLength] tracks how many characters of the previous preview are currently
     * sitting in the field so they can be removed before the revised text is inserted.
     */
    private fun showLiveText(text: String) {
        val ic = currentInputConnection ?: return
        if (composedLength > 0) ic.deleteSurroundingText(composedLength, 0)
        ic.commitText(text, 1)
        composedLength = text.length
    }

    private fun cancelRecording() {
        recorder.stopEarly()
        recordingJob?.cancel()
        activeStream?.let { runCatching { it.release() } }
        activeStream = null
        if (composedLength > 0) {
            currentInputConnection?.deleteSurroundingText(composedLength, 0)
            composedLength = 0
        }
        isRecording = false
        if (state == State.RECORDING) setState(State.IDLE)
    }

    private fun onTranscriptionDone(text: String) {
        val ic = currentInputConnection
        if (composedLength > 0) {
            ic?.deleteSurroundingText(composedLength, 0)
            composedLength = 0
        }
        if (text.isBlank()) {
            setState(State.IDLE)
            showToast("未偵測到語音，請再試一次")
            return
        }
        val insertStart = minOf(selStart, selEnd)
        ic?.commitText(text, 1)
        setState(State.IDLE)
        showSuggestions(text, insertStart)
    }

    // ── Selection / candidate panel ───────────────────────────────────────────

    /**
     * Opens the candidate panel against whatever is currently selected in the host field
     * (replace mode) or, if there's no selection, at the current cursor (insert mode).
     * [showEmptyDictMessage] is suppressed for the passive auto-trigger from
     * [onUpdateSelection] so an unrelated copy/paste selection doesn't nag the user.
     */
    private fun showCandidatePanel(showEmptyDictMessage: Boolean = true) {
        if (UserDictionary.load(this).isEmpty()) {
            if (showEmptyDictMessage) tvStatus.text = "詞彙庫為空，請先至設定新增詞彙"
            return
        }
        if (!isShiftOn) {
            // Only re-derive anchor/cursor from the host selection when this isn't already
            // an in-progress Shift-gesture — otherwise this clobbers our own anchor every
            // time ◀/▶ triggers onUpdateSelection, breaking contiguous selection extension.
            if (selStart < selEnd) {
                shiftAnchor  = selStart
                cursorPos    = selEnd
                isCursorMode = true
                setShift(true)
            } else {
                cursorPos    = selEnd
                isCursorMode = true
                setShift(false)
            }
        }
        populateCandidateChips()
        updatePanelLabel()
        layoutMicRow.visibility       = View.GONE
        layoutCandidateTop.visibility = View.VISIBLE
        btnDictInsert.visibility      = View.GONE
        btnCancelSelection.visibility = View.VISIBLE
        scrollSuggestions.visibility  = View.GONE
    }

    private fun closeCandidatePanel() {
        isCursorMode = false
        setShift(false)
        if (!::layoutCandidateTop.isInitialized) return
        layoutCandidateTop.visibility = View.GONE
        layoutMicRow.visibility       = View.VISIBLE
        btnDictInsert.visibility      = View.VISIBLE
        btnCancelSelection.visibility = View.GONE
    }

    private fun populateCandidateChips() {
        llCandidates.removeAllViews()
        val dict = UserDictionary.load(this)
        if (dict.isEmpty()) {
            llCandidates.addView(makeChip("（詞典為空，請新增）", enabled = false))
        } else {
            val usage = DictUsage.allCounts(this)
            dict.entries
                .sortedWith(compareByDescending<Map.Entry<String, String>> { usage[it.key] ?: 0 }
                    .thenBy { it.key })
                .forEach { (from, to) ->
                    makeChip(to).also { chip ->
                        chip.setOnClickListener { applyCandidate(from, to) }
                        llCandidates.addView(chip)
                    }
                }
        }
    }

    /** Long-press on ◀/▶ repeatedly moves the cursor/selection edge every [REPEAT_MOVE_INTERVAL_MS]. */
    private fun attachRepeatMove(view: View, delta: Int) {
        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    repeatMoveDelta = delta
                    repeatMoveHandler.postDelayed(repeatMoveRunnable, REPEAT_MOVE_DELAY_MS)
                    false // let onClick still fire for a single tap
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    repeatMoveHandler.removeCallbacks(repeatMoveRunnable)
                    false
                }
                else -> false
            }
        }
    }

    private fun adjustSelection(delta: Int) {
        cursorPos = (cursorPos + delta).coerceAtLeast(0)
        val ic = currentInputConnection
        if (isShiftOn) {
            ic?.setSelection(minOf(shiftAnchor, cursorPos), maxOf(shiftAnchor, cursorPos))
        } else {
            ic?.setSelection(cursorPos, cursorPos)
        }
        updatePanelLabel()
    }

    private fun toggleShift() {
        if (!isShiftOn) {
            // Turn ON: anchor stays at current cursor, ◀/▶ will extend selection
            shiftAnchor = cursorPos
            setShift(true)
        } else {
            // Turn OFF: cursor returns to anchor, back to pure cursor mode
            cursorPos = shiftAnchor
            setShift(false)
            currentInputConnection?.setSelection(cursorPos, cursorPos)
        }
        updatePanelLabel()
    }

    private fun setShift(on: Boolean) {
        isShiftOn = on
        if (::btnShift.isInitialized) {
            btnShift.setBackgroundResource(if (on) R.drawable.commit_bg else R.drawable.key_bg)
            btnShift.setTextColor(
                ContextCompat.getColor(this, if (on) R.color.ime_accent_text else R.color.ime_key_text)
            )
        }
    }

    /** Reads the host field's current selection/cursor context via InputConnection to label the panel. */
    private fun updatePanelLabel() {
        if (!::tvSelectedRange.isInitialized) return
        val ic = currentInputConnection ?: return
        if (isShiftOn && shiftAnchor != cursorPos) {
            val selected = ic.getSelectedText(0)?.toString().orEmpty()
            tvSelectedRange.text = if (selected.isEmpty()) "⇧ 移動方向鍵開始選取"
                                   else "選取範圍：「$selected」"
        } else {
            val before = ic.getTextBeforeCursor(1, 0)?.toString().orEmpty()
            val after  = ic.getTextAfterCursor(1, 0)?.toString().orEmpty()
            tvSelectedRange.text = "插入位置：「${before}│${after}」"
        }
    }

    private fun applyCandidate(from: String, replacement: String) {
        DictUsage.recordUse(this, from)
        val insertPos = minOf(selStart, selEnd)
        currentInputConnection?.commitText(replacement, 1)
        cursorPos = insertPos + replacement.length
        setShift(false)
        populateCandidateChips()
        updatePanelLabel()
        tvStatus.text = "已套用「$replacement」"
    }

    /** Near-miss ASR correction suggestions for the text just committed by [onTranscriptionDone]. */
    private fun showSuggestions(text: String, start: Int) {
        lastCommittedText  = text
        lastCommittedStart = start
        if (!::scrollSuggestions.isInitialized) return
        val suggestions = UserDictionary.findFuzzySuggestions(text, UserDictionary.load(this)).take(5)
        if (suggestions.isEmpty()) {
            scrollSuggestions.visibility = View.GONE
            return
        }
        llSuggestions.removeAllViews()
        suggestions.forEach { s ->
            makeChip("${s.matchedText}→${s.replacement}").also { chip ->
                chip.setOnClickListener { applySuggestion(s) }
                llSuggestions.addView(chip)
            }
        }
        scrollSuggestions.visibility = View.VISIBLE
    }

    private fun applySuggestion(s: UserDictionary.Suggestion) {
        val idx = lastCommittedText.indexOf(s.matchedText)
        val ic  = currentInputConnection
        if (idx < 0 || ic == null) { hideSuggestions(); return }

        val absStart = lastCommittedStart + idx
        val absEnd   = absStart + s.matchedText.length
        ic.setSelection(absStart, absEnd)
        // Guard against staleness: only apply if the field still actually contains what we expect there
        // (the user may have edited elsewhere since this suggestion was computed).
        if (ic.getSelectedText(0)?.toString() != s.matchedText) {
            showToast("內容已變更，請改用長按選取後套用詞彙")
            hideSuggestions()
            return
        }
        ic.commitText(s.replacement, 1)
        DictUsage.recordUse(this, s.from)
        tvStatus.text = "已修正「${s.matchedText}」→「${s.replacement}」"
        hideSuggestions()
    }

    private fun hideSuggestions() {
        if (::scrollSuggestions.isInitialized) scrollSuggestions.visibility = View.GONE
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

    /** Build a newline-separated hotwords string from the current UserDictionary. */
    private fun buildHotwords(): String =
        UserDictionary.load(this).values.distinct().sorted().joinToString("\n")

    private fun postProcess(raw: String): String {
        if (ModelConfig.selectedEngine(this) == ModelConfig.ENGINE_X_ASR) return raw
        return try {
            ZhConverterUtil.toTraditional(raw)
        } catch (ex: Exception) {
            Log.w(TAG, "OpenCC failed: ${ex.message}")
            raw
        }
    }

    // ── Text actions ──────────────────────────────────────────────────────────

    private fun commitText(text: String) { currentInputConnection?.commitText(text, 1) }

    private fun sendBackspace() {
        val ic = currentInputConnection ?: return
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty() || selStart != selEnd) {
            val insertPos = minOf(selStart, selEnd)
            ic.commitText("", 1)
            selStart = insertPos
            selEnd = insertPos
            cursorPos = insertPos
            shiftAnchor = insertPos
            setShift(false)
            if (::layoutCandidateTop.isInitialized && layoutCandidateTop.visibility == View.VISIBLE) {
                updatePanelLabel()
            }
        } else {
            ic.deleteSurroundingText(1, 0)
        }
    }

    private fun sendEnter() {
        val ei     = currentInputEditorInfo
        val action = ei?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
        if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            currentInputConnection?.performEditorAction(action)
        } else {
            commitText("\n")
        }
    }

    private fun openMainSettings() {
        startActivity(Intent(this, ImeSettingsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    private fun setState(s: State) { state = s; updateUi() }

    private fun updateUi() {
        if (!::btnMic.isInitialized) return
        when (state) {
            State.IDLE -> {
                tvStatus.text = currentAcceleratorLabel().let {
                    if (it.isNotEmpty()) "點擊麥克風開始語音輸入（$it）" else "點擊麥克風開始語音輸入"
                }
                btnMic.setImageResource(R.drawable.ic_mic)
                btnMic.alpha = 1f
                progressBar.visibility = View.GONE
            }
            State.LOADING -> {
                tvStatus.text = "正在載入模型…"
                btnMic.alpha = 0.4f
                progressBar.visibility = View.VISIBLE
                progressBar.isIndeterminate = true
            }
            State.RECORDING -> {
                tvStatus.text = "錄音中… 再次點擊提早停止"
                btnMic.setImageResource(R.drawable.ic_mic_active)
                btnMic.alpha = 1f
                progressBar.visibility = View.VISIBLE
                progressBar.isIndeterminate = false
            }
            State.PROCESSING -> {
                tvStatus.text = "辨識中…"
                btnMic.alpha = 0.4f
                progressBar.visibility = View.VISIBLE
                progressBar.isIndeterminate = true
            }
        }
    }

    private fun updateRmsBar(rms: Float) {
        if (state != State.RECORDING) return
        progressBar.progress = (rms / 0.1f * 100).toInt().coerceIn(0, 100)
    }

    private fun showToast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
