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
import kotlin.math.abs

/**
 * Foreground service that owns the floating bubble overlay.
 *
 * Bubble lifecycle:
 *   Collapsed (small circle) → user taps → Expanded (bottom panel) → user taps ✕ → Collapsed
 *
 * Emergency escape: triple-tap the collapsed bubble within 2 seconds → service stops.
 *
 * WindowManager usage:
 *   - TYPE_APPLICATION_OVERLAY (Android 8+)
 *   - FLAG_NOT_FOCUSABLE on collapsed bubble so key events pass through
 *   - FLAG_NOT_TOUCH_MODAL on expanded panel so touches outside close it
 */
class FloatingBubbleService : Service() {

    companion object {
        private const val TAG = "BubbleService"
        private const val NOTIF_CHANNEL_ID = "bubble_channel"
        private const val NOTIF_ID = 1001
        private const val DRAG_THRESHOLD_PX = 10
        private const val TRIPLE_TAP_RESET_MS = 2000L
        const val ACTION_START = "com.ping.elderlyassistant.START_BUBBLE"
        const val ACTION_STOP  = "com.ping.elderlyassistant.STOP_BUBBLE"
    }

    private lateinit var windowManager: WindowManager
    private val handler = Handler(Looper.getMainLooper())

    // Collapsed bubble state
    private var bubbleView: View? = null
    private lateinit var bubbleParams: WindowManager.LayoutParams
    private var tapCount = 0
    private val resetTapCount = Runnable { tapCount = 0 }

    // Expanded panel state
    private var expandedView: View? = null
    private var isExpanded = false

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
        addBubble()
        Log.i(TAG, "FloatingBubbleService started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        removeAllViews()
        Log.i(TAG, "FloatingBubbleService stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Bubble creation ───────────────────────────────────────────────────────

    private fun addBubble() {
        val overlayType = overlayWindowType()

        bubbleParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 400
        }

        bubbleView = LayoutInflater.from(this).inflate(R.layout.layout_bubble_collapsed, null)
        attachDragAndTapListener()
        windowManager.addView(bubbleView, bubbleParams)
    }

    // ── Touch: drag + tap discrimination ─────────────────────────────────────

    private fun attachDragAndTapListener() {
        var startX = 0
        var startY = 0
        var rawStartX = 0f
        var rawStartY = 0f
        var wasDragged = false

        bubbleView?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = bubbleParams.x
                    startY = bubbleParams.y
                    rawStartX = event.rawX
                    rawStartY = event.rawY
                    wasDragged = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - rawStartX).toInt()
                    val dy = (event.rawY - rawStartY).toInt()
                    if (!wasDragged && (abs(dx) > DRAG_THRESHOLD_PX || abs(dy) > DRAG_THRESHOLD_PX)) {
                        wasDragged = true
                    }
                    if (wasDragged) {
                        bubbleParams.x = startX + dx
                        bubbleParams.y = startY + dy
                        windowManager.updateViewLayout(bubbleView, bubbleParams)
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (!wasDragged) handleBubbleTap()
                    true
                }

                else -> false
            }
        }
    }

    /** Single tap opens expanded panel; triple tap within 2s stops service. */
    private fun handleBubbleTap() {
        tapCount++
        handler.removeCallbacks(resetTapCount)

        if (tapCount >= 3) {
            Log.i(TAG, "Triple-tap detected — stopping service")
            tapCount = 0
            stopSelf()
            return
        }

        handler.postDelayed(resetTapCount, TRIPLE_TAP_RESET_MS)

        if (!isExpanded) {
            showExpandedPanel()
        } else {
            dismissExpandedPanel()
        }
    }

    // ── Expanded panel ────────────────────────────────────────────────────────

    private fun showExpandedPanel() {
        if (isExpanded) return
        isExpanded = true

        // Pulse the bubble icon to indicate active state
        bubbleView?.findViewById<TextView>(R.id.tv_bubble_icon)
            ?.setBackgroundResource(R.drawable.bubble_background_active)

        val overlayType = overlayWindowType()
        val panelParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
        }

        expandedView = LayoutInflater.from(this).inflate(R.layout.layout_bubble_expanded, null)

        // Wire collapse button
        expandedView?.findViewById<TextView>(R.id.btn_collapse)?.setOnClickListener {
            dismissExpandedPanel()
        }

        // Wire mic button (Phase 1: shows node info in debug text view)
        expandedView?.findViewById<TextView>(R.id.btn_mic)?.setOnClickListener {
            onMicButtonTapped()
        }

        // Dismiss on outside touch
        expandedView?.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                dismissExpandedPanel()
            }
            false
        }

        updatePanelStatus(getString(R.string.bubble_tap_hint))
        windowManager.addView(expandedView, panelParams)
    }

    private fun dismissExpandedPanel() {
        if (!isExpanded) return
        isExpanded = false

        bubbleView?.findViewById<TextView>(R.id.tv_bubble_icon)
            ?.setBackgroundResource(R.drawable.bubble_background)

        expandedView?.let { v ->
            if (v.isAttachedToWindow) windowManager.removeView(v)
        }
        expandedView = null
    }

    /**
     * Phase 1: tapping the mic button captures + displays node tree info.
     * Phase 2+: this will trigger Whisper ASR recording.
     */
    private fun onMicButtonTapped() {
        val svc = AssistantAccessibilityService.instance
        if (svc == null) {
            updatePanelStatus(getString(R.string.bubble_accessibility_off))
            return
        }

        updatePanelStatus(getString(R.string.bubble_processing))

        val tree = svc.captureNodeTree()
        if (tree == null) {
            updatePanelStatus("無法讀取畫面，請重試")
            return
        }

        val msg = getString(R.string.bubble_node_captured) +
                "\n(${tree.nodeCount} 個節點，Package: ${tree.packageName.substringAfterLast('.')})"
        updatePanelStatus(msg)

        // Show raw node info in debug view (BuildConfig.DEBUG only)
        if (BuildConfig.DEBUG) {
            expandedView?.findViewById<TextView>(R.id.tv_node_info)?.apply {
                visibility = View.VISIBLE
                text = tree.text.lines().take(5).joinToString("\n")
            }
        }
    }

    private fun updatePanelStatus(msg: String) {
        expandedView?.findViewById<TextView>(R.id.tv_status)?.text = msg
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIF_CHANNEL_ID,
                "語音助理服務",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "長輩語音助理背景常駐服務"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, FloatingBubbleService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle("語音助理執行中")
            .setContentText("點擊螢幕上的 AI 氣泡開始說話")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_delete, "停止", stopPending)
            .build()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun overlayWindowType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE

    private fun removeAllViews() {
        handler.removeCallbacksAndMessages(null)
        bubbleView?.let { if (it.isAttachedToWindow) windowManager.removeView(it) }
        expandedView?.let { if (it.isAttachedToWindow) windowManager.removeView(it) }
        bubbleView = null
        expandedView = null
    }
}
