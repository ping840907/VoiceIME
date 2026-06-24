package com.ping.elderlyassistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.*
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.lifecycleScope
import com.ping.elderlyassistant.pipeline.PipelineOrchestrator
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Foreground service that owns the floating bubble overlay.
 *
 * Bubble lifecycle:
 *   Collapsed → tap → Expanded → tap ✕ → Collapsed
 *
 * Expanded panel mic button:
 *   Phase 1: shows node count
 *   Phase 2: record → Whisper transcribe → show result
 *   Phase 3: record → Whisper → Qwen → execute action
 *
 * Emergency escape: triple-tap collapsed bubble within 2 s → stopSelf()
 */
class FloatingBubbleService : Service(), LifecycleOwner {

    companion object {
        private const val TAG              = "BubbleService"
        private const val NOTIF_CHANNEL_ID = "bubble_channel"
        private const val NOTIF_ID         = 1001
        private const val DRAG_THRESHOLD   = 10
        private const val TRIPLE_TAP_MS    = 2000L
        const val ACTION_START = "com.ping.elderlyassistant.START_BUBBLE"
        const val ACTION_STOP  = "com.ping.elderlyassistant.STOP_BUBBLE"
    }

    // ── LifecycleOwner so we can use lifecycleScope ───────────────────────────
    private val _lifecycle = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = _lifecycle

    private lateinit var windowManager: WindowManager
    private val handler = Handler(Looper.getMainLooper())

    private var bubbleView: View? = null
    private lateinit var bubbleParams: WindowManager.LayoutParams
    private var tapCount = 0
    private val resetTap = Runnable { tapCount = 0 }

    private var expandedView: View? = null
    private var isExpanded = false

    private lateinit var pipeline: PipelineOrchestrator

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        _lifecycle.currentState = Lifecycle.State.CREATED
        _lifecycle.currentState = Lifecycle.State.STARTED
        _lifecycle.currentState = Lifecycle.State.RESUMED

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        pipeline = PipelineOrchestrator(this)
        pipeline.preloadModels()

        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
        addBubble()

        observePipelineState()

        Log.i(TAG, "FloatingBubbleService started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        return START_STICKY
    }

