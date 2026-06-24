package com.ping.elderlyassistant.pipeline

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import com.ping.elderlyassistant.R

/**
 * Full-screen semi-transparent overlay shown during AI automation steps.
 *
 * The overlay intercepts all touches so the user cannot accidentally tap the
 * underlying app while the assistant is performing actions. A Cancel button
 * lets the user abort immediately.
 *
 * Must be created and used on the main thread (or via [show]/[hide] which
 * dispatch to the main thread internally).
 */
class BlockingOverlay(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var overlayView: android.view.View? = null

    /**
     * Show the overlay (idempotent: updates status text if already visible).
     *
     * @param statusText Short description of current step for the user
     * @param onCancel   Callback invoked when the user taps the Cancel button
     */
    fun show(statusText: String, onCancel: () -> Unit) {
        handler.post {
            if (overlayView != null) {
                overlayView?.findViewById<TextView>(R.id.tv_overlay_status)?.text = statusText
                return@post
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                overlayType(),
                // FLAG_NOT_FOCUSABLE: avoid stealing IME; touch events are still intercepted
                // because the layout covers the full screen and is not transparent to input.
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
            }

            val view = LayoutInflater.from(context)
                .inflate(R.layout.layout_blocking_overlay, null)
            view.findViewById<TextView>(R.id.tv_overlay_status)?.text = statusText
            view.findViewById<Button>(R.id.btn_overlay_cancel)?.setOnClickListener { onCancel() }

            overlayView = view
            try {
                windowManager.addView(view, params)
            } catch (ex: Exception) {
                Log.e("BlockingOverlay", "addView failed — overlay permission revoked?: ${ex.message}")
                overlayView = null
            }
        }
    }

    /** Update the status line without rebuilding the view. */
    fun updateStatus(text: String) {
        handler.post {
            overlayView?.findViewById<TextView>(R.id.tv_overlay_status)?.text = text
        }
    }

    /** Remove the overlay. Safe to call even if the overlay is not showing. */
    fun hide() {
        handler.post {
            overlayView?.let {
                if (it.isAttachedToWindow) windowManager.removeView(it)
            }
            overlayView = null
        }
    }

    private fun overlayType() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
}
