package com.ping.voiceime

import com.ping.voiceime.engine.ModelConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CircuitLogicTest {

    @Test
    fun testChinesePunctuationFilter() {
        val input = "你好，世界！這是一個測試：成功了嗎？「當然」～"
        val expected = "你好世界這是一個測試成功了嗎當然"
        val actual = ModelConfig.filterChinesePunctuation(input)
        assertEquals(expected, actual)
    }

    @Test
    fun testTaiwanTraditionalConversion() {
        val input = "看着刚才在家里里面"
        val expected = "看著剛才在家裡裡面"
        val actual = ModelConfig.toTaiwanTraditional(input)
        assertEquals(expected, actual)
    }

    @Test
    fun testTaiwanVariantsNormalization() {
        val input = "剛纔心裏着火了"
        val expected = "剛才心裡著火了"
        val actual = ModelConfig.normalizeTaiwanVariants(input)
        assertEquals(expected, actual)
    }

    @Test
    fun testUserDictionaryApply() {
        val dict = mapOf(
            "語音辨識" to "VoiceIME",
            "着" to "著",
            "通用詞" to "通用詞"
        )
        val input = "這是語音辨識測試，看着螢幕"
        val expected = "這是VoiceIME測試，看著螢幕"
        val actual = UserDictionary.apply(input, dict)
        assertEquals(expected, actual)
    }

    @Test
    fun testCircuitLogicScenarioA() {
        // 情境 A:
        // mic=true, IME ok, Bubble ok, X_ASR ok, QWEN3 ok, selected=X_ASR
        val micGranted = true
        val imeEnabled = true
        val imeDefault = true
        val overlayGranted = true
        val accessibilityEnabled = true
        val xAsrDownloaded = true
        val qwen3Downloaded = true
        val selectedEngine = ModelConfig.ENGINE_X_ASR
        var dualEngineToggle = false

        val keyboardModuleLine = micGranted && imeEnabled && imeDefault
        val bubbleModuleLine = micGranted && overlayGranted && accessibilityEnabled
        val inputPathReady = keyboardModuleLine || bubbleModuleLine

        val xAsrReadyLine = xAsrDownloaded
        val qwen3ReadyLine = qwen3Downloaded

        val dualEngineSelectable = xAsrDownloaded && qwen3Downloaded && (selectedEngine == ModelConfig.ENGINE_QWEN3)
        if (!dualEngineSelectable) {
            dualEngineToggle = false
        }
        val dualEngineLine = dualEngineSelectable && dualEngineToggle

        val currentEngineReady = (selectedEngine == ModelConfig.ENGINE_X_ASR && xAsrDownloaded) ||
                (selectedEngine == ModelConfig.ENGINE_QWEN3 && qwen3Downloaded)
        val vocabularyLine = inputPathReady && currentEngineReady

        assertTrue(keyboardModuleLine)
        assertTrue(bubbleModuleLine)
        assertTrue(xAsrReadyLine)
        assertTrue(qwen3ReadyLine)
        assertFalse(dualEngineSelectable)
        assertFalse(dualEngineToggle)
        assertFalse(dualEngineLine)
        assertTrue(vocabularyLine)
    }

    @Test
    fun testCircuitLogicScenarioB() {
        // 情境 B:
        // 同情境 A，但 selected_engine=QWEN3_ASR，且使用者開啟 dual_engine_toggle=true
        val micGranted = true
        val imeEnabled = true
        val imeDefault = true
        val overlayGranted = true
        val accessibilityEnabled = true
        val xAsrDownloaded = true
        val qwen3Downloaded = true
        val selectedEngine = ModelConfig.ENGINE_QWEN3
        var dualEngineToggle = true

        val keyboardModuleLine = micGranted && imeEnabled && imeDefault
        val bubbleModuleLine = micGranted && overlayGranted && accessibilityEnabled
        val inputPathReady = keyboardModuleLine || bubbleModuleLine

        val dualEngineSelectable = xAsrDownloaded && qwen3Downloaded && (selectedEngine == ModelConfig.ENGINE_QWEN3)
        val dualEngineLine = dualEngineSelectable && dualEngineToggle

        val currentEngineReady = (selectedEngine == ModelConfig.ENGINE_X_ASR && xAsrDownloaded) ||
                (selectedEngine == ModelConfig.ENGINE_QWEN3 && qwen3Downloaded)
        val vocabularyLine = inputPathReady && currentEngineReady

        assertTrue(dualEngineSelectable)
        assertTrue(dualEngineToggle)
        assertTrue(dualEngineLine)
        assertTrue(vocabularyLine)
    }

    @Test
    fun testCircuitLogicScenarioC() {
        // 情境 C:
        // 使用者僅下載 Qwen3，但 selected_engine=X_ASR
        val micGranted = true
        val imeEnabled = true
        val imeDefault = true
        val overlayGranted = true
        val accessibilityEnabled = true
        val xAsrDownloaded = false
        val qwen3Downloaded = true
        val selectedEngine = ModelConfig.ENGINE_X_ASR

        val keyboardModuleLine = micGranted && imeEnabled && imeDefault
        val bubbleModuleLine = micGranted && overlayGranted && accessibilityEnabled
        val inputPathReady = keyboardModuleLine || bubbleModuleLine

        val xAsrReadyLine = xAsrDownloaded
        val qwen3ReadyLine = qwen3Downloaded

        val currentEngineReady = (selectedEngine == ModelConfig.ENGINE_X_ASR && xAsrDownloaded) ||
                (selectedEngine == ModelConfig.ENGINE_QWEN3 && qwen3Downloaded)
        val vocabularyLine = inputPathReady && currentEngineReady

        assertFalse(xAsrReadyLine)
        assertTrue(qwen3ReadyLine)
        assertFalse(currentEngineReady)
        assertFalse(vocabularyLine)
    }

    @Test
    fun testCircuitLogicScenarioD() {
        // 情境 D:
        // mic_permission_granted=false
        val micGranted = false
        val imeEnabled = true
        val imeDefault = true
        val overlayGranted = true
        val accessibilityEnabled = true
        val xAsrDownloaded = true
        val qwen3Downloaded = true
        val selectedEngine = ModelConfig.ENGINE_X_ASR

        val keyboardModuleLine = micGranted && imeEnabled && imeDefault
        val bubbleModuleLine = micGranted && overlayGranted && accessibilityEnabled
        val inputPathReady = keyboardModuleLine || bubbleModuleLine

        val currentEngineReady = (selectedEngine == ModelConfig.ENGINE_X_ASR && xAsrDownloaded) ||
                (selectedEngine == ModelConfig.ENGINE_QWEN3 && qwen3Downloaded)
        val vocabularyLine = inputPathReady && currentEngineReady

        assertFalse(keyboardModuleLine)
        assertFalse(bubbleModuleLine)
        assertFalse(inputPathReady)
        assertFalse(vocabularyLine)
    }
}
