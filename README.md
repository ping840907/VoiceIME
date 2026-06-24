# 語音助理

Android 上的離線語音操控助理。說一句話，助理就能替你點按、輸入、開啟 App，全程不需要網路。

## 功能概覽

- **語音輸入**：按下麥克風按鈕說話，自動辨識後送給 AI 執行
- **文字輸入**：切換鍵盤模式，直接打字下指令
- **多步驟自動化**：一條指令可跨畫面連續操作（上限 8 步、30 秒逾時）
- **完全離線**：ASR 與 LLM 均在裝置本機執行，語音不上傳
- **懸浮氣泡**：常駐於所有 App 上方，隨時呼叫
- **開機自啟**：裝置重開機後自動恢復運行
- **安全保護**：執行期間顯示攔截遮罩；金融、OTP、密碼管理 App 一律拒絕自動操作

---

## 架構

```
使用者語音 / 文字
        │
        ▼
 SenseVoice-Small (ASR)      ← sherpa-onnx，純離線，支援中文 / 台語 / 粵語
        │
        ▼
  Qwen3-1.7B (LLM)           ← mlc4j / MLC LLM，OpenCL GPU 加速
        │ JSON action
        ▼
 AccessibilityService         ← 讀取畫面節點、執行點按 / 輸入 / 滑動
        │
        ▼
   目標 App 操作完成
```

### 主要元件

| 元件 | 說明 |
|------|------|
| `FloatingBubbleService` | 懸浮氣泡前景服務，管理氣泡與展開面板 UI |
| `PipelineOrchestrator` | 協調錄音 → 轉譯 → LLM → 執行的完整流程 |
| `SenseVoiceEngine` | sherpa-onnx ASR 封裝，NNAPI → CPU 備援 |
| `MlcLlmEngine` | mlc4j LLM 封裝，NPU → OpenCL GPU → CPU 備援 |
| `ActionExecutor` | 將 LLM JSON 動作轉換為 Accessibility 操作 |
| `AssistantAccessibilityService` | 讀取畫面節點樹、執行手勢與文字輸入 |
| `NodeSerializer` | 將無障礙節點樹壓縮為 LLM 可用的文字格式 |
| `BlockingOverlay` | 執行期間全螢幕觸控攔截遮罩 |
| `AutomationGuard` | 多步驟安全限制（步數上限、逾時） |
| `AppBlacklist` | 禁止自動操作的敏感 App 清單 |

---

## 需求

- Android 8.0（API 26）以上
- 建議：Snapdragon 或 MediaTek 旗艦機，RAM ≥ 6 GB（Qwen3-1.7B 約佔 1.1 GB）
- 開發環境：Android Studio Hedgehog 以上、JDK 17

---

## 模型檔案準備

所有模型放在 App 的外部專用儲存空間，**不需要** `READ_EXTERNAL_STORAGE` 權限。

### SenseVoice ASR

```
/sdcard/Android/data/com.ping.elderlyassistant[.debug]/files/models/
└── sense_voice/
    ├── model.int8.onnx   (~234 MB)
    └── tokens.txt
```

下載來源：[sherpa-onnx ASR 模型釋出頁](https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models)
→ `sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17.tar.bz2`
→ 解壓後將 `model.onnx` 重新命名為 `model.int8.onnx`（或直接使用 int8 量化版本）

```bash
adb push sense_voice/ \
  /sdcard/Android/data/com.ping.elderlyassistant.debug/files/models/
```

### Qwen3-1.7B LLM

```
/sdcard/Android/data/com.ping.elderlyassistant[.debug]/files/models/
└── Qwen3-1.7B-q4f16_1/
    ├── mlc-chat-config.json
    ├── ndarray-cache.json
    └── params_shard_*.bin   (~1.1 GB 合計)
```

模型權重可從 MLC LLM 預先量化的釋出版本取得，或自行以 `mlc_llm compile` 編譯。

```bash
adb push Qwen3-1.7B-q4f16_1/ \
  /sdcard/Android/data/com.ping.elderlyassistant.debug/files/models/
```

---

## 推理加速層

### ASR（ONNX Runtime）

| 優先順序 | Provider | 說明 |
|---------|----------|------|
| 1 | `nnapi` | Android NNAPI，自動路由至 NPU / DSP / GPU |
| 2 | `cpu` | 純軟體，所有裝置均可用 |

### LLM（MLC LLM mlc4j）

| 優先順序 | .so 名稱 | 說明 |
|---------|---------|------|
| 1 | `Qwen3_1_7B_q4f16_1_qnn` | Qualcomm Hexagon NPU（需自行以 QNN SDK 編譯） |
| 2 | `Qwen3_1_7B_q4f16_1_mtk` | MediaTek APU（需 NeuroPilot SDK，不公開） |
| 3 | `Qwen3_1_7B_q4f16_1_android` | OpenCL GPU — 標準 mlc4j AAR，支援 Adreno / Mali |
| 4 | `Qwen3_1_7B_q4f16_1_arm64` | ARM64 CPU（LLVM），速度慢 10–50 倍，保底備援 |

> **一般使用者只需要 OpenCL（第 3 項）。** NPU 版本需自備 Qualcomm / MediaTek SDK 並重新編譯 mlc4j，屬進階用途。
> Vulkan 在 Android 上不受 MLC LLM 標準版支援，已排除。

