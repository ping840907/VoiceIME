package com.ping.voiceim

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.ping.voiceim.engine.ModelConfig
import java.io.File

class ImeSettingsActivity : AppCompatActivity() {

    private val requestMic = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        updateMicStatus()
        if (!granted) {
            findViewById<TextView>(R.id.tv_mic_status).text =
                "⚠ 麥克風權限遭拒，請至應用程式設定手動授予"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        findViewById<Button>(R.id.btn_grant_mic).setOnClickListener {
            requestMic.launch(Manifest.permission.RECORD_AUDIO)
        }

        findViewById<Button>(R.id.btn_enable_ime).setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }

        findViewById<Button>(R.id.btn_switch_ime).setOnClickListener {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        }

        updateModelStatus()
        updateMicStatus()
    }

    override fun onResume() {
        super.onResume()
        updateModelStatus()
        updateMicStatus()
    }

    private fun updateMicStatus() {
        val tv = findViewById<TextView>(R.id.tv_mic_status)
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        tv.text = if (granted) "✓ 麥克風權限已授予" else "⚠ 尚未授予麥克風權限"
    }

    private fun updateModelStatus() {
        val tv = findViewById<TextView>(R.id.tv_model_status)
        val dir = ModelConfig.qwen3AsrDir(this)
        val files = listOf(
            ModelConfig.qwen3AsrConvFrontendPath(this),
            ModelConfig.qwen3AsrEncoderPath(this),
            ModelConfig.qwen3AsrDecoderPath(this),
        )
        val missing = files.filter { !File(it).exists() }
        if (missing.isEmpty() && File(ModelConfig.qwen3AsrTokenizerDir(this)).isDirectory) {
            tv.text = "✓ 模型已就緒\n路徑: $dir"
        } else {
            tv.text = buildString {
                append("⚠ 模型檔案未找到，請將模型放置於：\n$dir\n\n")
                append("需要的檔案：\n")
                append("  conv_frontend.onnx\n")
                append("  encoder.int8.onnx\n")
                append("  decoder.int8.onnx\n")
                append("  tokenizer/  (目錄)\n\n")
                append("下載來源：\n")
                append("https://github.com/k2-fsa/sherpa-onnx/releases\n")
                append("(搜尋 sherpa-onnx-qwen3-asr-0.6B-int8)")
            }
        }
    }
}
