package com.ping.voiceim.engine

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * Downloads and installs ASR model files over the network. Model files
 * themselves are only ever read on-device afterwards — recognition never
 * makes a network call.
 */
class ModelDownloader(private val context: Context) {

    data class Progress(val label: String, val percent: Int) // percent < 0 = indeterminate

    /** Sum of Content-Length across all remote files, or -1 if unknown. */
    suspend fun estimateTotalBytes(target: ModelDownloadSpec.DownloadTarget): Long =
        withContext(Dispatchers.IO) {
            val urls = target.archiveUrl?.let { listOf(it) } ?: target.files.map { it.url }
            var total = 0L
            for (url in urls) {
                val len = runCatching { headContentLength(url) }.getOrDefault(-1L)
                if (len < 0) return@withContext -1L
                total += len
            }
            total
        }

    suspend fun download(
        target: ModelDownloadSpec.DownloadTarget,
        onProgress: (Progress) -> Unit,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val dir = engineDir(target.engine)
            dir.mkdirs()

            if (target.archiveUrl != null) {
                val archiveFile = File(context.cacheDir, "model_download.tar.bz2")
                downloadFile(target.archiveUrl, archiveFile) { done, total ->
                    val pct = if (total > 0) ((done * 90) / total).toInt() else -1
                    onProgress(Progress("下載中…", pct))
                }
                onProgress(Progress("解壓縮中…", -1))
                extractTarBz2(archiveFile, dir)
                archiveFile.delete()
            } else {
                val n = target.files.size
                target.files.forEachIndexed { i, f ->
                    downloadFile(f.url, File(dir, f.relativePath)) { done, total ->
                        val filePct = if (total > 0) (done * 100 / total).toInt() else 0
                        onProgress(Progress("下載中 (${i + 1}/$n)…", (i * 100 + filePct) / n))
                    }
                }
            }
            Result.success(Unit)
        } catch (ex: Exception) {
            Log.e(TAG, "Download failed: ${ex.message}", ex)
            Result.failure(ex)
        }
    }

    private fun engineDir(engine: String): File =
        File(if (engine == ModelConfig.ENGINE_X_ASR) ModelConfig.xAsrDir(context) else ModelConfig.qwen3AsrDir(context))

    private suspend fun headContentLength(url: String): Long = withContext(Dispatchers.IO) {
        val conn = openConnection(url, "HEAD")
        try {
            if (conn.responseCode !in 200..299) return@withContext -1L
            conn.contentLengthLong
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun downloadFile(
        url: String,
        dest: File,
        onProgress: (done: Long, total: Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val conn = openConnection(url, "GET")
        try {
            if (conn.responseCode !in 200..299) {
                throw IOException("HTTP ${conn.responseCode} for $url")
            }
            val total = conn.contentLengthLong
            dest.parentFile?.mkdirs()
            val tmp = File(dest.parentFile, dest.name + ".part")
            conn.inputStream.use { input ->
                FileOutputStream(tmp).use { output ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (coroutineContext.isActive) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                }
            }
            if (!coroutineContext.isActive) {
                tmp.delete()
                throw IOException("Download cancelled")
            }
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) throw IOException("Failed to move downloaded file into place: $dest")
        } finally {
            conn.disconnect()
        }
    }

    /** Extracts entries into [targetDir], stripping each entry's first path component. */
    private fun extractTarBz2(archiveFile: File, targetDir: File) {
        BufferedInputStream(archiveFile.inputStream()).use { fis ->
            BZip2CompressorInputStream(fis).use { bzIn ->
                TarArchiveInputStream(bzIn).use { tarIn ->
                    var entry = tarIn.nextTarEntry
                    while (entry != null) {
                        // Strip the archive's top-level wrapper folder if present;
                        // fall back to the raw name for flat (non-wrapped) archives.
                        val slash = entry.name.indexOf('/')
                        val relPath = if (slash >= 0) entry.name.substring(slash + 1) else entry.name
                        if (relPath.isNotEmpty()) {
                            val outFile = File(targetDir, relPath)
                            if (entry.isDirectory) {
                                outFile.mkdirs()
                            } else {
                                outFile.parentFile?.mkdirs()
                                FileOutputStream(outFile).use { out -> tarIn.copyTo(out) }
                            }
                        }
                        entry = tarIn.nextTarEntry
                    }
                }
            }
        }
    }

    private fun openConnection(url: String, method: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.connect()
        return conn
    }

    companion object {
        private const val TAG = "ModelDownloader"

        fun formatBytes(bytes: Long): String = when {
            bytes < 0             -> "大小未知"
            bytes < 1024 * 1024   -> "%.0f KB".format(bytes / 1024.0)
            else                  -> "%.0f MB".format(bytes / (1024.0 * 1024.0))
        }
    }
}
