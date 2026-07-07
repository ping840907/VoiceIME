package com.ping.voiceim

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.ping.voiceim.engine.ModelConfig
import com.ping.voiceim.engine.ModelDownloadSpec
import com.ping.voiceim.engine.ModelDownloadState
import com.ping.voiceim.engine.ModelDownloader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

class ImeSettingsActivity : AppCompatActivity() {

    // Only used for the lightweight "estimate size" HEAD check and for observing
    // ModelDownloadService's progress while this screen is visible — the actual
    // download runs in the service and is unaffected by this scope's lifecycle.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val downloader by lazy { ModelDownloader(this) }
    private var observeJob: Job? = null

    private lateinit var btnDownloadModel: Button
    private lateinit var tvDownloadStatus: TextView
    private lateinit var progressDownload: ProgressBar

    private val requestMic = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        updateMicStatus()
        if (!granted) {
            findViewById<TextView>(R.id.tv_mic_status).text =
                "⚠ 麥克風權限遭拒，請至應用程式設定手動授予"
        }
    }

    private val requestNotifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* proceed regardless — download still runs, only the progress notification depends on this */
        beginDownload()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        btnDownloadModel = findViewById(R.id.btn_download_model)
        tvDownloadStatus = findViewById(R.id.tv_download_status)
        progressDownload = findViewById(R.id.progress_download)

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

        findViewById<Button>(R.id.btn_open_dict).setOnClickListener {
            startActivity(Intent(this, DictSettingsActivity::class.java))
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

        btnDownloadModel.setOnClickListener {
            if (ModelDownloadState.active.value != null) cancelDownload() else confirmAndDownload()
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

    override fun onStart() {
        super.onStart()
        // Reflect whatever ModelDownloadService is already doing (survives this
        // Activity being recreated / having been away while a download ran).
        observeJob = scope.launch {
            launch {
                ModelDownloadState.active.collect { active ->
                    if (active != null) showDownloadProgress(active.progress) else hideDownloadProgress()
                }
            }
            launch {
                ModelDownloadState.results.collect { (_, result) ->
                    hideDownloadProgress()
                    result.onSuccess {
                        Toast.makeText(this@ImeSettingsActivity, "模型下載完成", Toast.LENGTH_LONG).show()
                    }.onFailure { ex ->
                        Toast.makeText(this@ImeSettingsActivity, "下載失敗：${ex.message}", Toast.LENGTH_LONG).show()
                    }
                    updateModelStatus()
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
        updateModelStatus()
        updateMicStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.coroutineContext[Job]?.cancel()
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
        val ready = isModelReady(engine)
        tv.text = if (ready) buildReadyStatus(engine) else buildMissingStatus(engine)

        val downloading = ModelDownloadState.active.value != null
        if (!downloading) {
            btnDownloadModel.isEnabled = true
            btnDownloadModel.text = if (ready) "重新下載模型" else "下載模型"
        }
    }

    private fun isModelReady(engine: String): Boolean = if (engine == ModelConfig.ENGINE_X_ASR) {
        listOf(
            ModelConfig.xAsrEncoderPath(this),
            ModelConfig.xAsrDecoderPath(this),
            ModelConfig.xAsrJoinerPath(this),
            ModelConfig.xAsrTokensPath(this),
        ).all { File(it).exists() }
    } else {
        listOf(
            ModelConfig.qwen3AsrConvFrontendPath(this),
            ModelConfig.qwen3AsrEncoderPath(this),
            ModelConfig.qwen3AsrDecoderPath(this),
        ).all { File(it).exists() } && File(ModelConfig.qwen3AsrTokenizerDir(this)).isDirectory
    }

    private fun buildReadyStatus(engine: String): String {
        val dir = if (engine == ModelConfig.ENGINE_X_ASR) ModelConfig.xAsrDir(this) else ModelConfig.qwen3AsrDir(this)
        val name = if (engine == ModelConfig.ENGINE_X_ASR) "X-ASR" else "Qwen3-ASR"
        return "✓ $name 模型已就緒\n路徑: $dir"
    }

    private fun buildMissingStatus(engine: String): String = if (engine == ModelConfig.ENGINE_X_ASR) {
        "⚠ X-ASR 模型尚未下載\n路徑: ${ModelConfig.xAsrDir(this)}\n\n點擊下方「下載模型」自動安裝，或參閱 README 手動放置。"
    } else {
        "⚠ Qwen3-ASR 模型尚未下載\n路徑: ${ModelConfig.qwen3AsrDir(this)}\n\n點擊下方「下載模型」自動安裝，或參閱 README 手動放置。"
    }

    private fun confirmAndDownload() {
        val engine = ModelConfig.selectedEngine(this)
        val target = ModelDownloadSpec.forEngine(engine)

        btnDownloadModel.isEnabled = false
        btnDownloadModel.text = "檢查檔案大小…"

        scope.launch {
            val bytes = downloader.estimateTotalBytes(target)
            btnDownloadModel.isEnabled = true
            btnDownloadModel.text = if (isModelReady(engine)) "重新下載模型" else "下載模型"

            AlertDialog.Builder(this@ImeSettingsActivity)
                .setTitle("下載模型")
                .setMessage(
                    "即將下載約 ${ModelDownloader.formatBytes(bytes)} 的模型檔案，下載會在背景繼續進行，" +
                        "關閉螢幕或切換到其他 App 不會中斷。\n" +
                        "辨識過程仍完全在裝置本機執行，僅此下載步驟需要網路連線（可能產生行動數據流量費用）。\n\n是否繼續？"
                )
                .setPositiveButton("開始下載") { _, _ -> requestNotificationsThenDownload() }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun requestNotificationsThenDownload() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            beginDownload()
        }
    }

    private fun beginDownload() {
        val engine = ModelConfig.selectedEngine(this)
        ModelDownloadService.start(this, engine)
    }

    private fun showDownloadProgress(progress: ModelDownloader.Progress) {
        btnDownloadModel.isEnabled = true
        btnDownloadModel.text = "取消下載"
        tvDownloadStatus.visibility = TextView.VISIBLE
        progressDownload.visibility = ProgressBar.VISIBLE
        progressDownload.isIndeterminate = progress.percent < 0
        if (progress.percent >= 0) progressDownload.progress = progress.percent
        tvDownloadStatus.text = if (progress.percent >= 0) "${progress.label} ${progress.percent}%" else progress.label
    }

    private fun hideDownloadProgress() {
        tvDownloadStatus.visibility = TextView.GONE
        progressDownload.visibility = ProgressBar.GONE
    }

    private fun cancelDownload() {
        ModelDownloadService.cancel(this)
        hideDownloadProgress()
        updateModelStatus()
    }
}
