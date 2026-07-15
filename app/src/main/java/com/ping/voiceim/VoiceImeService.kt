package com.ping.voiceim

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
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
import com.ping.voiceim.engine.AudioRecorder
import com.ping.voiceim.engine.ModelConfig
import com.ping.voiceim.engine.Qwen3AsrEngine
import com.ping.voiceim.engine.XAsrEngine
import com.k2fsa.sherpa.onnx.OnlineStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VoiceImeService : InputMethodService() {

    // IME runs under the bare system theme; wrap it so AppCompat/Material widgets inflate correctly.
    private val themedCtx by lazy { ContextThemeWrapper(this, R.style.Theme_VoiceAssistant) }

    private val scope     = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val qwen3Asr  = lazy { Qwen3AsrEngine(this) }
    private val xAsr      = lazy { XAsrEngine(this) }
    private val recorder  = AudioRecorder()

    private var recordingJob: Job? = null
    private var isRecording = false
    private var pendingText = ""

    // Active streaming session for X-ASR
    private var activeStream: OnlineStream? = null

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

    // Selection state
    private var selStart = 0
    private var selEnd    = 0

    // Cursor insertion mode: candidate panel is open, cursor is positioned
    private var isCursorMode = false
    private var cursorPos    = 0

    // Shift mode: anchor is fixed, cursorPos is the moving focus for selection
    // shiftAnchor = position where Shift was enabled (or where long-press drag started)
    private var isShiftOn   = false
    private var shiftAnchor = 0

    // Views
    private lateinit var tvTranscription: SelectableTextView
    private lateinit var tvStatus: TextView
    private lateinit var btnMic: ImageButton
    private lateinit var btnBackspace: TextView
    private lateinit var btnEnter: TextView
    private lateinit var btnSpace: TextView
    private lateinit var btnDictInsert: TextView
    private lateinit var btnSettings: ImageButton
    private lateinit var progressBar: ProgressBar
    private lateinit var layoutNormalControls: LinearLayout
    private lateinit var layoutCandidates: LinearLayout
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

        tvTranscription      = view.findViewById(R.id.tv_transcription)
        tvStatus             = view.findViewById(R.id.tv_status)
        btnMic               = view.findViewById(R.id.btn_mic)
        btnBackspace         = view.findViewById(R.id.btn_backspace)
        btnEnter             = view.findViewById(R.id.btn_enter)
        btnSpace             = view.findViewById(R.id.btn_space)
        btnDictInsert        = view.findViewById(R.id.btn_dict_insert)
        btnSettings          = view.findViewById(R.id.btn_settings)
        progressBar          = view.findViewById(R.id.progress_bar)
        layoutNormalControls = view.findViewById(R.id.layout_normal_controls)
        layoutCandidates     = view.findViewById(R.id.layout_candidates)
        tvSelectedRange      = view.findViewById(R.id.tv_selected_range)
        llCandidates         = view.findViewById(R.id.ll_candidates)
        btnShift             = view.findViewById(R.id.btn_shift)
        btnSelExpandLeft     = view.findViewById(R.id.btn_sel_expand_left)
        btnSelExpandRight    = view.findViewById(R.id.btn_sel_expand_right)
        btnCancelSelection   = view.findViewById(R.id.btn_cancel_selection)
        scrollSuggestions    = view.findViewById(R.id.scroll_suggestions)
        llSuggestions        = view.findViewById(R.id.ll_suggestions)

        btnMic.setOnClickListener { onMicClick() }
        btnBackspace.setOnClickListener { onBackspaceClick() }
        btnBackspace.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (pendingText.isEmpty()) {
                        repeatDeleteHandler.postDelayed(repeatDeleteRunnable, REPEAT_DELETE_DELAY_MS)
                    }
                    false // let onClick fire normally
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    repeatDeleteHandler.removeCallbacks(repeatDeleteRunnable)
                    false
                }
                else -> false
            }
        }
        btnEnter.setOnClickListener { onEnterClick() }
        btnSpace.setOnClickListener { commitText(" ") }
        btnDictInsert.setOnClickListener { showDictPanel() }
        btnSettings.setOnClickListener { openMainSettings() }
        btnShift.setOnClickListener { toggleShift() }
        btnCancelSelection.setOnClickListener {
            if (isShiftOn) {
                // Cancel selection: cursor returns to the anchor position
                cursorPos    = shiftAnchor
                isCursorMode = true
                setShift(false)
                updateCursorHighlight()
            } else {
                collapseSelection()
            }
        }
        btnSelExpandLeft.setOnClickListener  { adjustSelection(delta = -1) }
        btnSelExpandRight.setOnClickListener { adjustSelection(delta = +1) }
        attachRepeatMove(btnSelExpandLeft,  delta = -1)
        attachRepeatMove(btnSelExpandRight, delta = +1)

        tvTranscription.onSingleTap = { offset ->
            if (pendingText.isNotEmpty()) {
                cursorPos = offset.coerceIn(0, pendingText.length)
                showCursorPanel()
            }
        }

        tvTranscription.onSelectionChanged = { start, end ->
            selStart = start
            selEnd   = end
            if (start < end && pendingText.isNotEmpty()) {
                if (layoutCandidates.visibility == View.VISIBLE) {
                    // New drag while panel is open — update shift selection
                    shiftAnchor  = start
                    cursorPos    = end
                    isCursorMode = true
                    setShift(true)
                    updateShiftHighlight()
                } else {
                    showCandidatePanel()
                }
            } else if (layoutCandidates.visibility != View.VISIBLE) {
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

    /** True if the loaded Qwen3 recognizer no longer matches the current hotwords/model-size selection. */
    private fun qwen3NeedsReload(): Boolean =
        buildHotwords() != qwen3Asr.value.loadedHotwords ||
            qwen3Asr.value.loadedModelSize != ModelConfig.qwen3ModelSize(this)

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
            // (Re)load if not loaded yet, or if hotwords/model size no longer match — a
            // no-op check inside load() itself makes this cheap when nothing changed.
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
        collapseSelection()
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
        collapseSelection()
        setState(State.RECORDING)

        recordingJob = scope.launch {
            withContext(Dispatchers.IO) {
                recorder.recordStreaming(
                    onChunk = { chunk ->
                        engine.acceptWaveform(stream, chunk)
                        while (engine.isReady(stream)) {
                            engine.decode(stream)
                        }
                        val partial = engine.getResult(stream)
                        if (partial.isNotBlank()) {
                            Handler(Looper.getMainLooper()).post {
                                pendingText = partial
                                tvTranscription.displayCursorAt = -1
                                tvTranscription.text = partial
                            }
                        }
                        if (engine.isEndpoint(stream)) {
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
            val finalText = engine.getResult(stream).trim()
            runCatching { stream.release() }

            onTranscriptionDone(finalText)
        }
    }

    private fun cancelRecording() {
        recorder.stopEarly()
        recordingJob?.cancel()
        activeStream?.let { runCatching { it.release() } }
        activeStream = null
        isRecording = false
        if (state == State.RECORDING) setState(State.IDLE)
    }

    private fun onTranscriptionDone(text: String) {
        if (text.isBlank()) {
            setState(State.IDLE)
            showToast("未偵測到語音，請再試一次")
            return
        }
        pendingText = text
        tvTranscription.displayCursorAt = -1
        tvTranscription.text = text
        setState(State.IDLE)
        updateSuggestions()
    }

    // ── Selection / candidate panel ───────────────────────────────────────────

    private fun showCandidatePanel() {
        if (selStart >= selEnd || pendingText.isEmpty()) return
        val s = selStart.coerceIn(0, pendingText.length)
        val e = selEnd.coerceIn(0, pendingText.length)
        if (s >= e) return

        // Long-press drag → anchor at left end, cursor at right end, Shift ON
        shiftAnchor  = s
        cursorPos    = e
        isCursorMode = true
        setShift(true)

        tvTranscription.cancelDrag()
        updateShiftHighlight()
        populateCandidateChips()
        btnShift.visibility         = View.VISIBLE
        btnSelExpandLeft.visibility  = View.VISIBLE
        btnSelExpandRight.visibility = View.VISIBLE
        layoutNormalControls.visibility = View.GONE
        layoutCandidates.visibility     = View.VISIBLE
        scrollSuggestions.visibility    = View.GONE
    }

    private fun showCursorPanel() {
        isCursorMode = true
        setShift(false)
        cursorPos = cursorPos.coerceIn(0, pendingText.length)
        tvTranscription.cancelDrag()
        updateCursorHighlight()
        populateCandidateChips()
        btnShift.visibility         = View.VISIBLE
        btnSelExpandLeft.visibility  = View.VISIBLE
        btnSelExpandRight.visibility = View.VISIBLE
        layoutNormalControls.visibility = View.GONE
        layoutCandidates.visibility     = View.VISIBLE
        scrollSuggestions.visibility    = View.GONE
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
        cursorPos = (cursorPos + delta).coerceIn(0, pendingText.length)
        if (isShiftOn) updateShiftHighlight() else updateCursorHighlight()
    }

    private fun toggleShift() {
        if (!isShiftOn) {
            // Turn ON: anchor stays at current cursor, ◀/▶ will extend selection
            shiftAnchor = cursorPos
            setShift(true)
            updateShiftHighlight()
        } else {
            // Turn OFF: cursor returns to anchor, back to pure cursor mode
            cursorPos    = shiftAnchor
            isCursorMode = true
            setShift(false)
            updateCursorHighlight()
        }
    }

    private fun setShift(on: Boolean) {
        isShiftOn = on
        if (::btnShift.isInitialized) {
            btnShift.setBackgroundResource(if (on) R.drawable.commit_bg else R.drawable.key_bg)
            btnShift.setTextColor(
                ContextCompat.getColor(this, if (on) R.color.ime_accent_text else R.color.ime_key_text)
            )
            btnCancelSelection.text = if (on) "取消選取" else "關閉"
        }
    }

    private fun updateShiftHighlight() {
        val cp = cursorPos.coerceIn(0, pendingText.length)
        val an = shiftAnchor.coerceIn(0, pendingText.length)
        val s  = minOf(an, cp)
        val e  = maxOf(an, cp)

        // Always insert | at cursor position (same technique as cursor mode)
        val withCursor = pendingText.substring(0, cp) + "|" + pendingText.substring(cp)
        val spannable  = SpannableString(withCursor)

        // | accent colour
        spannable.setSpan(
            ForegroundColorSpan(ContextCompat.getColor(this, R.color.ime_accent)),
            cp, cp + 1,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        // Selection background — index-adjusted for the inserted |
        if (s < e) {
            val adjS: Int; val adjE: Int
            if (cp <= an) {
                // cursor at left or same as anchor: |[selection]
                // pendingText[cp..an-1] → new string[(cp+1)..(an)]
                adjS = cp + 1
                adjE = an + 1
            } else {
                // cursor at right of anchor: [selection]|
                // pendingText[an..cp-1] → new string[an..cp-1] (all before the |)
                adjS = an
                adjE = cp
            }
            if (adjS < adjE) {
                spannable.setSpan(
                    BackgroundColorSpan(0x4429B6F6),
                    adjS, adjE,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }

        tvTranscription.displayCursorAt = cp
        tvTranscription.text = spannable
        val selected = if (s < e) pendingText.substring(s, e) else ""
        tvSelectedRange.text = if (selected.isEmpty()) "⇧ 移動方向鍵開始選取"
                               else "選取範圍：「$selected」"
    }

    private fun updateCursorHighlight() {
        val pos = cursorPos.coerceIn(0, pendingText.length)
        val withCursor = pendingText.substring(0, pos) + "|" + pendingText.substring(pos)
        val spannable  = SpannableString(withCursor)
        spannable.setSpan(
            ForegroundColorSpan(ContextCompat.getColor(this, R.color.ime_accent)),
            pos, pos + 1,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        tvTranscription.displayCursorAt = pos
        tvTranscription.text = spannable

        val before = if (pos > 0) pendingText[pos - 1].toString() else ""
        val after  = if (pos < pendingText.length) pendingText[pos].toString() else ""
        tvSelectedRange.text = "插入位置：「${before}│${after}」"
    }

    private fun applyCandidate(from: String, replacement: String) {
        DictUsage.recordUse(this, from)
        if (pendingText.isEmpty()) {
            // No pending text (dict key with empty transcription): commit directly
            commitText(replacement)
            collapseSelection()
            return
        }
        if (isShiftOn && shiftAnchor != cursorPos) {
            // Replace shift-selected range
            val s = minOf(shiftAnchor, cursorPos).coerceIn(0, pendingText.length)
            val e = maxOf(shiftAnchor, cursorPos).coerceIn(0, pendingText.length)
            val original = pendingText.substring(s, e)
            pendingText  = pendingText.substring(0, s) + replacement + pendingText.substring(e)
            cursorPos    = s + replacement.length
            shiftAnchor  = cursorPos
            isCursorMode = true
            setShift(false)
            updateCursorHighlight()
            populateCandidateChips()
            updateSuggestions()
            tvStatus.text = "已替換「$original」→「$replacement」"
        } else {
            // Cursor mode: insert at cursor position
            val pos = cursorPos.coerceIn(0, pendingText.length)
            pendingText  = pendingText.substring(0, pos) + replacement + pendingText.substring(pos)
            cursorPos    = pos + replacement.length
            isCursorMode = true
            setShift(false)
            updateCursorHighlight()
            populateCandidateChips()
            updateSuggestions()
            tvStatus.text = "已插入「$replacement」"
        }
    }

    private fun collapseSelection() {
        isCursorMode = false
        setShift(false)
        selStart = 0; selEnd = 0
        if (!::layoutCandidates.isInitialized) return
        btnShift.visibility         = View.VISIBLE
        btnSelExpandLeft.visibility  = View.VISIBLE
        btnSelExpandRight.visibility = View.VISIBLE
        layoutCandidates.visibility     = View.GONE
        layoutNormalControls.visibility = View.VISIBLE
        if (::tvTranscription.isInitialized) {
            tvTranscription.displayCursorAt = -1
            tvTranscription.text = pendingText
        }
        updateSuggestions()
    }

    /** Show near-miss ASR correction suggestions as tappable chips below the preview. */
    private fun updateSuggestions() {
        if (!::scrollSuggestions.isInitialized) return
        if (pendingText.isEmpty() || isRecording || layoutCandidates.visibility == View.VISIBLE) {
            scrollSuggestions.visibility = View.GONE
            return
        }
        val suggestions = UserDictionary.findFuzzySuggestions(pendingText, UserDictionary.load(this)).take(5)
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
        val idx = pendingText.indexOf(s.matchedText)
        if (idx < 0) { updateSuggestions(); return }
        pendingText = pendingText.substring(0, idx) + s.replacement + pendingText.substring(idx + s.matchedText.length)
        tvTranscription.displayCursorAt = -1
        tvTranscription.text = pendingText
        DictUsage.recordUse(this, s.from)
        tvStatus.text = "已修正「${s.matchedText}」→「${s.replacement}」"
        updateSuggestions()
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

    private fun commitPending() {
        if (pendingText.isNotEmpty()) {
            commitText(pendingText)
            clearPending()
        }
    }

    private fun clearPending() {
        pendingText = ""
        collapseSelection()
        if (::tvTranscription.isInitialized) {
            tvTranscription.displayCursorAt = -1
            tvTranscription.text = ""
        }
        if (::btnMic.isInitialized) updateUi()
    }

    private fun commitText(text: String) { currentInputConnection?.commitText(text, 1) }

    private fun sendBackspace() { currentInputConnection?.deleteSurroundingText(1, 0) }



    private fun sendEnter() {
        val ei     = currentInputEditorInfo
        val action = ei?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
        if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            currentInputConnection?.performEditorAction(action)
        } else {
            commitText("\n")
        }
    }

    private fun showDictPanel() {
        if (UserDictionary.load(this).isEmpty()) {
            tvStatus.text = "詞彙庫為空，請先至設定新增詞彙"
            return
        }
        // Position cursor at end of any pending text, then open the shared cursor panel
        cursorPos = pendingText.length
        showCursorPanel()
    }

    private fun openMainSettings() {
        startActivity(Intent(this, ImeSettingsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // ── Text actions (dynamic backspace / enter) ──────────────────────────────

    private fun onBackspaceClick() {
        if (pendingText.isNotEmpty()) clearPending() else sendBackspace()
    }

    private fun onEnterClick() {
        if (pendingText.isNotEmpty()) commitPending() else sendEnter()
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    private fun setState(s: State) { state = s; updateUi() }

    private fun updateUi() {
        if (!::btnMic.isInitialized) return
        val hasPending = pendingText.isNotEmpty()
        // Dynamic labels for backspace / enter
        btnBackspace.text = if (hasPending) "取消" else "⌫"
        btnBackspace.textSize = if (hasPending) 14f else 26f
        btnEnter.text     = if (hasPending) "確認插入" else "↵"
        btnEnter.textSize = if (hasPending) 13f else 26f
        btnEnter.setBackgroundResource(if (hasPending) R.drawable.commit_bg else R.drawable.key_bg)
        btnEnter.setTextColor(
            ContextCompat.getColor(this, if (hasPending) R.color.ime_accent_text else R.color.ime_key_text)
        )

        when (state) {
            State.IDLE -> {
                tvStatus.text = when {
                    hasPending -> ""
                    else -> currentAcceleratorLabel().let {
                        if (it.isNotEmpty()) "點擊麥克風開始語音輸入（$it）" else "點擊麥克風開始語音輸入"
                    }
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
