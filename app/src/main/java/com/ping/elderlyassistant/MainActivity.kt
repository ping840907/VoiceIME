package com.ping.elderlyassistant

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Setup screen.
 *
 * Guides the user through two one-time permission grants:
 *   1. SYSTEM_ALERT_WINDOW  – lets us show the floating bubble
 *   2. Accessibility service – lets us read screen nodes and simulate taps
 *
 * Once both are granted, the "啟動語音助理" button starts FloatingBubbleService.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var tvOverlayStatus: TextView
    private lateinit var tvAccessibilityStatus: TextView
    private lateinit var tvOverallStatus: TextView
    private lateinit var btnOverlay: Button
    private lateinit var btnAccessibility: Button
    private lateinit var btnToggleService: Button

    private var serviceRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvOverlayStatus       = findViewById(R.id.tv_overlay_status)
        tvAccessibilityStatus = findViewById(R.id.tv_accessibility_status)
        tvOverallStatus       = findViewById(R.id.tv_overall_status)
        btnOverlay            = findViewById(R.id.btn_overlay_permission)
        btnAccessibility      = findViewById(R.id.btn_accessibility_permission)
        btnToggleService      = findViewById(R.id.btn_toggle_service)

        btnOverlay.setOnClickListener { openOverlaySettings() }
        btnAccessibility.setOnClickListener { openAccessibilitySettings() }
        btnToggleService.setOnClickListener { toggleService() }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionUI()
    }

    // ── Permission checks ─────────────────────────────────────────────────────

    private fun hasOverlayPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(this)
        else true

    private fun hasAccessibilityPermission(): Boolean {
        val am = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabled = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        return enabled.any { it.resolveInfo.serviceInfo.packageName == packageName }
    }

    // ── UI refresh ────────────────────────────────────────────────────────────

    private fun refreshPermissionUI() {
        val overlayOk = hasOverlayPermission()
        val accessOk  = hasAccessibilityPermission()
        val allOk     = overlayOk && accessOk

        setPermissionStatus(tvOverlayStatus, btnOverlay, overlayOk, "已授權", "未授權", "前往授權")
        setPermissionStatus(tvAccessibilityStatus, btnAccessibility, accessOk, "已啟用", "未啟用", "前往啟用")

        tvOverallStatus.text = if (allOk)
            getString(R.string.status_all_ready)
        else
            getString(R.string.status_needs_permission)
        tvOverallStatus.setTextColor(
            if (allOk) getColor(R.color.permission_granted)
            else getColor(R.color.permission_missing)
        )

        btnToggleService.isEnabled = allOk
        btnToggleService.text = if (serviceRunning)
            getString(R.string.btn_stop_service)
        else
            getString(R.string.btn_start_service)
    }

    private fun setPermissionStatus(
        statusTv: TextView,
        btn: Button,
        granted: Boolean,
        grantedLabel: String,
        missingLabel: String,
        btnLabel: String
    ) {
        if (granted) {
            statusTv.text = grantedLabel
            statusTv.setTextColor(getColor(R.color.permission_granted))
            btn.text = "已完成 ✓"
            btn.isEnabled = false
        } else {
            statusTv.text = missingLabel
            statusTv.setTextColor(getColor(R.color.permission_missing))
            btn.text = btnLabel
            btn.isEnabled = true
        }
    }

    // ── Permission navigation ─────────────────────────────────────────────────

    private fun openOverlaySettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    // ── Service control ───────────────────────────────────────────────────────

    private fun toggleService() {
        if (serviceRunning) {
            stopService(Intent(this, FloatingBubbleService::class.java))
            serviceRunning = false
        } else {
            val intent = Intent(this, FloatingBubbleService::class.java).apply {
                action = FloatingBubbleService.ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            serviceRunning = true
            // Shrink to background so bubble is visible
            moveTaskToBack(true)
        }
        refreshPermissionUI()
    }
}
