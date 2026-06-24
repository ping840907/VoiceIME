package com.ping.elderlyassistant.engine

/**
 * Archived — replaced by SenseVoiceEngine (sherpa-onnx) in Phase 2.
 *
 * Whisper.cpp via JNI was the original ASR backend. Replaced because:
 *   • SenseVoice-Small is ~15× faster at similar accuracy
 *   • sherpa-onnx ships a prebuilt AAR; no custom NDK/CMake build needed
 *
 * This file is kept as an empty stub so git history is preserved.
 * Do NOT add code here — all ASR is handled by SenseVoiceEngine.
 */
@Deprecated("Use SenseVoiceEngine instead", level = DeprecationLevel.ERROR)
private class WhisperEngine
