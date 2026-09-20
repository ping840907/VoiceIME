package com.ping.voiceime

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ping.voiceime.engine.ModelConfig
import com.ping.voiceime.engine.ModelDownloadSpec
import com.ping.voiceime.engine.ModelDownloader
import com.ping.voiceime.engine.XAsrEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
class SherpaOnnxTest {

    companion object {
        private const val TAG = "SherpaOnnxTest"
    }

    private fun readWavPcm16(file: File): FloatArray {
        val bytes = file.readBytes()
        val pcmOffset = 44
        val pcmLength = bytes.size - pcmOffset
        val sampleCount = pcmLength / 2
        val floats = FloatArray(sampleCount)
        val bb = ByteBuffer.wrap(bytes, pcmOffset, pcmLength).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until sampleCount) {
            floats[i] = bb.short.toFloat() / 32768.0f
        }
        return floats
    }

    @Test
    fun testSherpaOnnxJniLoad() {
        Log.i(TAG, "Testing JNI loading of sherpa-onnx 1.13.8...")
        com.k2fsa.sherpa.onnx.OfflineRecognizer.prependAdspLibraryPath("")
        com.k2fsa.sherpa.onnx.OnlineRecognizer.prependAdspLibraryPath("")
        Log.i(TAG, "sherpa-onnx 1.13.8 JNI libraries loaded successfully!")
    }

    @Test
    fun testXAsrEngineWithRealAudio() {
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val xAsr = XAsrEngine(context)

            val encoder = File(ModelConfig.xAsrEncoderPath(context))
            if (!encoder.exists()) {
                Log.i(TAG, "Downloading X-ASR model...")
                val downloader = ModelDownloader(context)
                val res = downloader.download(ModelDownloadSpec.xAsr()) { p ->
                    Log.i(TAG, "Download progress: ${p.label} ${p.percent}%")
                }
                assertTrue("Model download should succeed: ${res.exceptionOrNull()?.message}", res.isSuccess)
            }

            Log.i(TAG, "Testing XAsrEngine with sherpa-onnx 1.13.8...")
            val loadResult = xAsr.load()
            Log.i(TAG, "XAsr load result: success=${loadResult.success}, provider=${loadResult.provider}, error=${loadResult.error}")
            assertTrue("XAsrEngine should load successfully: ${loadResult.error}", loadResult.success)

            val stream = xAsr.createStream()
            assertNotNull("Stream should not be null", stream)

            val wavFile = File(context.filesDir, "test.wav")
            assertTrue("test.wav should exist", wavFile.exists())

            val samples = readWavPcm16(wavFile)
            Log.i(TAG, "Feeding ${samples.size} samples (${samples.size / 16000f}s) to XAsr...")

            // Feed audio in 0.1s chunks (1600 samples)
            val chunkSize = 1600
            var offset = 0
            while (offset < samples.size) {
                val end = minOf(offset + chunkSize, samples.size)
                val chunk = samples.copyOfRange(offset, end)
                xAsr.acceptWaveform(stream!!, chunk)
                while (xAsr.isReady(stream)) {
                    xAsr.decode(stream)
                }
                offset = end
            }
            xAsr.inputFinished(stream!!)
            while (xAsr.isReady(stream)) {
                xAsr.decode(stream)
            }

            val text = xAsr.getResult(stream)
            Log.i(TAG, ">>> XAsr recognized text: '$text' <<<")
            assertTrue("XAsr should recognize text from test.wav", text.isNotBlank())

            xAsr.release()
            Log.i(TAG, "XAsrEngine real audio test completed successfully!")
        }
    }
}
