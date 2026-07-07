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
                // Extract into a staging dir first and only swap it into place after it
                // verifies clean. If this download/extraction fails partway, whatever model
                // was already installed (if any) is left completely untouched.
                val stagingDir = File(dir.parentFile, dir.name + "_staging")
                stagingDir.deleteRecursively()
                stagingDir.mkdirs()

                val archiveFile = File(context.cacheDir, "model_download.tar.bz2")
                downloadFile(target.archiveUrl, archiveFile) { done, total ->
                    val pct = if (total > 0) ((done * 90) / total).toInt() else -1
                    onProgress(Progress("下載中…", pct))
                }
                extractTarBz2(archiveFile, stagingDir) { fraction, currentFile ->
                    onProgress(Progress("解壓縮中：$currentFile", (fraction * 100).toInt().coerceIn(0, 100)))
                }
                archiveFile.delete()
                if (target.engine == ModelConfig.ENGINE_QWEN3) verifyQwen3Files(stagingDir)

                dir.deleteRecursively()
                if (!stagingDir.renameTo(dir)) throw IOException("Failed to install extracted model into $dir")
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

    /**
     * Sanity-check that extraction actually produced usable model files. A truncated or
     * corrupt archive (e.g. from the multi-stream bzip2 issue above) can otherwise leave
     * empty/partial files in place that only fail much later — as a native abort when
     * onnxruntime tries to parse them during model load.
     */
    private fun verifyQwen3Files(dir: File) {
        val required = listOf(
            File(dir, ModelConfig.QWEN3_ASR_CONV_FRONTEND),
            File(dir, ModelConfig.QWEN3_ASR_ENCODER),
            File(dir, ModelConfig.QWEN3_ASR_DECODER),
        )
        val broken = required.filter { !it.exists() || it.length() == 0L }
        val tokenizerDir = File(dir, ModelConfig.QWEN3_ASR_TOKENIZER_DIR)
        if (broken.isNotEmpty() || !tokenizerDir.isDirectory || tokenizerDir.listFiles().isNullOrEmpty()) {
            throw IOException(
                "模型檔案不完整或已損毀（${broken.joinToString { it.name }.ifEmpty { "tokenizer/" }}），請重新下載"
            )
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
                    val buf = ByteArray(COPY_BUFFER_SIZE)
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

    /**
     * Extracts entries into [targetDir], stripping each entry's first path component.
     * [onProgress] receives the fraction (0f..1f) of the *compressed* archive consumed so far,
     * plus the name of the file currently being written. Reported per chunk (not per whole
     * file) so a single very large file (e.g. the encoder) doesn't leave the UI looking frozen
     * for however long that one file takes to decompress — decompression is CPU-bound and can
     * take a long time on-device.
     */
    private fun extractTarBz2(archiveFile: File, targetDir: File, onProgress: (Float, String) -> Unit) {
        val archiveSize = archiveFile.length().coerceAtLeast(1L)
        val counting = CountingInputStream(BufferedInputStream(archiveFile.inputStream(), COPY_BUFFER_SIZE))
        var lastReportedBytes = 0L
        val reportEveryBytes = 128 * 1024L // throttle UI updates to ~every 128KB consumed
        counting.use { fis ->
            // decompressConcatenated=true: some tools (e.g. pbzip2) emit multi-stream bzip2
            // archives. Without this flag, BZip2CompressorInputStream silently stops after the
            // first stream, truncating the tar — files further into the archive (like the
            // decoder) end up corrupt/incomplete even though the download itself succeeded.
            BZip2CompressorInputStream(fis, true).use { bzIn ->
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
                                var copied = 0L
                                FileOutputStream(outFile).use { out ->
                                    val buf = ByteArray(COPY_BUFFER_SIZE)
                                    while (true) {
                                        val n = tarIn.read(buf)
                                        if (n < 0) break
                                        out.write(buf, 0, n)
                                        copied += n
                                        if (counting.bytesRead - lastReportedBytes >= reportEveryBytes) {
                                            lastReportedBytes = counting.bytesRead
                                            onProgress(counting.bytesRead.toFloat() / archiveSize, relPath)
                                        }
                                    }
                                }
                                if (copied != entry.size) {
                                    throw IOException(
                                        "解壓縮失敗：${relPath} 大小不符（預期 ${entry.size}，實際 $copied），封存檔可能已損毀"
                                    )
                                }
                            }
                        }
                        lastReportedBytes = counting.bytesRead
                        onProgress(counting.bytesRead.toFloat() / archiveSize, relPath)
                        entry = tarIn.nextTarEntry
                    }
                }
            }
        }
    }

    /** Wraps a stream and tracks total bytes read through it. */
    private class CountingInputStream(private val delegate: java.io.InputStream) : java.io.InputStream() {
        var bytesRead = 0L
            private set

        override fun read(): Int {
            val b = delegate.read()
            if (b >= 0) bytesRead++
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = delegate.read(b, off, len)
            if (n > 0) bytesRead += n
            return n
        }

        override fun close() = delegate.close()
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

        // Larger than the JDK's 8KB default to cut down on read()/write() syscall counts for
        // large model files; still small enough to not matter for memory.
        private const val COPY_BUFFER_SIZE = 256 * 1024

        fun formatBytes(bytes: Long): String = when {
            bytes < 0             -> "大小未知"
            bytes < 1024 * 1024   -> "%.0f KB".format(bytes / 1024.0)
            else                  -> "%.0f MB".format(bytes / (1024.0 * 1024.0))
        }
    }
}