---

## 建置步驟

### 1. 加入 SDK 依賴

**sherpa-onnx**（Maven Central，自動下載）：
```groovy
// app/build.gradle — 已預設啟用
implementation 'com.k2fsa.sherpa.onnx:sherpa-onnx-android:1.10.34'
```

**mlc4j**（選擇其一）：
```groovy
// 選項 A — 本地 AAR（從 https://github.com/mlc-ai/mlc-llm/releases 下載）
// 將 mlc4j-*.aar 放入 app/libs/，取消以下注解：
// implementation fileTree(dir: 'libs', include: ['mlc4j*.aar'])

// 選項 B — Maven
// implementation 'ai.mlc.mlcllm:mlc4j:0.1.0'
```

### 2. 放入模型 .so（如有自訂 NPU 版本）

```
app/src/main/jniLibs/arm64-v8a/
├── libQwen3_1_7B_q4f16_1_qnn.so    （Qualcomm，可選）
├── libQwen3_1_7B_q4f16_1_mtk.so    （MediaTek，可選）
└── libQwen3_1_7B_q4f16_1_android.so （OpenCL，標準）
```

### 3. 編譯

```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

---

## 首次設定

App 開啟後，依序完成四項權限授權：

1. **麥克風** — 系統彈窗，點允許
2. **懸浮視窗** — 前往系統設定頁開啟
3. **無障礙服務** — 前往設定 → 無障礙 → 語音助理 → 啟用
4. **電池優化豁免** — 前往設定，選「不受限制」，防止系統背景終止服務

全部完成後，點「啟動語音助理」即可看到氣泡出現在螢幕角落。

---

## 使用方式

| 操作 | 說明 |
|------|------|
| 點擊氣泡 | 展開操作面板 |
| 點擊「話」按鈕 | 開始錄音；再點一次提前停止 |
| 點擊「⌨」按鈕 | 切換文字輸入模式 |
| 點擊「✕」或面板外側 | 收起面板（進行中的操作會先取消） |
| 連點氣泡 3 下 | 關閉語音助理服務 |

### 指令範例

- 「幫我打電話給媽媽」
- 「傳 LINE 給阿明說我在路上了」
- 「打開 YouTube」
- 「搜尋今天的天氣」

---

## 技術細節

### Pipeline 狀態機

```
Idle → Recording → Transcribing → Thinking → Executing → Done / Error → Idle
```

- `Done` 或 `Error` 狀態停留 3.5 秒後自動回到 `Idle`
- 任何狀態下呼叫 `cancel()` 皆立即回到 `Idle`

### LLM Prompt 格式

使用 Qwen3 ChatML 格式，系統提示包含：
- `/no_think` 標記（停用思考鏈，加速輸出）
- 支援的 JSON 動作 schema（click / type / scroll / back / home / open_app / done / unknown）
- 5 組 few-shot 範例（撥話、傳訊、開 App、捲動、完成）
- 常用 App 套件名稱對照表

### 安全設計

- **BlockingOverlay**：AI 執行期間遮蓋全螢幕，阻擋誤觸
- **AppBlacklist**：金融 App、驗證器、密碼管理工具一律拒絕自動操作
- **AutomationGuard**：最多 8 步、30 秒強制逾時
- **FLAG_SECURE 防護**：嘗試操作受保護視窗時回傳 Blocked

---

## 專案結構

```
app/src/main/java/com/ping/elderlyassistant/
├── MainActivity.kt                    # 權限設定畫面
├── FloatingBubbleService.kt           # 懸浮氣泡前景服務
├── AssistantAccessibilityService.kt   # 無障礙服務橋接
├── NodeSerializer.kt                  # 節點樹序列化
├── AppBlacklist.kt                    # 敏感 App 黑名單
├── BootReceiver.kt                    # 開機自啟
├── ServicePrefs.kt                    # 服務狀態持久化
├── engine/
│   ├── LlmEngine.kt                   # LLM 介面定義
│   ├── MlcLlmEngine.kt               # mlc4j 實作
│   ├── SenseVoiceEngine.kt           # sherpa-onnx ASR 實作
│   ├── AudioRecorder.kt              # 麥克風錄音 + VAD
│   └── ModelConfig.kt                # 模型路徑與推理參數
└── pipeline/
    ├── PipelineOrchestrator.kt        # 主流程協調器
    ├── ActionExecutor.kt              # JSON action 執行器
    ├── PromptBuilder.kt               # LLM prompt 建構
    ├── AutomationGuard.kt             # 步數 / 逾時安全守衛
    └── BlockingOverlay.kt             # 執行期間觸控攔截
```

---

## 授權

本專案程式碼採 MIT 授權。

所用模型及 SDK 各有獨立授權：
- SenseVoice-Small：[Apache 2.0](https://github.com/FunAudioLLM/SenseVoice)
- Qwen3：[Qwen License](https://huggingface.co/Qwen/Qwen3-1.7B)
- sherpa-onnx：[Apache 2.0](https://github.com/k2-fsa/sherpa-onnx)
- MLC LLM / mlc4j：[Apache 2.0](https://github.com/mlc-ai/mlc-llm)
