package com.ping.voiceim

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.RadioGroup
import android.widget.SeekBar
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

        // Engine selection
        val rg = findViewById<RadioGroup>(R.id.rg_engine)
        val currentEngine = ModelConfig.selectedEngine(this)
        rg.check(if (currentEngine == ModelConfig.ENGINE_X_ASR) R.id.rb_xasr else R.id.rb_qwen3)
        rg.setOnCheckedChangeListener { _, checkedId ->
            val engine = if (checkedId == R.id.rb_xasr) ModelConfig.ENGINE_X_ASR else ModelConfig.ENGINE_QWEN3
            ModelConfig.setSelectedEngine(this, engine)
            updateModelStatus()
        }

        // VAD silence duration slider (0.5s .. 3.0s in 0.1s steps)
        val sbVad   = findViewById<SeekBar>(R.id.sb_vad_silence)
        val tvVad   = findViewById<TextView>(R.id.tv_vad_value)
        fun vadLabel(seconds: Float) = "靜音 %.1f 秒後自動停止".format(seconds)
        val currentVad = ModelConfig.vadSilenceSeconds(this)
        sbVad.progress = (((currentVad - ModelConfig.VAD_SILENCE_MIN) / 0.1f).toInt())
        tvVad.text = vadLabel(currentVad)
        sbVad.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val seconds = ModelConfig.VAD_SILENCE_MIN + progress * 0.1f
                tvVad.text = vadLabel(seconds)
                if (fromUser) ModelConfig.setVadSilenceSeconds(this@ImeSettingsActivity, seconds)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

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
        val engine = ModelConfig.selectedEngine(this)
        tv.text = if (engine == ModelConfig.ENGINE_X_ASR) buildXAsrStatus() else buildQwen3Status()
    }

    private fun buildQwen3Status(): String {
        val dir = ModelConfig.qwen3AsrDir(this)
        val files = listOf(
            ModelConfig.qwen3AsrConvFrontendPath(this),
            ModelConfig.qwen3AsrEncoderPath(this),
            ModelConfig.qwen3AsrDecoderPath(this),
        )
        val missing = files.filter { !File(it).exists() }
        return if (missing.isEmpty() && File(ModelConfig.qwen3AsrTokenizerDir(this)).isDirectory) {
            "✓ Qwen3-ASR 模型已就緒\n路徑: $dir"
        } else buildString {
            append("⚠ Qwen3-ASR 模型未找到，請放置於：\n$dir\n\n")
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

    private fun buildXAsrStatus(): String {
        val dir = ModelConfig.xAsrDir(this)
        val files = listOf(
            ModelConfig.xAsrEncoderPath(this),
            ModelConfig.xAsrDecoderPath(this),
            ModelConfig.xAsrJoinerPath(this),
            ModelConfig.xAsrTokensPath(this),
        )
        val missing = files.filter { !File(it).exists() }
        return if (missing.isEmpty()) {
            "✓ X-ASR 模型已就緒\n路徑: $dir"
        } else buildString {
            append("⚠ X-ASR 模型未找到，請放置於：\n$dir\n\n")
            append("需要的檔案：\n")
            append("  encoder.int8.onnx\n")
            append("  decoder.onnx\n")
            append("  joiner.int8.onnx\n")
            append("  tokens.txt\n\n")
            append("下載來源：\n")
            append("https://huggingface.co/Luigi/x-asr-zh-tw-en-streaming-ft75m")
        }
    }
}
