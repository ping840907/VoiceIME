package com.ping.voiceime

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.ping.voiceime.engine.ModelConfig
import com.ping.voiceime.engine.ModelDownloadState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class OnboardingActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var observeJob: Job? = null
    private var pendingDownloadEngine: String? = null

    private var currentStep = 1
    private var selectedMode = ModelConfig.MODE_BUBBLE

    // Top & Navigation Views
    private lateinit var btnSkip: MaterialButton
    private lateinit var progressSteps: LinearProgressIndicator
    private lateinit var scrollContent: View
    private lateinit var btnPrev: MaterialButton
    private lateinit var tvStepLabel: TextView
    private lateinit var btnNext: MaterialButton

    // Step Containers
    private lateinit var step1Container: LinearLayout
    private lateinit var step2Container: LinearLayout
    private lateinit var step3Container: LinearLayout
    private lateinit var step4Container: LinearLayout
    private lateinit var step5Container: LinearLayout

    // Step 1 Views
    private lateinit var tvMicStatus: TextView
    private lateinit var btnGrantMic: MaterialButton

    // Step 2 Views
    private lateinit var cardModeBubble: MaterialCardView
    private lateinit var ivModeBubbleCheck: ImageView
    private lateinit var cardModeKeyboard: MaterialCardView
    private lateinit var ivModeKeyboardCheck: ImageView
    private lateinit var cardModeBoth: MaterialCardView
    private lateinit var ivModeBothCheck: ImageView

    // Step 3 Views
    private lateinit var layoutStep3KeyboardGroup: LinearLayout
    private lateinit var tvImeEnableStatus: TextView
    private lateinit var btnEnableIme: MaterialButton
    private lateinit var tvImeSwitchStatus: TextView
    private lateinit var btnSwitchIme: MaterialButton

    private lateinit var layoutStep3BubbleGroup: LinearLayout
    private lateinit var tvOverlayStatus: TextView
    private lateinit var btnGrantOverlay: MaterialButton
    private lateinit var tvAccessibilityStatus: TextView
    private lateinit var btnGrantAccessibility: MaterialButton
    private lateinit var btnOpenAppDetails: MaterialButton

    // Step 4 Views
    private lateinit var tvXasrBadge: TextView
    private lateinit var progressXasr: LinearProgressIndicator
    private lateinit var tvStatusXasr: TextView
    private lateinit var btnDownloadXasr: MaterialButton

    private lateinit var tvQwen3Badge: TextView
    private lateinit var progressQwen3: LinearProgressIndicator
    private lateinit var tvStatusQwen3: TextView
    private lateinit var btnDownloadQwen3: MaterialButton

    // Step 5 Views
    private lateinit var layoutQwen3Preferences: LinearLayout
    private lateinit var cardXasrOnlyNotice: MaterialCardView
    private lateinit var btnVadQuick: MaterialButton
    private lateinit var btnVadNormal: MaterialButton
    private lateinit var btnVadRelaxed: MaterialButton
    private lateinit var switchPunctuation: MaterialSwitch
    private lateinit var etTest: EditText
    private lateinit var btnFinish: MaterialButton

    // Observers
    private val imeSettingsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            super.onChange(selfChange)
            updateStep3Status()
        }
    }

    private val requestMic = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        updateStep1Status()
    }

    private val requestNotifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        pendingDownloadEngine?.let { engine ->
            ModelDownloadService.start(this, engine)
            pendingDownloadEngine = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)

        contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.DEFAULT_INPUT_METHOD),
            false,
            imeSettingsObserver
        )
        contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.ENABLED_INPUT_METHODS),
            false,
            imeSettingsObserver
        )

        bindViews()
        setupListeners()
        updateModeSelection(ModelConfig.getOnboardingMode(this))
        renderStep(1)
        startObservingDownloads()
    }

    override fun onDestroy() {
        super.onDestroy()
        contentResolver.unregisterContentObserver(imeSettingsObserver)
        observeJob?.cancel()
    }

    override fun onResume() {
        super.onResume()
        updateAllStatus()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            updateAllStatus()
        }
    }

    private fun bindViews() {
        btnSkip       = findViewById(R.id.btn_onboarding_skip)
        progressSteps = findViewById(R.id.progress_onboarding_steps)
        scrollContent = findViewById(R.id.scroll_onboarding_content)
        btnPrev       = findViewById(R.id.btn_onboarding_prev)
        tvStepLabel   = findViewById(R.id.tv_onboarding_step_label)
        btnNext       = findViewById(R.id.btn_onboarding_next)

        step1Container = findViewById(R.id.step_1_container)
        step2Container = findViewById(R.id.step_2_container)
        step3Container = findViewById(R.id.step_3_container)
        step4Container = findViewById(R.id.step_4_container)
        step5Container = findViewById(R.id.step_5_container)

        // Step 1
        tvMicStatus = findViewById(R.id.tv_onboarding_mic_status)
        btnGrantMic = findViewById(R.id.btn_onboarding_grant_mic)

        // Step 2
        cardModeBubble      = findViewById(R.id.card_mode_bubble)
        ivModeBubbleCheck   = findViewById(R.id.iv_mode_bubble_check)
        cardModeKeyboard    = findViewById(R.id.card_mode_keyboard)
        ivModeKeyboardCheck = findViewById(R.id.iv_mode_keyboard_check)
        cardModeBoth        = findViewById(R.id.card_mode_both)
        ivModeBothCheck     = findViewById(R.id.iv_mode_both_check)

        // Step 3
        layoutStep3KeyboardGroup = findViewById(R.id.layout_step3_keyboard_group)
        tvImeEnableStatus        = findViewById(R.id.tv_onboarding_ime_enable_status)
        btnEnableIme             = findViewById(R.id.btn_onboarding_enable_ime)
        tvImeSwitchStatus        = findViewById(R.id.tv_onboarding_ime_switch_status)
        btnSwitchIme             = findViewById(R.id.btn_onboarding_switch_ime)

        layoutStep3BubbleGroup   = findViewById(R.id.layout_step3_bubble_group)
        tvOverlayStatus          = findViewById(R.id.tv_onboarding_overlay_status)
        btnGrantOverlay          = findViewById(R.id.btn_onboarding_grant_overlay)
        tvAccessibilityStatus    = findViewById(R.id.tv_onboarding_accessibility_status)
        btnGrantAccessibility    = findViewById(R.id.btn_onboarding_grant_accessibility)
        btnOpenAppDetails        = findViewById(R.id.btn_onboarding_open_app_details)

        // Step 4
        tvXasrBadge      = findViewById(R.id.tv_onboarding_xasr_badge)
        progressXasr     = findViewById(R.id.progress_onboarding_xasr)
        tvStatusXasr     = findViewById(R.id.tv_onboarding_status_xasr)
        btnDownloadXasr  = findViewById(R.id.btn_onboarding_download_xasr)

        tvQwen3Badge     = findViewById(R.id.tv_onboarding_qwen3_badge)
        progressQwen3    = findViewById(R.id.progress_onboarding_qwen3)
        tvStatusQwen3    = findViewById(R.id.tv_onboarding_status_qwen3)
        btnDownloadQwen3 = findViewById(R.id.btn_onboarding_download_qwen3)

        // Step 5
        layoutQwen3Preferences = findViewById(R.id.layout_qwen3_preferences)
        cardXasrOnlyNotice     = findViewById(R.id.card_xasr_only_notice)
        btnVadQuick            = findViewById(R.id.btn_vad_quick)
        btnVadNormal           = findViewById(R.id.btn_vad_normal)
        btnVadRelaxed          = findViewById(R.id.btn_vad_relaxed)
        switchPunctuation      = findViewById(R.id.switch_onboarding_punctuation)
        etTest                 = findViewById(R.id.et_onboarding_test)
        btnFinish              = findViewById(R.id.btn_onboarding_finish)
    }

    private fun setupListeners() {
        // Top & Bottom Navigation
        btnSkip.setOnClickListener {
            completeOnboarding()
        }

        btnPrev.setOnClickListener {
            if (currentStep > 1) {
                renderStep(currentStep - 1)
            }
        }

        btnNext.setOnClickListener {
            if (currentStep < 5) {
                if (currentStep == 1 && !hasMicPermission()) {
                    Toast.makeText(this, "請先授予麥克風權限以利後續功能使用", Toast.LENGTH_SHORT).show()
                    requestMic.launch(Manifest.permission.RECORD_AUDIO)
                    return@setOnClickListener
                }
                renderStep(currentStep + 1)
            } else {
                completeOnboarding()
            }
        }

        // Step 1: Mic
        btnGrantMic.setOnClickListener {
            if (!hasMicPermission()) {
                requestMic.launch(Manifest.permission.RECORD_AUDIO)
            } else {
                Toast.makeText(this, "麥克風權限已就緒", Toast.LENGTH_SHORT).show()
            }
        }

        // Step 2: Mode Selection
        cardModeBubble.setOnClickListener {
            updateModeSelection(ModelConfig.MODE_BUBBLE)
        }
        cardModeKeyboard.setOnClickListener {
            updateModeSelection(ModelConfig.MODE_KEYBOARD)
        }
        cardModeBoth.setOnClickListener {
            updateModeSelection(ModelConfig.MODE_BOTH)
        }

        // Step 3: Keyboard Actions
        btnEnableIme.setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }

        btnSwitchIme.setOnClickListener {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
            // 微輪詢更新狀態
            scope.launch {
                val intervals = listOf(300L, 600L, 1000L, 1500L, 2500L)
                for (delayMs in intervals) {
                    delay(delayMs)
                    updateStep3Status()
                }
            }
        }

        // Step 3: Bubble Actions
        btnGrantOverlay.setOnClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
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
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        btnOpenAppDetails.setOnClickListener {
            val intent = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }

        // Step 4: Model Downloads
        btnDownloadXasr.setOnClickListener {
            handleDownloadButtonClick(ModelConfig.ENGINE_X_ASR)
        }

        btnDownloadQwen3.setOnClickListener {
            handleDownloadButtonClick(ModelConfig.ENGINE_QWEN3)
        }

        // Step 5: Preferences
        fun updateVadButtons(selected: Float) {
            ModelConfig.setVadSilenceSeconds(this@OnboardingActivity, selected)
            btnVadQuick.strokeWidth = if (selected == 0.8f) 4 else 1
            btnVadNormal.strokeWidth = if (selected == 1.5f) 4 else 1
            btnVadRelaxed.strokeWidth = if (selected == 2.5f) 4 else 1
        }

        val initialVad = ModelConfig.vadSilenceSeconds(this)
        updateVadButtons(initialVad)

        btnVadQuick.setOnClickListener { updateVadButtons(0.8f) }
        btnVadNormal.setOnClickListener { updateVadButtons(1.5f) }
        btnVadRelaxed.setOnClickListener { updateVadButtons(2.5f) }

        switchPunctuation.isChecked = ModelConfig.isFilterPunctuationEnabled(this)
        switchPunctuation.setOnCheckedChangeListener { _, isChecked ->
            ModelConfig.setFilterPunctuationEnabled(this, isChecked)
        }

        btnFinish.setOnClickListener {
            completeOnboarding()
        }
    }

    private fun renderStep(step: Int) {
        currentStep = step
        progressSteps.progress = step * 20
        tvStepLabel.text = "步驟 $step / 5"

        btnPrev.visibility = if (step > 1) View.VISIBLE else View.INVISIBLE
        btnNext.text = if (step == 5) "完成" else "下一步"

        step1Container.visibility = if (step == 1) View.VISIBLE else View.GONE
        step2Container.visibility = if (step == 2) View.VISIBLE else View.GONE
        step3Container.visibility = if (step == 3) View.VISIBLE else View.GONE
        step4Container.visibility = if (step == 4) View.VISIBLE else View.GONE
        step5Container.visibility = if (step == 5) View.VISIBLE else View.GONE

        // 滾動回頂端
        scrollContent.scrollTo(0, 0)

        // 刷新當前步驟對應狀態
        updateAllStatus()
    }

    private fun updateModeSelection(mode: String) {
        selectedMode = mode
        ModelConfig.setOnboardingMode(this, mode)

        val strokeSelected = ContextCompat.getColor(this, R.color.md_theme_light_primary)
        val strokeNormal = ContextCompat.getColor(this, R.color.surface_card_stroke)

        cardModeBubble.strokeColor = if (mode == ModelConfig.MODE_BUBBLE) strokeSelected else strokeNormal
        cardModeBubble.strokeWidth = if (mode == ModelConfig.MODE_BUBBLE) 4 else 2
        ivModeBubbleCheck.visibility = if (mode == ModelConfig.MODE_BUBBLE) View.VISIBLE else View.GONE

        cardModeKeyboard.strokeColor = if (mode == ModelConfig.MODE_KEYBOARD) strokeSelected else strokeNormal
        cardModeKeyboard.strokeWidth = if (mode == ModelConfig.MODE_KEYBOARD) 4 else 2
        ivModeKeyboardCheck.visibility = if (mode == ModelConfig.MODE_KEYBOARD) View.VISIBLE else View.GONE

        cardModeBoth.strokeColor = if (mode == ModelConfig.MODE_BOTH) strokeSelected else strokeNormal
        cardModeBoth.strokeWidth = if (mode == ModelConfig.MODE_BOTH) 4 else 2
        ivModeBothCheck.visibility = if (mode == ModelConfig.MODE_BOTH) View.VISIBLE else View.GONE

        // Step 3 區塊動態呈現
        val showKeyboard = mode == ModelConfig.MODE_KEYBOARD || mode == ModelConfig.MODE_BOTH
        val showBubble = mode == ModelConfig.MODE_BUBBLE || mode == ModelConfig.MODE_BOTH

        layoutStep3KeyboardGroup.visibility = if (showKeyboard) View.VISIBLE else View.GONE
        layoutStep3BubbleGroup.visibility = if (showBubble) View.VISIBLE else View.GONE
    }

    private fun updateAllStatus() {
        updateStep1Status()
        updateStep3Status()
        updateStep4Status()
        updateStep5Status()
    }

    private fun updateStep5Status() {
        val qwen3Ready = ModelConfig.isQwen3Ready(this)
        val xAsrReady = ModelConfig.isXAsrReady(this)

        // 若使用者僅下載 X-ASR，隱藏 Qwen3 專屬的 VAD 與標點符號偏好，顯示專屬說明
        if (xAsrReady && !qwen3Ready) {
            layoutQwen3Preferences.visibility = View.GONE
            cardXasrOnlyNotice.visibility = View.VISIBLE
        } else {
            layoutQwen3Preferences.visibility = View.VISIBLE
            cardXasrOnlyNotice.visibility = View.GONE
        }
    }

    private fun updateStep1Status() {
        if (hasMicPermission()) {
            tvMicStatus.text = "麥克風權限已就緒"
            tvMicStatus.setTextColor(ContextCompat.getColor(this, R.color.status_success))
            btnGrantMic.text = "已就緒"
            btnGrantMic.isEnabled = false
        } else {
            tvMicStatus.text = "語音輸入必備的核心權限，請點擊授予"
            tvMicStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            btnGrantMic.text = "授予麥克風權限"
            btnGrantMic.isEnabled = true
        }
    }

    private fun updateStep3Status() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val imeEnabled = imm.enabledInputMethodList.any { it.packageName == packageName }
        val currentIme = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        val imeDefault = currentIme != null && currentIme.contains(packageName)

        // Keyboard Status
        if (imeEnabled) {
            tvImeEnableStatus.text = "系統輸入法已開啟"
            tvImeEnableStatus.setTextColor(ContextCompat.getColor(this, R.color.status_success))
            btnEnableIme.text = "已啟用"
        } else {
            tvImeEnableStatus.text = "請在系統「虛擬鍵盤」列表中勾選 VoiceIME"
            tvImeEnableStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            btnEnableIme.text = "前往系統啟用"
        }

        if (imeDefault) {
            tvImeSwitchStatus.text = "VoiceIME 為目前使用中輸入法"
            tvImeSwitchStatus.setTextColor(ContextCompat.getColor(this, R.color.status_success))
            btnSwitchIme.text = "目前使用中"
        } else {
            tvImeSwitchStatus.text = "尚未切換為 VoiceIME（可隨時切換）"
            tvImeSwitchStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            btnSwitchIme.text = "切換輸入法"
        }

        // Bubble Status
        val overlayGranted = Settings.canDrawOverlays(this)
        val accessibilityEnabled = isAccessibilityServiceEnabled()

        if (overlayGranted) {
            tvOverlayStatus.text = "懸浮窗權限已就緒"
            tvOverlayStatus.setTextColor(ContextCompat.getColor(this, R.color.status_success))
            btnGrantOverlay.text = "已授權"
        } else {
            tvOverlayStatus.text = "允許語音泡泡飄浮在螢幕邊緣隨時供您點擊"
            tvOverlayStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            btnGrantOverlay.text = "前往授權懸浮窗"
        }

        if (accessibilityEnabled) {
            tvAccessibilityStatus.text = "自動填入文字服務已就緒"
            tvAccessibilityStatus.setTextColor(ContextCompat.getColor(this, R.color.status_success))
            btnGrantAccessibility.text = "已開啟"
        } else {
            tvAccessibilityStatus.text = "懸浮球辨識完成後，以此服務將文字填入輸入框"
            tvAccessibilityStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            btnGrantAccessibility.text = "前往開啟無障礙服務"
        }

        // 自動確保懸浮泡泡服務在系統條件就緒時運行
        if (overlayGranted && accessibilityEnabled && !FloatingBubbleService.isRunning) {
            FloatingBubbleService.start(this)
        }
    }

    private fun updateStep4Status() {
        val xAsrReady = ModelConfig.isXAsrReady(this)
        val qwen3Ready = ModelConfig.isQwen3Ready(this)

        tvXasrBadge.text = if (xAsrReady) "已就緒" else "未下載"
        tvXasrBadge.setTextColor(ContextCompat.getColor(this, if (xAsrReady) R.color.status_success else R.color.text_tertiary))

        tvQwen3Badge.text = if (qwen3Ready) "已就緒" else "未下載"
        tvQwen3Badge.setTextColor(ContextCompat.getColor(this, if (qwen3Ready) R.color.status_success else R.color.text_tertiary))

        val active = ModelDownloadState.active.value
        if (active == null) {
            if (xAsrReady) {
                btnDownloadXasr.text = "已就緒"
            } else {
                btnDownloadXasr.text = "下載 X-ASR 模型"
            }

            if (qwen3Ready) {
                btnDownloadQwen3.text = "已就緒"
            } else {
                btnDownloadQwen3.text = "下載 Qwen3-ASR 模型"
            }
        }
    }

    private fun handleDownloadButtonClick(engine: String) {
        val active = ModelDownloadState.active.value
        if (active != null && active.engine == engine) {
            ModelDownloadService.cancel(this)
            updateStep4Status()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
            pendingDownloadEngine = engine
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }

        ModelDownloadService.start(this, engine)
    }

    private fun startObservingDownloads() {
        observeJob = scope.launch {
            launch {
                ModelDownloadState.active.collect { active ->
                    renderDownloadProgress(active)
                }
            }
            launch {
                ModelDownloadState.results.collect { (engine, result) ->
                    val label = if (engine == ModelConfig.ENGINE_X_ASR) "X-ASR" else "Qwen3-ASR"
                    result.onSuccess {
                        Toast.makeText(this@OnboardingActivity, "$label 模型下載完成", Toast.LENGTH_LONG).show()
                        updateStep4Status()
                    }.onFailure { ex ->
                        Toast.makeText(this@OnboardingActivity, "$label 下載失敗: ${ex.message}", Toast.LENGTH_LONG).show()
                        updateStep4Status()
                    }
                }
            }
        }
    }

    private fun renderDownloadProgress(active: ModelDownloadState.Active?) {
        if (active == null) {
            progressXasr.visibility = View.GONE
            tvStatusXasr.visibility = View.GONE
            progressQwen3.visibility = View.GONE
            tvStatusQwen3.visibility = View.GONE
            updateStep4Status()
            return
        }

        val isXasr = active.engine == ModelConfig.ENGINE_X_ASR
        val progressIndicator = if (isXasr) progressXasr else progressQwen3
        val tvStatus = if (isXasr) tvStatusXasr else tvStatusQwen3
        val btn = if (isXasr) btnDownloadXasr else btnDownloadQwen3

        btn.text = "取消下載"
        progressIndicator.visibility = View.VISIBLE
        tvStatus.visibility = View.VISIBLE

        progressIndicator.isIndeterminate = active.progress.percent < 0
        if (active.progress.percent >= 0) {
            progressIndicator.progress = active.progress.percent
        }

        val pctStr = if (active.progress.percent >= 0) "${active.progress.percent}%" else ""
        tvStatus.text = "${active.progress.label} $pctStr"
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

    private fun isAccessibilityServiceEnabled(): Boolean {
        if (VoiceAccessibilityService.isServiceRunning()) return true
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.contains(packageName)
    }

    private fun completeOnboarding() {
        ModelConfig.setOnboardingCompleted(this, true)

        // 若下載了模型但尚未選取引擎，自動選取一個就緒的引擎
        val xAsrReady = ModelConfig.isXAsrReady(this)
        val qwen3Ready = ModelConfig.isQwen3Ready(this)
        if (qwen3Ready) {
            ModelConfig.setSelectedEngine(this, ModelConfig.ENGINE_QWEN3)
        } else if (xAsrReady) {
            ModelConfig.setSelectedEngine(this, ModelConfig.ENGINE_X_ASR)
        }

        startActivity(Intent(this, ImeSettingsActivity::class.java))
        finish()
    }
}
