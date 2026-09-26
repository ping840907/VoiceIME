package com.ping.voiceime

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.ping.voiceime.engine.ModelConfig
import com.ping.voiceime.engine.ModelDownloadSpec
import com.ping.voiceime.engine.ModelDownloadState
import com.ping.voiceime.engine.ModelDownloader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ImeSettingsActivity : AppCompatActivity() {

    companion object {
        private const val TOAST_MODEL_NOT_DOWNLOADED = "請先下載此模型，再選取為辨識引擎"
        private const val TOAST_DUAL_ENGINE_NEED_DOWNLOAD = "請先下載 X-ASR 與 Qwen3-ASR 兩個模型，才能開啟雙引擎模式"
        private const val TOAST_DUAL_ENGINE_NEED_QWEN3_SELECTED = "請先將辨識引擎切換為 Qwen3-ASR，才能開啟雙引擎模式"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val downloader by lazy { ModelDownloader(this) }
    private var observeJob: Job? = null
    private var pendingDownloadEngine: String? = null

    // Node 1: Mic Root
    private lateinit var tvOverallBadge: TextView
    private lateinit var tvMicStatus: TextView
    private lateinit var btnGrantMic: MaterialButton

    // Node 2: Input Modules
    private lateinit var tvKeyboardBadge: TextView
    private lateinit var tvImeEnableStatus: TextView
    private lateinit var btnEnableIme: MaterialButton
    private lateinit var tvImeSwitchStatus: TextView
    private lateinit var btnSwitchIme: MaterialButton

    private lateinit var tvBubbleBadge: TextView
    private lateinit var tvOverlayStatus: TextView
    private lateinit var btnGrantOverlay: MaterialButton
    private lateinit var tvAccessibilityStatus: TextView
    private lateinit var btnGrantAccessibility: MaterialButton
    private lateinit var tvRestrictedSettingsHelp: TextView

    // Node 3: Engines
    // X-ASR
    private lateinit var tvXasrStatus: TextView
    private lateinit var btnSelectXasr: MaterialButton
    private lateinit var btnDownloadXasr: MaterialButton
    private lateinit var progressDownloadXasr: LinearProgressIndicator
    private lateinit var tvDownloadStatusXasr: TextView

    // Qwen3-ASR
    private lateinit var tvQwen3Status: TextView
    private lateinit var btnSelectQwen3: MaterialButton
    private lateinit var btnDownloadQwen3: MaterialButton
    private lateinit var progressDownloadQwen3: LinearProgressIndicator
    private lateinit var tvDownloadStatusQwen3: TextView
    private lateinit var switchFilterPunctuation: MaterialSwitch
    private lateinit var tvVadValue: TextView
    private lateinit var sbVadSilence: SeekBar

    // Dual Engine
    private lateinit var layoutDualEngineToggle: View
    private lateinit var switchDualEngine: MaterialSwitch

    // Node 4: Vocabulary
    private lateinit var btnOpenDict: MaterialButton

    private val requestMic = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        updateAllStatus()
    }

    private val requestNotifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        pendingDownloadEngine?.let { engine ->
            beginDownload(engine)
            pendingDownloadEngine = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        bindViews()
        setupListeners()
        updateAllStatus()
    }

    private fun bindViews() {
        // Overall
        tvOverallBadge = findViewById(R.id.tv_overall_badge)

        // Node 1: Mic
        tvMicStatus = findViewById(R.id.tv_mic_status)
        btnGrantMic = findViewById(R.id.btn_grant_mic)

        // Node 2: Input Modules
        tvKeyboardBadge    = findViewById(R.id.tv_keyboard_badge)
        tvImeEnableStatus  = findViewById(R.id.tv_ime_enable_status)
        btnEnableIme       = findViewById(R.id.btn_enable_ime)
        tvImeSwitchStatus  = findViewById(R.id.tv_ime_switch_status)
        btnSwitchIme       = findViewById(R.id.btn_switch_ime)

        tvBubbleBadge             = findViewById(R.id.tv_bubble_badge)
        tvOverlayStatus           = findViewById(R.id.tv_overlay_status)
        btnGrantOverlay           = findViewById(R.id.btn_grant_overlay)
        tvAccessibilityStatus     = findViewById(R.id.tv_accessibility_status)
        btnGrantAccessibility     = findViewById(R.id.btn_grant_accessibility)
        tvRestrictedSettingsHelp  = findViewById(R.id.tv_restricted_settings_help)

        // Node 3: Engines
        // X-ASR
        tvXasrStatus           = findViewById(R.id.tv_xasr_status)
        btnSelectXasr          = findViewById(R.id.btn_select_xasr)
        btnDownloadXasr        = findViewById(R.id.btn_download_xasr)
        progressDownloadXasr   = findViewById(R.id.progress_download_xasr)
        tvDownloadStatusXasr   = findViewById(R.id.tv_download_status_xasr)

        // Qwen3-ASR
        tvQwen3Status           = findViewById(R.id.tv_qwen3_status)
        btnSelectQwen3          = findViewById(R.id.btn_select_qwen3)
        btnDownloadQwen3        = findViewById(R.id.btn_download_qwen3)
        progressDownloadQwen3   = findViewById(R.id.progress_download_qwen3)
        tvDownloadStatusQwen3   = findViewById(R.id.tv_download_status_qwen3)
        switchFilterPunctuation = findViewById(R.id.switch_filter_punctuation)
        tvVadValue              = findViewById(R.id.tv_vad_value)
        sbVadSilence            = findViewById(R.id.sb_vad_silence)

        // Dual Engine
        layoutDualEngineToggle = findViewById(R.id.layout_dual_engine_toggle)
        switchDualEngine       = findViewById(R.id.switch_dual_engine)

        // Node 4: Vocabulary
        btnOpenDict = findViewById(R.id.btn_open_dict)
    }

    private fun setupListeners() {
        // Node 1: Mic
        btnGrantMic.setOnClickListener {
            requestMic.launch(Manifest.permission.RECORD_AUDIO)
        }

        // Node 2: Keyboard
        btnEnableIme.setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }

        btnSwitchIme.setOnClickListener {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        }

        // Node 2: Bubble & §3.2.1 高亮跳轉
        btnGrantOverlay.setOnClickListener {
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            } catch (ex: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
            }
        }

        btnGrantAccessibility.setOnClickListener {
            try {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    val compName = ComponentName(packageName, VoiceAccessibilityService::class.java.name).flattenToString()
                    putExtra(":settings:fragment_args_key", compName)
                    putExtra(":settings:show_fragment_args", Bundle().apply {
                        putString(":settings:fragment_args_key", compName)
                    })
                }
                startActivity(intent)
            } catch (ex: Exception) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        // §3.2.2 備援連結與受限制設定指引
        tvRestrictedSettingsHelp.setOnClickListener {
            showRestrictedSettingsDialog()
        }

        // Node 3: Engine Select Buttons (§2.2 點擊邏輯與聯鎖防呆)
        btnSelectXasr.setOnClickListener {
            onSelectButtonClick(ModelConfig.ENGINE_X_ASR)
        }

        btnSelectQwen3.setOnClickListener {
            onSelectButtonClick(ModelConfig.ENGINE_QWEN3)
        }

        // Downloads
        btnDownloadXasr.setOnClickListener {
            handleDownloadButtonClick(ModelConfig.ENGINE_X_ASR)
        }

        btnDownloadQwen3.setOnClickListener {
            handleDownloadButtonClick(ModelConfig.ENGINE_QWEN3)
        }

        // Qwen3 settings: Punctuation filter
        switchFilterPunctuation.isChecked = ModelConfig.isFilterPunctuationEnabled(this)
        switchFilterPunctuation.setOnCheckedChangeListener { _, isChecked ->
            ModelConfig.setFilterPunctuationEnabled(this, isChecked)
        }

        // Qwen3 settings: VAD slider
        fun vadLabel(seconds: Float) = "%.1f 秒".format(seconds)
        val currentVad = ModelConfig.vadSilenceSeconds(this)
        sbVadSilence.progress = (((currentVad - ModelConfig.VAD_SILENCE_MIN) / 0.1f).toInt())
        tvVadValue.text = vadLabel(currentVad)
        sbVadSilence.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val seconds = ModelConfig.VAD_SILENCE_MIN + progress * 0.1f
                tvVadValue.text = vadLabel(seconds)
                if (fromUser) ModelConfig.setVadSilenceSeconds(this@ImeSettingsActivity, seconds)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        // Dual Engine Toggle Click Logic (§2.2)
        layoutDualEngineToggle.setOnClickListener {
            onDualEngineToggleClick()
        }

        // Vocabulary
        btnOpenDict.setOnClickListener {
            startActivity(Intent(this, DictSettingsActivity::class.java))
        }
    }

    override fun onStart() {
        super.onStart()
        observeJob = scope.launch {
            launch {
                ModelDownloadState.active.collect { active ->
                    renderDownloadStatus(active)
                }
            }
            launch {
                ModelDownloadState.results.collect { (engine, result) ->
                    val label = if (engine == ModelConfig.ENGINE_X_ASR) "X-ASR" else "Qwen3-ASR"
                    result.onSuccess {
                        Toast.makeText(this@ImeSettingsActivity, "$label 模型下載完成", Toast.LENGTH_LONG).show()
                    }.onFailure { ex ->
                        Toast.makeText(this@ImeSettingsActivity, "$label 下載失敗：${ex.message}", Toast.LENGTH_LONG).show()
                    }
                    updateAllStatus()
                }
            }
            launch {
                while (true) {
                    delay(1000)
                    ModelDownloadState.active.value?.let { renderDownloadStatus(it) }
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        observeJob?.cancel()
    }

    override fun onResume() {
        super.onResume()
        updateAllStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.coroutineContext[Job]?.cancel()
    }

    // ══════════════════════════════════════════════════════════════════════════
    // §2.2 線路生效運算式與狀態判定
    // ══════════════════════════════════════════════════════════════════════════
    private fun updateAllStatus() {
        val micGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val imeEnabled = imm.enabledInputMethodList.any { it.packageName == packageName }
        val currentIme = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        val imeDefault = currentIme != null && currentIme.contains(packageName)

        val overlayGranted = Settings.canDrawOverlays(this)
        val accessibilityEnabled = isAccessibilityServiceEnabled()

        val xAsrDownloaded = ModelConfig.isXAsrReady(this)
        val qwen3Downloaded = ModelConfig.isQwen3Ready(this)
        var selectedEngine = ModelConfig.selectedEngine(this)

        // Initial sanity check: ensure selected points to a valid ready engine if possible
        if (!xAsrDownloaded && !qwen3Downloaded) {
            // Neither downloaded
        } else if (selectedEngine == ModelConfig.ENGINE_X_ASR && !xAsrDownloaded && qwen3Downloaded) {
            selectedEngine = ModelConfig.ENGINE_QWEN3
            ModelConfig.setSelectedEngine(this, selectedEngine)
        } else if (selectedEngine == ModelConfig.ENGINE_QWEN3 && !qwen3Downloaded && xAsrDownloaded) {
            selectedEngine = ModelConfig.ENGINE_X_ASR
            ModelConfig.setSelectedEngine(this, selectedEngine)
        }

        var dualEngineToggle = ModelConfig.isDualEngineEnabled(this)

        // 1. 輸入路徑線路
        val keyboardModuleLineActive = micGranted && imeEnabled && imeDefault
        val bubbleModuleLineActive = micGranted && overlayGranted && accessibilityEnabled
        val inputPathReady = keyboardModuleLineActive || bubbleModuleLineActive

        // 自動確保懸浮泡泡服務在系統條件就緒時運行
        if (overlayGranted && accessibilityEnabled && !FloatingBubbleService.isRunning) {
            FloatingBubbleService.start(this)
        }

        // 2. 模型就緒主線路
        val xAsrReadyLineActive = xAsrDownloaded
        val qwen3ReadyLineActive = qwen3Downloaded

        // 3. 雙引擎可用條件判定與聯鎖防呆
        val dualEngineSelectable = xAsrDownloaded && qwen3Downloaded && (selectedEngine == ModelConfig.ENGINE_QWEN3)
        if (!dualEngineSelectable && dualEngineToggle) {
            dualEngineToggle = false
            ModelConfig.setDualEngineEnabled(this, false)
        }
        val dualEngineLineActive = dualEngineSelectable && dualEngineToggle

        // 4. 詞彙線路
        val currentEngineReady = (selectedEngine == ModelConfig.ENGINE_X_ASR && xAsrDownloaded) ||
                (selectedEngine == ModelConfig.ENGINE_QWEN3 && qwen3Downloaded)
        val vocabularyLineActive = inputPathReady && currentEngineReady

        // ══════════════════════════════════════════════════════════════════════
        // 更新 UI 元件狀態與文案
        // ══════════════════════════════════════════════════════════════════════

        // Node 1: Mic
        if (micGranted) {
            tvMicStatus.text = "麥克風錄音權限已就緒"
            tvMicStatus.setTextColor(ContextCompat.getColor(this, R.color.status_success))
            btnGrantMic.text = "已就緒"
        } else {
            tvMicStatus.text = "辨識語音必須的系統核心權限"
            tvMicStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            btnGrantMic.text = "授予權限"
        }

        // Node 2: Keyboard
        if (keyboardModuleLineActive) {
            tvKeyboardBadge.text = "就緒"
            tvKeyboardBadge.setTextColor(ContextCompat.getColor(this, R.color.status_success_text))
            tvKeyboardBadge.setBackgroundResource(R.drawable.bg_status_badge_success)
        } else {
            tvKeyboardBadge.text = "未就緒"
            tvKeyboardBadge.setTextColor(ContextCompat.getColor(this, R.color.status_warning_text))
            tvKeyboardBadge.setBackgroundResource(R.drawable.bg_status_badge_warning)
        }

        if (imeEnabled) {
            tvImeEnableStatus.text = "系統輸入法已開啟"
            tvImeEnableStatus.setTextColor(ContextCompat.getColor(this, R.color.status_success))
            btnEnableIme.text = "已啟用"
        } else {
            tvImeEnableStatus.text = "請在系統「虛擬鍵盤」列表中勾選"
            tvImeEnableStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            btnEnableIme.text = "前往啟用"
        }

        if (imeDefault) {
            tvImeSwitchStatus.text = "目前為預設輸入法"
            tvImeSwitchStatus.setTextColor(ContextCompat.getColor(this, R.color.status_success))
            btnSwitchIme.text = "已是預設"
        } else {
            tvImeSwitchStatus.text = "選取 VoiceIME 為目前鍵盤"
            tvImeSwitchStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            btnSwitchIme.text = "切換輸入法"
        }

        // Node 2: Bubble
        if (bubbleModuleLineActive) {
            tvBubbleBadge.text = "就緒"
            tvBubbleBadge.setTextColor(ContextCompat.getColor(this, R.color.status_success_text))
            tvBubbleBadge.setBackgroundResource(R.drawable.bg_status_badge_success)
        } else {
            tvBubbleBadge.text = "未就緒"
            tvBubbleBadge.setTextColor(ContextCompat.getColor(this, R.color.status_warning_text))
            tvBubbleBadge.setBackgroundResource(R.drawable.bg_status_badge_warning)
        }

        if (overlayGranted) {
            tvOverlayStatus.text = "懸浮視窗權限已授予"
            tvOverlayStatus.setTextColor(ContextCompat.getColor(this, R.color.status_success))
            btnGrantOverlay.text = "已授權"
        } else {
            tvOverlayStatus.text = "允許顯示在其他應用程式上層"
            tvOverlayStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            btnGrantOverlay.text = "前往授權"
        }

        if (accessibilityEnabled) {
            tvAccessibilityStatus.text = "自動貼上服務已啟用"
            tvAccessibilityStatus.setTextColor(ContextCompat.getColor(this, R.color.status_success))
            btnGrantAccessibility.text = "已開啟"
            tvRestrictedSettingsHelp.visibility = View.GONE
        } else {
            tvAccessibilityStatus.text = "將辨識文字直接填入焦點欄位"
            tvAccessibilityStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            btnGrantAccessibility.text = "前往開啟"
            tvRestrictedSettingsHelp.visibility = View.VISIBLE
        }

        // Node 3: X-ASR Card
        val isXasrSelected = selectedEngine == ModelConfig.ENGINE_X_ASR
        if (isXasrSelected) {
            btnSelectXasr.text = "使用中"
            btnSelectXasr.isEnabled = true
        } else {
            btnSelectXasr.text = "選取引擎"
            btnSelectXasr.isEnabled = true
        }

        if (xAsrDownloaded) {
            tvXasrStatus.text = "已下載就緒"
            tvXasrStatus.setTextColor(ContextCompat.getColor(this, R.color.status_success))
            btnDownloadXasr.text = "重新下載"
        } else {
            tvXasrStatus.text = "未下載 (約 138MB)"
            tvXasrStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            btnDownloadXasr.text = "下載模型"
        }

        // Node 3: Qwen3 Card
        val isQwen3Selected = selectedEngine == ModelConfig.ENGINE_QWEN3
        if (isQwen3Selected) {
            btnSelectQwen3.text = "使用中"
            btnSelectQwen3.isEnabled = true
        } else {
            btnSelectQwen3.text = "選取引擎"
            btnSelectQwen3.isEnabled = true
        }

        if (qwen3Downloaded) {
            tvQwen3Status.text = "已下載就緒"
            tvQwen3Status.setTextColor(ContextCompat.getColor(this, R.color.status_success))
            btnDownloadQwen3.text = "重新下載"
        } else {
            tvQwen3Status.text = "未下載 (約 878MB)"
            tvQwen3Status.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            btnDownloadQwen3.text = "下載模型"
        }

        // Dual Engine Toggle
        switchDualEngine.isChecked = dualEngineToggle

        // Header Overall Badge
        if (inputPathReady && currentEngineReady) {
            tvOverallBadge.text = "全部就緒"
            tvOverallBadge.setTextColor(ContextCompat.getColor(this, R.color.status_success_text))
            tvOverallBadge.setBackgroundResource(R.drawable.bg_status_badge_success)
        } else {
            tvOverallBadge.text = "需要設定"
            tvOverallBadge.setTextColor(ContextCompat.getColor(this, R.color.status_warning_text))
            tvOverallBadge.setBackgroundResource(R.drawable.bg_status_badge_warning)
        }
    }

    // §2.2 模型卡的 select_button 點擊邏輯與聯鎖防呆
    private fun onSelectButtonClick(targetEngine: String) {
        val downloaded = if (targetEngine == ModelConfig.ENGINE_X_ASR) {
            ModelConfig.isXAsrReady(this)
        } else {
            ModelConfig.isQwen3Ready(this)
        }

        if (downloaded) {
            ModelConfig.setSelectedEngine(this, targetEngine)
            // 聯鎖防呆：若切換引擎導致雙引擎條件不成立，強制關閉開關以防殘留
            val dualEngineSelectable = ModelConfig.isXAsrReady(this) &&
                    ModelConfig.isQwen3Ready(this) &&
                    (targetEngine == ModelConfig.ENGINE_QWEN3)
            if (!dualEngineSelectable) {
                ModelConfig.setDualEngineEnabled(this, false)
            }
            updateAllStatus()
        } else {
            Toast.makeText(this, TOAST_MODEL_NOT_DOWNLOADED, Toast.LENGTH_SHORT).show()
        }
    }

    // §2.2 dual_engine_toggle 點擊邏輯
    private fun onDualEngineToggleClick() {
        val xDownloaded = ModelConfig.isXAsrReady(this)
        val qDownloaded = ModelConfig.isQwen3Ready(this)
        val selected = ModelConfig.selectedEngine(this)
        val dualEngineSelectable = xDownloaded && qDownloaded && (selected == ModelConfig.ENGINE_QWEN3)

        if (dualEngineSelectable) {
            val nextState = !ModelConfig.isDualEngineEnabled(this)
            ModelConfig.setDualEngineEnabled(this, nextState)
            updateAllStatus()
        } else if (!xDownloaded || !qDownloaded) {
            Toast.makeText(this, TOAST_DUAL_ENGINE_NEED_DOWNLOAD, Toast.LENGTH_SHORT).show()
        } else if (selected != ModelConfig.ENGINE_QWEN3) {
            Toast.makeText(this, TOAST_DUAL_ENGINE_NEED_QWEN3_SELECTED, Toast.LENGTH_SHORT).show()
        }
    }

    // §3.2.2 受限制設定提示彈窗
    private fun showRestrictedSettingsDialog() {
        AlertDialog.Builder(this)
            .setTitle("啟用受限制的設定 (Android 13+)")
            .setMessage(
                "若無障礙服務開關呈現灰階鎖定，請依照以下步驟解除限制：\n\n" +
                        "1. 點擊下方按鈕前往「應用程式資訊」頁面。\n" +
                        "2. 點選畫面右上角「⋮」（三點選單）。\n" +
                        "3. 點選「允許受限制的設定」並輸入螢幕鎖定密碼解鎖。\n" +
                        "4. 完成後返回系統無障礙服務清單即可正常開啟服務。"
            )
            .setPositiveButton("前往應用程式資訊") { _, _ ->
                val intent = Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun handleDownloadButtonClick(engine: String) {
        val active = ModelDownloadState.active.value
        if (active != null && active.engine == engine) {
            ModelDownloadService.cancel(this)
            hideDownloadProgress(engine)
            updateAllStatus()
            return
        }

        val target = ModelDownloadSpec.forEngine(engine)
        val engineLabel = if (engine == ModelConfig.ENGINE_X_ASR) "X-ASR (約 138MB)" else "Qwen3-ASR (約 878MB)"
        val btn = if (engine == ModelConfig.ENGINE_X_ASR) btnDownloadXasr else btnDownloadQwen3
        btn.isEnabled = false
        btn.text = "檢查檔案大小…"

        scope.launch {
            val bytes = downloader.estimateTotalBytes(target)
            btn.isEnabled = true
            btn.text = if (ModelConfig.isModelReady(this@ImeSettingsActivity, engine)) "重新下載" else "下載模型"

            AlertDialog.Builder(this@ImeSettingsActivity)
                .setTitle("下載 $engineLabel 模型")
                .setMessage(
                    "即將下載約 ${ModelDownloader.formatBytes(bytes)} 的模型檔案，下載會在背景進行，" +
                            "關閉螢幕或切換應用不會中斷。\n\n" +
                            "語音辨識全程在裝置本機執行，僅下載步驟需使用網路。\n\n是否繼續？"
                )
                .setPositiveButton("開始下載") { _, _ ->
                    requestNotificationPermissionThenDownload(engine)
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun requestNotificationPermissionThenDownload(engine: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingDownloadEngine = engine
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            beginDownload(engine)
        }
    }

    private fun beginDownload(engine: String) {
        ModelDownloadService.start(this, engine)
    }

    private fun renderDownloadStatus(active: ModelDownloadState.Active?) {
        if (active == null) {
            hideDownloadProgress(ModelConfig.ENGINE_X_ASR)
            hideDownloadProgress(ModelConfig.ENGINE_QWEN3)
            updateAllStatus()
            return
        }

        val isXasr = active.engine == ModelConfig.ENGINE_X_ASR
        val btn = if (isXasr) btnDownloadXasr else btnDownloadQwen3
        val progressIndicator = if (isXasr) progressDownloadXasr else progressDownloadQwen3
        val tvStatus = if (isXasr) tvDownloadStatusXasr else tvDownloadStatusQwen3

        btn.isEnabled = true
        btn.text = "取消下載"
        progressIndicator.visibility = View.VISIBLE
        tvStatus.visibility = View.VISIBLE

        progressIndicator.isIndeterminate = active.progress.percent < 0
        if (active.progress.percent >= 0) {
            progressIndicator.progress = active.progress.percent
        }

        val elapsed = formatElapsed(System.currentTimeMillis() - active.startedAtMs)
        tvStatus.text = "${active.progress.label} ${if (active.progress.percent >= 0) "${active.progress.percent}%" else ""} · 已耗時 $elapsed"
    }

    private fun hideDownloadProgress(engine: String) {
        if (engine == ModelConfig.ENGINE_X_ASR) {
            progressDownloadXasr.visibility = View.GONE
            tvDownloadStatusXasr.visibility = View.GONE
            btnDownloadXasr.text = if (ModelConfig.isXAsrReady(this)) "重新下載" else "下載模型"
        } else {
            progressDownloadQwen3.visibility = View.GONE
            tvDownloadStatusQwen3.visibility = View.GONE
            btnDownloadQwen3.text = if (ModelConfig.isQwen3Ready(this)) "重新下載" else "下載模型"
        }
    }

    private fun formatElapsed(elapsedMs: Long): String {
        val totalSeconds = (elapsedMs / 1000).coerceAtLeast(0)
        val m = totalSeconds / 60
        val s = totalSeconds % 60
        return if (m > 0) "${m} 分 ${s} 秒" else "${s} 秒"
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        if (VoiceAccessibilityService.isServiceRunning()) return true
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.contains(packageName)
    }
}