    override fun onDestroy() {
        _lifecycle.currentState = Lifecycle.State.DESTROYED
        removeAllViews()
        pipeline.destroy()
        Log.i(TAG, "FloatingBubbleService stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Pipeline state → UI ───────────────────────────────────────────────────

    private fun observePipelineState() {
        lifecycleScope.launch {
            pipeline.state.collectLatest { state ->
                when (state) {
                    is PipelineOrchestrator.State.Idle ->
                        updatePanel(getString(R.string.bubble_tap_hint), listening = false)

                    is PipelineOrchestrator.State.Recording ->
                        updatePanel(getString(R.string.bubble_listening), listening = true)

                    is PipelineOrchestrator.State.Transcribing ->
                        updatePanel("辨識中 (%.1fs)…".format(state.durationSec), listening = false)

                    is PipelineOrchestrator.State.Thinking ->
                        updatePanel("理解中：「${state.transcript}」", listening = false)

                    is PipelineOrchestrator.State.Executing ->
                        updatePanel("執行中…", listening = false)

                    is PipelineOrchestrator.State.Done -> {
                        val msg = buildString {
                            append("已辨識：「${state.transcript}」")
                            if (state.llmStats != null) {
                                append("\n速度：%.1f t/s".format(state.llmStats.decodeSpeedTps))
                            }
                            if (state.actionJson != null) {
                                append("\n動作：${state.actionJson.take(60)}")
                            }
                        }
                        updatePanel(msg, listening = false)
                    }

                    is PipelineOrchestrator.State.Error ->
                        updatePanel("錯誤：${state.message}", listening = false)
                }
            }
        }
    }

    // ── Bubble creation ───────────────────────────────────────────────────────

    private fun addBubble() {
        bubbleParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0; y = 400
        }

        bubbleView = LayoutInflater.from(this).inflate(R.layout.layout_bubble_collapsed, null)
        attachDragTap()
        windowManager.addView(bubbleView, bubbleParams)
    }

    // ── Drag + tap discrimination ─────────────────────────────────────────────

    private fun attachDragTap() {
        var startX = 0; var startY = 0
        var rawX = 0f;  var rawY = 0f
        var dragged = false

        bubbleView?.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = bubbleParams.x; startY = bubbleParams.y
                    rawX = e.rawX; rawY = e.rawY; dragged = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - rawX).toInt(); val dy = (e.rawY - rawY).toInt()
                    if (!dragged && (abs(dx) > DRAG_THRESHOLD || abs(dy) > DRAG_THRESHOLD)) dragged = true
                    if (dragged) {
                        bubbleParams.x = startX + dx; bubbleParams.y = startY + dy
                        windowManager.updateViewLayout(bubbleView, bubbleParams)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> { if (!dragged) handleTap(); true }
                else -> false
            }
        }
    }

    private fun handleTap() {
        tapCount++
        handler.removeCallbacks(resetTap)
        if (tapCount >= 3) { tapCount = 0; stopSelf(); return }
        handler.postDelayed(resetTap, TRIPLE_TAP_MS)
        if (!isExpanded) showPanel() else dismissPanel()
    }

    // ── Expanded panel ────────────────────────────────────────────────────────

    private fun showPanel() {
        if (isExpanded) return
        isExpanded = true
        bubbleView?.findViewById<TextView>(R.id.tv_bubble_icon)
            ?.setBackgroundResource(R.drawable.bubble_background_active)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM }

        expandedView = LayoutInflater.from(this).inflate(R.layout.layout_bubble_expanded, null)

        expandedView?.findViewById<TextView>(R.id.btn_collapse)?.setOnClickListener {
            pipeline.stopRecordingEarly()
            dismissPanel()
        }

        expandedView?.findViewById<TextView>(R.id.btn_mic)?.setOnClickListener {
            onMicTapped()
        }

        expandedView?.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_OUTSIDE) dismissPanel()
            false
        }

        windowManager.addView(expandedView, params)
    }

    private fun dismissPanel() {
        if (!isExpanded) return
        isExpanded = false
        bubbleView?.findViewById<TextView>(R.id.tv_bubble_icon)
            ?.setBackgroundResource(R.drawable.bubble_background)
        expandedView?.let { if (it.isAttachedToWindow) windowManager.removeView(it) }
        expandedView = null
    }

    // ── Mic button ────────────────────────────────────────────────────────────

    private fun onMicTapped() {
        val state = pipeline.state.value
        if (state is PipelineOrchestrator.State.Recording) {
            // Second tap while recording → stop early
            pipeline.stopRecordingEarly()
            return
        }
        if (state !is PipelineOrchestrator.State.Idle) return
        pipeline.startListening()
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    private fun updatePanel(text: String, listening: Boolean) {
        handler.post {
            expandedView?.findViewById<TextView>(R.id.tv_status)?.text = text
            expandedView?.findViewById<TextView>(R.id.btn_mic)?.apply {
                this.text = if (listening) "■" else "話"
                setTextColor(
                    if (listening) resources.getColor(R.color.bubble_bg_active, theme)
                    else resources.getColor(R.color.blue_700, theme)
                )
            }
        }
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                NOTIF_CHANNEL_ID, "語音助理服務", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "長輩語音助理背景常駐服務"; setShowBadge(false) }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, FloatingBubbleService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle("語音助理執行中")
            .setContentText("點擊 AI 氣泡說話")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_delete, "停止", stop)
            .build()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun overlayType() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private fun removeAllViews() {
        handler.removeCallbacksAndMessages(null)
        bubbleView?.let { if (it.isAttachedToWindow) windowManager.removeView(it) }
        expandedView?.let { if (it.isAttachedToWindow) windowManager.removeView(it) }
        bubbleView = null; expandedView = null
    }
}
