package com.ping.elderlyassistant

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Setup screen — guides the user through four one-time steps before the
 * floating bubble can be started:
 *   1. RECORD_AUDIO                  — runtime permission (shows system dialog)
 *   2. SYSTEM_ALERT_WINDOW           — overlay permission (Settings page)
 *   3. Accessibility service         — Settings page
 *   4. Battery optimization exemption — prevents the OS from killing the service
 *
 * Once all four are granted, the "啟動語音助理" button starts FloatingBubbleService.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var tvMicStatus:           TextView
    private lateinit var tvOverlayStatus:       TextView
    private lateinit var tvAccessibilityStatus: TextView
    private lateinit var tvBatteryStatus:       TextView
    private lateinit var tvOverallStatus:       TextView
    private lateinit var btnMic:                Button
    private lateinit var btnOverlay:            Button
    private lateinit var btnAccessibility:      Button
    private lateinit var btnBattery:            Button
    private lateinit var btnToggleService:      Button

    private var serviceRunning = false

    // Runtime permission launcher
    private val requestMicPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            refreshPermissionUI()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvMicStatus           = findViewById(R.id.tv_mic_status)
        tvOverlayStatus       = findViewById(R.id.tv_overlay_status)
        tvAccessibilityStatus = findViewById(R.id.tv_accessibility_status)
        tvBatteryStatus       = findViewById(R.id.tv_battery_status)
        tvOverallStatus       = findViewById(R.id.tv_overall_status)
        btnMic                = findViewById(R.id.btn_mic_permission)
        btnOverlay            = findViewById(R.id.btn_overlay_permission)
        btnAccessibility      = findViewById(R.id.btn_accessibility_permission)
        btnBattery            = findViewById(R.id.btn_battery_permission)
        btnToggleService      = findViewById(R.id.btn_toggle_service)

        btnMic.setOnClickListener {
            requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
        btnOverlay.setOnClickListener { openOverlaySettings() }
        btnAccessibility.setOnClickListener { openAccessibilitySettings() }
        btnBattery.setOnClickListener { openBatterySettings() }
        btnToggleService.setOnClickListener { toggleService() }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionUI()
    }

    // ── Permission checks ─────────────────────────────────────────────────────

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

    private fun hasOverlayPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(this)
        else true

    private fun hasAccessibilityPermission(): Boolean {
        val am = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.packageName == packageName }
    }

    private fun hasBatteryOptimizationExemption(): Boolean {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    // ── UI refresh ────────────────────────────────────────────────────────────

    private fun refreshPermissionUI() {
        val micOk      = hasMicPermission()
        val overlayOk  = hasOverlayPermission()
        val accessOk   = hasAccessibilityPermission()
        val batteryOk  = hasBatteryOptimizationExemption()
        val allOk      = micOk && overlayOk && accessOk && batteryOk

        setStatus(tvMicStatus, btnMic, micOk, "已授權", "未授權", "授予麥克風權限")
        setStatus(tvOverlayStatus, btnOverlay, overlayOk, "已授權", "未授權", "前往授權")
        setStatus(tvAccessibilityStatus, btnAccessibility, accessOk, "已啟用", "未啟用", "前往啟用")
        setStatus(tvBatteryStatus, btnBattery, batteryOk, "已豁免", "未豁免", "前往設定")

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

    private fun setStatus(
        tv: TextView, btn: Button, granted: Boolean,
        grantedLabel: String, missingLabel: String, btnLabel: String
    ) {
        tv.text = if (granted) grantedLabel else missingLabel
        tv.setTextColor(
            if (granted) getColor(R.color.permission_granted)
            else getColor(R.color.permission_missing)
        )
        btn.text = if (granted) "已完成 ✓" else btnLabel
        btn.isEnabled = !granted
    }

    // ── Permission navigation ─────────────────────────────────────────────────

    private fun openOverlaySettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"))
            )
        }
    }

    private fun openAccessibilitySettings() =
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))

    private fun openBatterySettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            runCatching {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName"))
                )
            }.onFailure {
                // Fallback: open general battery settings if direct intent is blocked by OEM
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
            else startService(intent)
            serviceRunning = true
            moveTaskToBack(true)
        }
        refreshPermissionUI()
    }
}
