cmake_minimum_required(VERSION 3.22.1)
project(elderlyassistant CXX C)

# ── Phase 2 note ──────────────────────────────────────────────────────────────
# ASR has moved to SenseVoice via sherpa-onnx AAR (no custom NDK build needed).
# This CMakeLists.txt is retained as a stub for future custom native code,
# e.g. VAD with WebRTC, custom GPU kernels, or other JNI extensions.
#
# The whisper.cpp JNI bridge is archived at:
#   app/src/main/cpp/whisper_jni_archive.cpp
# To re-enable it, restore the file and add the whisper_lib build rules back.
# ─────────────────────────────────────────────────────────────────────────────

# Placeholder: add future native sources here
# add_library(custom_native SHARED custom_native.cpp)
# target_link_libraries(custom_native PRIVATE android log)
