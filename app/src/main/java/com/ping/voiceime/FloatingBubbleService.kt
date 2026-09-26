package com.ping.voiceime

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.google.android.material.card.MaterialCardView
import com.k2fsa.sherpa.onnx.OnlineStream
import com.ping.voiceime.engine.AudioRecorder
import com.ping.voiceime.engine.ModelConfig
import com.ping.voiceime.engine.Qwen3AsrEngine
import com.ping.voiceime.engine.XAsrEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.hypot

class FloatingBubbleService : Service() {

    companion object {
        private const val TAG = "FloatingBubbleService"
        private const val NOTIFICATION_ID = 2001
        private const val CHANNEL_ID = "floating_bubble_channel"
        private const val ACTION_STOP = "com.ping.voiceime.ACTION_STOP_BUBBLE"

        @Volatile
        var isRunning = false
            private set

        @Volatile
        var instance: FloatingBubbleService? = null
            private set

        fun start(context: Context) {
            val intent = Intent(context, FloatingBubbleService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, FloatingBubbleService::class.java)
            context.stopService(intent)
        }
    }

    private val themedCtx by lazy { ContextThemeWrapper(this, R.style.Theme_VoiceIME) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val qwen3Asr by lazy { Qwen3AsrEngine(this) }
    private val xAsr by lazy { XAsrEngine(this) }
    private val recorder = AudioRecorder()

    private lateinit var windowManager: WindowManager
    private lateinit var windowLayoutParams: WindowManager.LayoutParams
    private lateinit var bubbleView: View

    private lateinit var previewLayoutParams: WindowManager.LayoutParams
    private lateinit var previewView: View

    private lateinit var layoutBubbleMicColumn: LinearLayout
    private lateinit var btnBubbleX: FrameLayout
    private lateinit var btnBubbleMic: FrameLayout
    private lateinit var ivBubbleIcon: ImageView
    private lateinit var progressBubble: ProgressBar
    private lateinit var cardBubblePreview: MaterialCardView
    private lateinit var tvBubblePreview: TextView

    private var activeStream: OnlineStream? = null
    private var recordingJob: Job? = null
    private var isRecording = false
    private var isAborted = false
    private var lastStreamingText = ""
    private var autoHidePreviewJob: Job? = null
    private var xButtonAutoHideJob: Job? = null
    private var snapAnimator: ValueAnimator? = null
    private var dynamicKeyboardTop: Int = 0
    private var isDockedOnRight = true
    private var isXButtonShowing = false

    // §6 狀態機: RECORDING, TRANSCRIBING, PASTED
    private enum class State { IDLE, LOADING, RECORDING, TRANSCRIBING, PASTED }
    private var state = State.IDLE

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        isRunning = true
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startForegroundWithNotification()

        if (Settings.canDrawOverlays(this)) {
            setupBubbleView()
        } else {
            Log.w(TAG, "Overlay permission not granted!")
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.bubble_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "提供懸浮泡泡語音輸入服務"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun startForegroundWithNotification() {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, ImeSettingsActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, FloatingBubbleService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("VoiceIME 懸浮語音輸入")
            .setContentText("輕觸懸浮泡泡即可語音輸入至目前焦點欄位")
            .setSmallIcon(R.drawable.ic_mic)
            .setContentIntent(openIntent)
            .addAction(0, "關閉泡泡", stopIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupBubbleView() {
        bubbleView = LayoutInflater.from(themedCtx).inflate(R.layout.layout_floating_bubble, null)

        layoutBubbleMicColumn = bubbleView.findViewById(R.id.layout_bubble_mic_column)
        btnBubbleX            = bubbleView.findViewById(R.id.btn_bubble_x)
        btnBubbleMic          = bubbleView.findViewById(R.id.btn_bubble_mic)
        ivBubbleIcon          = bubbleView.findViewById(R.id.iv_bubble_icon)
        progressBubble        = bubbleView.findViewById(R.id.progress_bubble)

        previewView           = LayoutInflater.from(themedCtx).inflate(R.layout.layout_floating_preview, null)
        cardBubblePreview     = previewView.findViewById(R.id.card_bubble_preview)
        tvBubblePreview       = previewView.findViewById(R.id.tv_bubble_preview)

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val displayMetrics = resources.displayMetrics
        val density = displayMetrics.density
        val edgeMargin = (16 * density).toInt()
        val bubbleWidthPx = (60 * density).toInt()
        val bubbleHeightPx = (106 * density).toInt()

        val minY = getMinY()
        val maxY = getMaxY()
        val initialY = ((minY + maxY) / 2).coerceIn(minY, maxY)

        // Fixed-size window for mic bubble: prevents any Surface buffer resize or jitter
        windowLayoutParams = WindowManager.LayoutParams(
            bubbleWidthPx,
            bubbleHeightPx,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.RIGHT
            x = edgeMargin
            y = initialY
        }

        // Dedicated overlay window for speech preview tooltip
        previewLayoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.RIGHT
            x = edgeMargin + bubbleWidthPx + (8 * density).toInt()
            y = initialY + ((106 - 56) * density).toInt()
        }

        isDockedOnRight = true

        btnBubbleX.setOnClickListener {
            onXButtonClick()
        }

        var initialX = 0
        var initialYPos = 0
        var touchStartX = 0f
        var touchStartY = 0f
        var isDragging = false

        btnBubbleMic.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    snapAnimator?.cancel()
                    initialX = windowLayoutParams.x
                    initialYPos = windowLayoutParams.y
                    touchStartX = event.rawX
                    touchStartY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchStartX
                    val dy = event.rawY - touchStartY
                    if (!isDragging && hypot(dx, dy) > 16) {
                        isDragging = true
                        hidePreviewText()
                        val screenWidth = getScreenWidth()
                        if (isDockedOnRight) {
                            val currentLeft = screenWidth - windowLayoutParams.x - bubbleWidthPx
                            windowLayoutParams.gravity = Gravity.TOP or Gravity.LEFT
                            windowLayoutParams.x = currentLeft
                            windowManager.updateViewLayout(bubbleView, windowLayoutParams)
                        }
                        initialX = windowLayoutParams.x
                        initialYPos = windowLayoutParams.y
                    }
                    if (isDragging) {
                        val screenWidth = getScreenWidth()
                        val screenHeight = getScreenHeight()
                        val minDragX = (-bubbleWidthPx + (20 * density)).toInt()
                        val maxDragX = (screenWidth - (20 * density)).toInt()
                        val minDragY = 0
                        val maxDragY = (screenHeight - (20 * density)).toInt()

                        windowLayoutParams.x = (initialX + (event.rawX - touchStartX)).toInt().coerceIn(minDragX, maxDragX)
                        windowLayoutParams.y = (initialYPos + (event.rawY - touchStartY)).toInt().coerceIn(minDragY, maxDragY)
                        windowManager.updateViewLayout(bubbleView, windowLayoutParams)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        onBubbleClick()
                    } else {
                        snapToSafeBounds()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (isDragging) {
                        snapToSafeBounds()
                    }
                    true
                }
                else -> false
            }
        }

        windowManager.addView(bubbleView, windowLayoutParams)
        previewView.visibility = View.GONE
        windowManager.addView(previewView, previewLayoutParams)
        setState(State.IDLE)

        // Listen for soft keyboard open/close and dynamic height changes
        VoiceAccessibilityService.onKeyboardStateChanged = { info ->
            Handler(Looper.getMainLooper()).post {
                dynamicKeyboardTop = if (info.isVisible) info.keyboardTop else 0
                setBubbleVisible(info.isVisible)

                if (info.isVisible && ::bubbleView.isInitialized && bubbleView.isAttachedToWindow) {
                    val safeMax = getMaxY()
                    if (windowLayoutParams.y > safeMax) {
                        snapAnimator?.cancel()
                        val startY = windowLayoutParams.y
                        ValueAnimator.ofInt(startY, safeMax).apply {
                            duration = 220
                            interpolator = DecelerateInterpolator()
                            addUpdateListener { anim ->
                                if (bubbleView.isAttachedToWindow) {
                                    windowLayoutParams.y = anim.animatedValue as Int
                                    windowManager.updateViewLayout(bubbleView, windowLayoutParams)
                                }
                            }
                            start()
                        }
                    }
                }
            }
        }

        // §6.2 被動自動隱藏規則 (PASTED 狀態下打字、游標移動、失焦自動隱藏 X 鍵)
        VoiceAccessibilityService.onManualTypingDetected = {
            Handler(Looper.getMainLooper()).post {
                if (state == State.PASTED) {
                    hideXButton()
                    setState(State.IDLE)
                }
            }
        }
        VoiceAccessibilityService.onCursorMoved = {
            Handler(Looper.getMainLooper()).post {
                if (state == State.PASTED) {
                    hideXButton()
                    setState(State.IDLE)
                }
            }
        }
        VoiceAccessibilityService.onInputFocusLost = {
            Handler(Looper.getMainLooper()).post {
                if (state == State.PASTED) {
                    hideXButton()
                    setState(State.IDLE)
                }
            }
        }

        val initialKeyboard = VoiceAccessibilityService.instance?.checkKeyboardState()
        if (initialKeyboard != null && initialKeyboard.isVisible) {
            dynamicKeyboardTop = initialKeyboard.keyboardTop
            setBubbleVisible(true, animate = false)
        } else if (VoiceAccessibilityService.isServiceRunning()) {
            setBubbleVisible(false, animate = false)
        } else {
            setBubbleVisible(true, animate = false)
        }
    }

    private fun getStatusBarHeight(): Int {
        val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) resources.getDimensionPixelSize(resId) else (24 * resources.displayMetrics.density).toInt()
    }

