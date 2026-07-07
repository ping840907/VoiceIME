package com.ping.voiceim

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.ping.voiceim.engine.ModelConfig
import com.ping.voiceim.engine.ModelDownloadSpec
import com.ping.voiceim.engine.ModelDownloadState
import com.ping.voiceim.engine.ModelDownloader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Runs model downloads as a foreground service so they keep going when the
 * screen turns off or the user switches to another app — a plain
 * Activity-scoped coroutine would be paused/killed by the system in those
 * cases, but a foreground service (with its notification) is protected.
 */
class ModelDownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private lateinit var downloader: ModelDownloader
    private lateinit var notificationManager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        downloader = ModelDownloader(this)
        notificationManager = getSystemService(NotificationManager::class.java)
        createChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                stopDownload()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val engine = intent.getStringExtra(EXTRA_ENGINE) ?: return START_NOT_STICKY
                if (job?.isActive == true) return START_NOT_STICKY
                startDownload(engine)
                return START_NOT_STICKY
            }
            else -> return START_NOT_STICKY
        }
    }

    private fun startDownload(engine: String) {
        val target = ModelDownloadSpec.forEngine(engine)
        val engineLabel = if (engine == ModelConfig.ENGINE_X_ASR) "X-ASR" else "Qwen3-ASR"
        startForeground(NOTIF_ID, buildNotification("準備下載 $engineLabel 模型…", -1))

        job = scope.launch {
            val result = downloader.download(target) { progress ->
                ModelDownloadState.update(engine, progress)
                notificationManager.notify(NOTIF_ID, buildNotification("$engineLabel：${progress.label}", progress.percent))
            }
            ModelDownloadState.clear()
            ModelDownloadState.results.tryEmit(engine to result)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun stopDownload() {
        job?.cancel()
        ModelDownloadState.clear()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        job?.cancel()
        super.onDestroy()
    }

    private fun buildNotification(text: String, percent: Int) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("正在下載語音辨識模型")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_mic)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent.coerceIn(0, 100), percent < 0)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, ImeSettingsActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .build()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(CHANNEL_ID, "模型下載", NotificationManager.IMPORTANCE_LOW)
        notificationManager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "model_download"
        private const val NOTIF_ID = 1001
        private const val EXTRA_ENGINE = "engine"
        private const val ACTION_START = "com.ping.voiceim.action.START_DOWNLOAD"
        private const val ACTION_CANCEL = "com.ping.voiceim.action.CANCEL_DOWNLOAD"

        fun start(context: Context, engine: String) {
            val intent = Intent(context, ModelDownloadService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_ENGINE, engine)
            context.startForegroundService(intent)
        }

        fun cancel(context: Context) {
            context.startService(Intent(context, ModelDownloadService::class.java).setAction(ACTION_CANCEL))
        }
    }
}