    private fun getMinY(): Int {
        val density = resources.displayMetrics.density
        return getStatusBarHeight() + (8 * density).toInt()
    }

    private fun getMaxY(): Int {
        val density = resources.displayMetrics.density
        val screenHeight = getScreenHeight()
        val bubbleHeight = if (::bubbleView.isInitialized && bubbleView.height > 0) bubbleView.height else (60 * density).toInt()
        val margin = (8 * density).toInt()

        val keyboardTop = if (dynamicKeyboardTop in 1 until screenHeight) {
            dynamicKeyboardTop
        } else {
            (screenHeight * 0.58f).toInt()
        }

        val calculatedMax = keyboardTop - bubbleHeight - margin
        return maxOf(getMinY(), calculatedMax)
    }

    private fun getScreenWidth(): Int = resources.displayMetrics.widthPixels
    private fun getScreenHeight(): Int = resources.displayMetrics.heightPixels

    fun setBubbleVisible(visible: Boolean, animate: Boolean = true) {
        if (!::bubbleView.isInitialized) return
        if (!visible && (state == State.RECORDING || state == State.TRANSCRIBING)) {
            return
        }
        bubbleView.animate().cancel()
        if (visible) {
            bubbleView.visibility = View.VISIBLE
            if (animate) {
                bubbleView.animate().alpha(1f).setDuration(180).start()
            } else {
                bubbleView.alpha = 1f
            }
        } else {
            if (animate) {
                bubbleView.animate().alpha(0f).setDuration(180).withEndAction {
                    if (state != State.RECORDING && state != State.TRANSCRIBING) {
                        bubbleView.visibility = View.GONE
                    } else {
                        bubbleView.alpha = 1f
                    }
                }.start()
            } else {
                bubbleView.visibility = View.GONE
                bubbleView.alpha = 0f
            }
        }
    }

    private fun updateLayoutForEdge(onRightEdge: Boolean) {
        isDockedOnRight = onRightEdge
        updatePreviewPosition()
    }

    private fun snapToSafeBounds() {
        if (!bubbleView.isAttachedToWindow) return
        snapAnimator?.cancel()

        val screenWidth = getScreenWidth()
        val density = resources.displayMetrics.density
        val edgeMargin = (16 * density).toInt()
        val bubbleWidthPx = (60 * density).toInt()

        val currentLeft = if (isDockedOnRight && (windowLayoutParams.gravity and Gravity.RIGHT == Gravity.RIGHT)) {
            screenWidth - windowLayoutParams.x - bubbleWidthPx
        } else {
            windowLayoutParams.x
        }
        val currentCenterX = currentLeft + bubbleWidthPx / 2
        val targetOnRight = currentCenterX >= screenWidth / 2
        isDockedOnRight = targetOnRight

        val curMinY = getMinY()
        val curMaxY = getMaxY()
        val targetY = windowLayoutParams.y.coerceIn(curMinY, curMaxY)

        val targetX = if (targetOnRight) {
            screenWidth - bubbleWidthPx - edgeMargin
        } else {
            edgeMargin
        }

        val startX = currentLeft
        val startY = windowLayoutParams.y

        // Normalize to TOP | LEFT for smooth interpolation
        windowLayoutParams.gravity = Gravity.TOP or Gravity.LEFT
        windowLayoutParams.x = startX

        snapAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 260
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { anim ->
                if (bubbleView.isAttachedToWindow) {
                    val f = anim.animatedFraction
                    windowLayoutParams.x = (startX + (targetX - startX) * f).toInt()
                    windowLayoutParams.y = (startY + (targetY - startY) * f).toInt()
                    windowManager.updateViewLayout(bubbleView, windowLayoutParams)
                }
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (!bubbleView.isAttachedToWindow) return
                    isDockedOnRight = targetOnRight
                    if (targetOnRight) {
                        windowLayoutParams.gravity = Gravity.TOP or Gravity.RIGHT
                        windowLayoutParams.x = edgeMargin
                    } else {
                        windowLayoutParams.gravity = Gravity.TOP or Gravity.LEFT
                        windowLayoutParams.x = edgeMargin
                    }
                    windowLayoutParams.y = targetY
                    windowManager.updateViewLayout(bubbleView, windowLayoutParams)
                    updatePreviewPosition()
                }
            })
            start()
        }
    }

    // 懸浮按鈕 X 鍵點擊事件
    private fun onXButtonClick() {
        when (state) {
            State.RECORDING -> {
                // 中止錄音：停止 AudioRecord、丟棄音訊 buffer、關閉預覽氣泡、state → IDLE
                isAborted = true
                isRecording = false
                recordingJob?.cancel()
                recorder.stopEarly()
                activeStream?.let { runCatching { it.release() } }
                activeStream = null
                hidePreviewText()
                setState(State.IDLE)
                hideXButton()
            }
            State.TRANSCRIBING -> {
                // 中斷模型推論 (Abort)：Cancel 背景轉譯協程、設置 isAborted 旗標阻止後續貼上、state → IDLE
                isAborted = true
                recordingJob?.cancel()
                hidePreviewText()
                setState(State.IDLE)
                hideXButton()
            }
            State.PASTED -> {
                // 復原文字框 (Undo)：透過無障礙快照還原至貼上前之內容與游標位置、state → IDLE
                val restored = VoiceAccessibilityService.instance?.restoreLastSnapshot() ?: false
                if (restored) {
                    showPreviewText("已復原", autoHide = true)
                }
                setState(State.IDLE)
                hideXButton()
            }
            else -> {
                hideXButton()
            }
        }
    }

    // X 鍵向上浮現與退場動效（固定高度視窗內純透明度與位移動畫，零視窗大小變更、零錄音鍵位移）
    private fun showXButton() {
        xButtonAutoHideJob?.cancel()
        if (isXButtonShowing) return
        isXButtonShowing = true

        btnBubbleX.visibility = View.VISIBLE
        btnBubbleX.alpha = 0f
        btnBubbleX.translationY = dpToPx(16f)
        btnBubbleX.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(200)
            .start()
    }

    private fun hideXButton() {
        xButtonAutoHideJob?.cancel()
        if (!isXButtonShowing) return
        isXButtonShowing = false

        btnBubbleX.animate()
            .alpha(0f)
            .translationY(dpToPx(16f))
            .setDuration(180)
            .withEndAction {
                btnBubbleX.visibility = View.INVISIBLE
            }
            .start()
    }

    private fun updatePreviewPosition() {
        if (!::previewView.isInitialized || !::bubbleView.isInitialized) return
        val density = resources.displayMetrics.density
        val gap = (8 * density).toInt()
        val bubbleWidthPx = (60 * density).toInt()
        val edgeMargin = (16 * density).toInt()
        val micOffset = ((106 - 56) * density).toInt()

        if (isDockedOnRight) {
            previewLayoutParams.gravity = Gravity.TOP or Gravity.RIGHT
            previewLayoutParams.x = edgeMargin + bubbleWidthPx + gap
        } else {
            previewLayoutParams.gravity = Gravity.TOP or Gravity.LEFT
            previewLayoutParams.x = edgeMargin + bubbleWidthPx + gap
        }
        previewLayoutParams.y = windowLayoutParams.y + micOffset + (6 * density).toInt()

        if (previewView.isAttachedToWindow) {
            windowManager.updateViewLayout(previewView, previewLayoutParams)
        }
    }

    private fun hidePreviewText() {
        autoHidePreviewJob?.cancel()
        if (::previewView.isInitialized && previewView.isAttachedToWindow) {
            previewView.visibility = View.GONE
        }
    }

    private fun showPreviewText(msg: String, autoHide: Boolean = false) {
        if (!::previewView.isInitialized) return
        tvBubblePreview.text = msg
        updatePreviewPosition()
        if (previewView.isAttachedToWindow) {
            previewView.visibility = View.VISIBLE
        }
        autoHidePreviewJob?.cancel()
        if (autoHide) {
            autoHidePreviewJob = scope.launch {
                delay(2600)
                if (state == State.IDLE || state == State.PASTED) {
                    hidePreviewText()
                    val keyboardVisible = VoiceAccessibilityService.instance?.getKeyboardInfo()?.isVisible == true
                    if (!keyboardVisible && VoiceAccessibilityService.isServiceRunning()) {
                        setBubbleVisible(false)
                    }
                }
            }
        }
    }

    private fun onBubbleClick() {
        when (state) {
            State.RECORDING -> {
                recorder.stopEarly()
                if (isDualEngineActive() || ModelConfig.selectedEngine(this) == ModelConfig.ENGINE_QWEN3) {
                    hidePreviewText()
                    setState(State.TRANSCRIBING)
                }
            }
            State.LOADING -> { /* Waiting for model */ }
            State.TRANSCRIBING -> { /* Processing */ }
            State.IDLE, State.PASTED -> {
                hideXButton()
                startRecording()
            }
        }
    }

    private fun isDualEngineActive(): Boolean {
        return ModelConfig.isXAsrReady(this) &&
                ModelConfig.isQwen3Ready(this) &&
                ModelConfig.selectedEngine(this) == ModelConfig.ENGINE_QWEN3 &&
                ModelConfig.isDualEngineEnabled(this)
    }

    private fun startRecording() {
        isAborted = false
        if (isDualEngineActive()) {
            if (!xAsr.isLoaded() || !qwen3Asr.isLoaded() || qwen3NeedsReload()) {
                preloadDualEngineAndStart()
                return
            }
            startDualEngineRecording()
            return
        }

        val engine = ModelConfig.selectedEngine(this)
        if (engine == ModelConfig.ENGINE_X_ASR) {
            if (!xAsr.isLoaded()) {
                preloadAndStart(engine)
                return
            }
            startStreamingRecording()
        } else {
            if (!qwen3Asr.isLoaded() || qwen3NeedsReload()) {
                preloadAndStart(engine)
                return
            }
            startOfflineRecording()
        }
    }

    private fun preloadDualEngineAndStart() {
        setState(State.LOADING)
        showPreviewText("載入雙模型中…")

        scope.launch {
            val (xOk, qOk) = withContext(Dispatchers.IO) {
                val x = xAsr.load().success
                val q = qwen3Asr.load(buildHotwords()).success
                x to q
            }
            if (xOk && qOk) {
                startDualEngineRecording()
            } else {
                setState(State.IDLE)
                showPreviewText("模型載入失敗，請檢查設定", autoHide = true)
            }
        }
    }

    private fun preloadAndStart(engine: String) {
        setState(State.LOADING)
        showPreviewText("載入模型中…")

        scope.launch {
            val success = withContext(Dispatchers.IO) {
                if (engine == ModelConfig.ENGINE_X_ASR) {
                    xAsr.load().success
                } else {
                    qwen3Asr.load(buildHotwords()).success
                }
            }
            if (success) {
                if (engine == ModelConfig.ENGINE_X_ASR) {
                    startStreamingRecording()
                } else {
                    startOfflineRecording()
                }
            } else {
                setState(State.IDLE)
                showPreviewText("模型載入失敗，請檢查設定", autoHide = true)
            }
        }
    }

    // 雙引擎模式底層運作機制
    private fun startDualEngineRecording() {
        val stream = xAsr.createStream() ?: run {
            showPreviewText("無法建立辨識串流", autoHide = true)
            return
        }
        activeStream = stream
        isRecording = true
        isAborted = false
        setState(State.RECORDING)
        showXButton()
        showPreviewText("聆聽中…")

        val audioBuffer = mutableListOf<FloatArray>()
        var accumulatedXAsr = ""
        lastStreamingText = ""

        recordingJob = scope.launch {
            val stopReason = withContext(Dispatchers.IO) {
                recorder.recordStreaming(
                    silenceSeconds = ModelConfig.vadSilenceSeconds(this@FloatingBubbleService),
                    onChunk = { chunk ->
                        if (isAborted) return@recordStreaming
                        audioBuffer.add(chunk.copyOf())
                        xAsr.acceptWaveform(stream, chunk)
                        while (xAsr.isReady(stream)) {
                            xAsr.decode(stream)
                        }
                        val partial = xAsr.getResult(stream)
                        val combined = accumulatedXAsr + partial
                        if (combined.isNotBlank() && combined != lastStreamingText) {
                            lastStreamingText = combined
                            Handler(Looper.getMainLooper()).post {
                                if (isRecording && !isAborted) {
                                    showPreviewText(combined)
                                }
                            }
                        }
                        if (xAsr.isEndpoint(stream)) {
                            if (partial.isNotBlank()) accumulatedXAsr += partial
                            xAsr.reset(stream)
                        }
                    }
                )
            }

            isRecording = false
            activeStream = null
            runCatching { xAsr.inputFinished(stream) }
            runCatching {
                while (xAsr.isReady(stream)) {
                    xAsr.decode(stream)
                }
            }
            val finalPartial = xAsr.getResult(stream).trim()
            val totalXAsrText = (accumulatedXAsr + finalPartial).trim()
            lastStreamingText = totalXAsrText
            runCatching { stream.release() }

            if (isAborted) return@launch

            if (stopReason == AudioRecorder.StopReason.INITIAL_TIMEOUT) {
                setState(State.IDLE)
                hideXButton()
                showPreviewText("未偵測到語音", autoHide = true)
                return@launch
            }

            // 錄音結束，預覽氣泡淡出，維持目標框純淨
            Handler(Looper.getMainLooper()).post {
                hidePreviewText()
                setState(State.TRANSCRIBING)
            }

            // 合併完整的音訊 buffer
            val totalSamples = audioBuffer.sumOf { it.size }
            if (totalSamples == 0) {
                setState(State.IDLE)
                hideXButton()
                showPreviewText("未偵測到語音", autoHide = true)
                return@launch
            }
            val combinedAudio = FloatArray(totalSamples)
            var offset = 0
            for (chunk in audioBuffer) {
                System.arraycopy(chunk, 0, combinedAudio, offset, chunk.size)
                offset += chunk.size
            }

            // §2.4 Qwen3 轉譯與降級防護 (Fallback)
            try {
                val raw = withContext(Dispatchers.Default) {
                    if (isAborted) return@withContext ""
                    qwen3Asr.transcribe(combinedAudio)
                }
                if (isAborted) return@launch
                val text = if (raw.isNotBlank()) postProcess(raw) else ""
                onTranscriptionDone(text)
            } catch (ex: Throwable) {
                if (ex is kotlinx.coroutines.CancellationException || isAborted) return@launch
                Log.e(TAG, "Qwen3 batch inference failed: ${ex.message}", ex)
                val fallbackText = lastStreamingText
                if (fallbackText.isNotEmpty()) {
                    val processedFallback = postProcess(fallbackText)
                    onTranscriptionDone(processedFallback)
                    Toast.makeText(this@FloatingBubbleService, "離線模型轉譯異常，已套用即時辨識結果", Toast.LENGTH_SHORT).show()
                } else {
                    setState(State.IDLE)
                    hideXButton()
                    showPreviewText("轉譯失敗，請重新錄音", autoHide = true)
                    Toast.makeText(this@FloatingBubbleService, "轉譯失敗，請重新錄音", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun startOfflineRecording() {
        isRecording = true
        isAborted = false
        setState(State.RECORDING)
        showXButton()
        showPreviewText("聆聽中…")

        recordingJob = scope.launch {
            val recording = withContext(Dispatchers.IO) {
                recorder.recordUntilSilence(
                    silenceSeconds = ModelConfig.vadSilenceSeconds(this@FloatingBubbleService)
                )
            }
            isRecording = false
            if (isAborted) return@launch

            if (recording.samples.isNotEmpty()) {
                setState(State.TRANSCRIBING)
                hidePreviewText()
                try {
                    val raw = withContext(Dispatchers.Default) {
                        if (isAborted) return@withContext ""
                        qwen3Asr.transcribe(recording.samples)
                    }
                    if (isAborted) return@launch
                    val text = if (raw.isNotBlank()) postProcess(raw) else ""
                    onTranscriptionDone(text)
                } catch (ex: Throwable) {
                    if (ex is kotlinx.coroutines.CancellationException || isAborted) return@launch
                    setState(State.IDLE)
                    hideXButton()
                    showPreviewText("轉譯失敗，請重新錄音", autoHide = true)
                }
            } else {
                setState(State.IDLE)
                hideXButton()
                showPreviewText("未偵測到語音", autoHide = true)
            }
        }
    }

    private fun startStreamingRecording() {
        val engine = xAsr
        val stream = engine.createStream() ?: run {
            showPreviewText("無法建立辨識串流", autoHide = true)
            return
        }
        activeStream = stream
        isRecording = true
        isAborted = false
        setState(State.RECORDING)
        showXButton()
        showPreviewText("聆聽中…")

        var accumulated = ""
        var lastShown = ""

        recordingJob = scope.launch {
            val stopReason = withContext(Dispatchers.IO) {
                recorder.recordStreaming(
                    silenceSeconds = ModelConfig.vadSilenceSeconds(this@FloatingBubbleService),
                    onChunk = { chunk ->
                        if (isAborted) return@recordStreaming
                        engine.acceptWaveform(stream, chunk)
                        while (engine.isReady(stream)) {
                            engine.decode(stream)
                        }
                        val partial = engine.getResult(stream)
                        val combined = accumulated + partial
                        if (combined.isNotBlank() && combined != lastShown) {
                            lastShown = combined
                            Handler(Looper.getMainLooper()).post {
                                if (isRecording && !isAborted) {
                                    showPreviewText(combined)
                                }
                            }
                        }
                        if (engine.isEndpoint(stream)) {
                            if (partial.isNotBlank()) accumulated += partial
                            engine.reset(stream)
                        }
                    }
                )
            }

            isRecording = false
            activeStream = null

            runCatching { engine.inputFinished(stream) }
            runCatching {
                while (engine.isReady(stream)) {
                    engine.decode(stream)
                }
            }
            val finalSegment = engine.getResult(stream).trim()
            runCatching { stream.release() }

            if (isAborted) return@launch

            if (stopReason == AudioRecorder.StopReason.INITIAL_TIMEOUT) {
                setState(State.IDLE)
                hideXButton()
                showPreviewText("未偵測到語音", autoHide = true)
                return@launch
            }

            val fullText = (accumulated + finalSegment).trim()
            onTranscriptionDone(fullText)
        }
    }

    private fun onTranscriptionDone(text: String) {
        if (text.isBlank()) {
            setState(State.IDLE)
            hideXButton()
            showPreviewText("未偵測到文字", autoHide = true)
            return
        }

        // 1. 複製至剪貼簿以策安全
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("voice_input", text)
        clipboard.setPrimaryClip(clip)

        // 2. 透過無障礙服務貼入
        val accService = VoiceAccessibilityService.instance
        val injected = accService?.inputText(text) ?: false

        // §6 貼上完成後進入 PASTED 狀態，顯示 X 鍵供 10 秒內 Undo
        setState(State.PASTED)
        showXButton()
        xButtonAutoHideJob?.cancel()
        xButtonAutoHideJob = scope.launch {
            delay(10_000)
            if (state == State.PASTED) {
                hideXButton()
                setState(State.IDLE)
            }
        }

        if (injected) {
            showPreviewText("已貼入：$text", autoHide = true)
        } else {
            showPreviewText("已複製：$text", autoHide = true)
        }
    }

    private fun setState(s: State) {
        state = s
        if (!::btnBubbleMic.isInitialized) return
        when (state) {
            State.IDLE -> {
                btnBubbleMic.setBackgroundResource(R.drawable.bubble_background)
                ivBubbleIcon.setImageResource(R.drawable.ic_mic)
                ivBubbleIcon.visibility = View.VISIBLE
                progressBubble.visibility = View.GONE
            }
            State.LOADING -> {
                btnBubbleMic.setBackgroundResource(R.drawable.bubble_background)
                ivBubbleIcon.visibility = View.GONE
                progressBubble.visibility = View.VISIBLE
            }
            State.RECORDING -> {
                btnBubbleMic.setBackgroundResource(R.drawable.bubble_background_active)
                ivBubbleIcon.setImageResource(R.drawable.ic_mic_active)
                ivBubbleIcon.visibility = View.VISIBLE
                progressBubble.visibility = View.GONE
            }
            State.TRANSCRIBING -> {
                btnBubbleMic.setBackgroundResource(R.drawable.bubble_background_active)
                ivBubbleIcon.visibility = View.GONE
                progressBubble.visibility = View.VISIBLE
            }
            State.PASTED -> {
                btnBubbleMic.setBackgroundResource(R.drawable.bubble_background)
                ivBubbleIcon.setImageResource(R.drawable.ic_mic)
                ivBubbleIcon.visibility = View.VISIBLE
                progressBubble.visibility = View.GONE
            }
        }
    }

    private fun qwen3NeedsReload(): Boolean =
        buildHotwords() != qwen3Asr.loadedHotwords

    private fun buildHotwords(): String =
        UserDictionary.load(this).values.distinct().sorted().joinToString("\n")

    private fun postProcess(raw: String): String {
        val converted = if (ModelConfig.selectedEngine(this) == ModelConfig.ENGINE_X_ASR && !isDualEngineActive()) {
            ModelConfig.normalizeTaiwanVariants(raw)
        } else {
            ModelConfig.toTaiwanTraditional(raw)
        }
        val userReplaced = UserDictionary.apply(converted, UserDictionary.load(this))
        return if (ModelConfig.isFilterPunctuationEnabled(this)) {
            ModelConfig.filterChinesePunctuation(userReplaced)
        } else {
            userReplaced
        }
    }

    private fun dpToPx(dp: Float): Float = dp * resources.displayMetrics.density

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) {
            instance = null
        }
        isRunning = false
        VoiceAccessibilityService.onKeyboardStateChanged = null
        VoiceAccessibilityService.onInputFocusStateChanged = null
        VoiceAccessibilityService.onManualTypingDetected = null
        VoiceAccessibilityService.onCursorMoved = null
        VoiceAccessibilityService.onInputFocusLost = null
        snapAnimator?.cancel()
        recordingJob?.cancel()
        autoHidePreviewJob?.cancel()
        xButtonAutoHideJob?.cancel()
        recorder.stopEarly()
        activeStream?.let { runCatching { it.release() } }
        activeStream = null

        if (qwen3Asr.isLoaded()) qwen3Asr.release()
        if (xAsr.isLoaded()) xAsr.release()

        if (::bubbleView.isInitialized && bubbleView.isAttachedToWindow) {
            windowManager.removeView(bubbleView)
        }
        if (::previewView.isInitialized && previewView.isAttachedToWindow) {
            windowManager.removeView(previewView)
        }
    }
}
