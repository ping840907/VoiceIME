# 語音助理

Android 上的離線語音操控助理。說一句話，助理就能替你點按、輸入、開啟 App，全程不需要網路。

## 功能概覽

- **語音輸入**：按下麥克風按鈕說話，自動辨識後送給 AI 執行
- **文字輸入**：切換鍵盤模式，直接打字下指令
- **ASR 引擎切換**：在設定頁選擇 SenseVoice-Small（快速）或 Qwen3-ASR-0.6B（高精度）
- **多步驟自動化**：一條指令可跨畫面連續操作（上限 8 步、30 秒逾時）
- **完全離線**：ASR 與 LLM 均在裝置本機執行，語音不上傳
- **懸浮氣泡**：漸層圓球樣式，吸附四邊，常駐於所有 App 上方
- **開機自啟**：裝置重開機後自動恢復運行
- **安全保護**：執行期間顯示攔截遮罩；金融、OTP、密碼管理 App 一律拒絕自動操作

---

## 架構

```
使用者語音 / 文字
        │
        ▼
 SenseVoice-Small  ←─┐  可在設定頁切換
 Qwen3-ASR-0.6B   ←─┘  (sherpa-onnx，純離線)
        │ 文字轉譯
        ▼
  Gemma 4 E2B (LLM)      ← LiteRT LM，NPU → GPU → CPU 自動備援
        │ JSON action
        ▼
 AccessibilityService      ← 讀取畫面節點、執行點按 / 輸入 / 滑動
        │
        ▼
   目標 App 操作完成
```

### 主要元件

| 元件 | 說明 |
|------|------|
| `FloatingBubbleService` | 懸浮氣泡前景服務，管理氣泡與展開面板 UI |
| `PipelineOrchestrator` | 協調錄音 → 轉譯 → LLM → 執行的完整流程 |
| `AsrEngine` | ASR 後端通用介面 |
| `SenseVoiceEngine` | sherpa-onnx SenseVoice 封裝，NNAPI → CPU 備援 |
| `Qwen3AsrEngine` | sherpa-onnx Qwen3-ASR 封裝，NNAPI → CPU 備援 |
| `GemmaEngine` | LiteRT LM Gemma 4 封裝，NPU → GPU → CPU 備援 |
| `ActionExecutor` | 將 LLM JSON 動作轉換為 Accessibility 操作 |
| `AssistantAccessibilityService` | 讀取畫面節點樹、執行手勢與文字輸入 |
| `NodeSerializer` | 將無障礙節點樹壓縮為 LLM 可用的文字格式 |
| `BlockingOverlay` | 執行期間全螢幕觸控攔截遮罩 |
| `AutomationGuard` | 多步驟安全限制（步數上限、逾時） |
| `AppBlacklist` | 禁止自動操作的敏感 App 清單 |

---

## 需求

- Android 8.0（API 26）以上
- 建議：Snapdragon 或 MediaTek 旗艦機，RAM ≥ 6 GB
- 儲存空間：SenseVoice ~234 MB、Qwen3-ASR ~600 MB、Gemma 4 E2B ~1.3 GB
- 開發環境：Android Studio Hedgehog 以上、JDK 17

---

## 模型下載與安裝

所有模型放在 App 的外部專用儲存空間，**不需要** `READ_EXTERNAL_STORAGE` 權限。

目標根目錄（以 debug 版為例）：
```
/sdcard/Android/data/com.ping.elderlyassistant.debug/files/models/
```

---

### 1. SenseVoice-Small（ASR，預設，~234 MB）

**下載**：[sherpa-onnx ASR Models Releases](https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models)

找到並下載：
```
sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17.tar.bz2
```

**需要的檔案**：
```
sense_voice/
├── model.int8.onnx   ← 壓縮包內的 model.int8.onnx（約 234 MB）
└── tokens.txt
```

> 壓縮包解壓後，將資料夾重新命名為 `sense_voice` 並確認內含 `model.int8.onnx`（或將 `model.onnx` 重新命名）。

**推送至裝置**：
```bash
adb push sense_voice/ \
  /sdcard/Android/data/com.ping.elderlyassistant.debug/files/models/sense_voice/
```

---

### 2. Qwen3-ASR-0.6B-int8（ASR，可選切換，~600 MB）

**下載**：[sherpa-onnx ASR Models Releases](https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models)

找到並下載：
```
sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25.tar.bz2
```

**需要的檔案**：
```
qwen3_asr/
├── conv_frontend.onnx
├── encoder.int8.onnx
├── decoder.int8.onnx
└── tokenizer/            ← 整個目錄
    ├── vocab.json
    ├── merges.txt
    └── ...（其餘詞表檔案）
```

> 壓縮包解壓後，將資料夾重新命名為 `qwen3_asr`。

**推送至裝置**：
```bash
adb push qwen3_asr/ \
  /sdcard/Android/data/com.ping.elderlyassistant.debug/files/models/qwen3_asr/
```

> 在 App 主頁「語音辨識引擎」卡片選擇 **Qwen3-ASR-0.6B** 後重新啟動服務即可生效。

---

### 3. Gemma 4 E2B INT4（LLM，~1.3 GB）

**下載**：[Google AI Edge — LiteRT LM Models](https://ai.google.dev/edge/litert/models/gemma)

或從 Hugging Face 搜尋：
```
google/gemma-4-e2b-it-lm-int4
```
下載 `.task` 格式的量化模型檔案。

**需要的檔案**：
```
models/
└── gemma4-e2b-it-int4.task   ← 單一 .task 檔（約 1.3 GB）
```

**推送至裝置**：
```bash
adb push gemma4-e2b-it-int4.task \
  /sdcard/Android/data/com.ping.elderlyassistant.debug/files/models/gemma4-e2b-it-int4.task
```

---

### 最終目錄結構

```
/sdcard/Android/data/com.ping.elderlyassistant.debug/files/models/
├── sense_voice/
│   ├── model.int8.onnx
│   └── tokens.txt
├── qwen3_asr/                  ← 選用（切換 ASR 引擎時需要）
│   ├── conv_frontend.onnx
│   ├── encoder.int8.onnx
│   ├── decoder.int8.onnx
│   └── tokenizer/
└── gemma4-e2b-it-int4.task
```

---

## 推理加速層

### ASR（ONNX Runtime via sherpa-onnx）

| 優先順序 | Provider | 說明 |
|---------|----------|------|
| 1 | `nnapi` | Android NNAPI，自動路由至 NPU / DSP / GPU（Android 8.1+） |
| 2 | `cpu` | 純軟體，所有裝置均可用 |

### LLM（LiteRT LM）

| 優先順序 | Backend | 說明 |
|---------|---------|------|
| 1 | NPU (QNN) | Qualcomm Hexagon DSP，最低功耗、最快速度 |
| 2 | GPU (OpenCL) | Adreno / Mali GPU，廣泛支援 |
| 3 | CPU | 保底備援，速度較慢 |

---

## 建置步驟

### 1. 加入 SDK 依賴

**sherpa-onnx**（需手動下載 AAR，不發佈至 Maven Central）：

1. 前往 https://github.com/k2-fsa/sherpa-onnx/releases
2. 下載 `sherpa-onnx-1.13.3.aar`（選一般版，勿選 `-rknn` 或 `-static-link` 變體）
3. 放入 `app/libs/`

```groovy
// app/build.gradle
implementation(name: 'sherpa-onnx-1.13.3', ext: 'aar')
```

**LiteRT LM**（Maven，已加入 `app/build.gradle`）：

```groovy
implementation 'com.google.ai.edge.litert:litert-lm-android:1.0.0'
```

### 2. 編譯

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
| 拖曳氣泡 | 移動位置，放開後自動吸附最近邊緣 |

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

系統提示（`PromptBuilder.SYSTEM_INSTRUCTION`）直接傳入 LiteRT LM 的 `ConversationConfig.systemInstruction()`，包含：

- 繁體中文 / 台語助理身分描述
- 支援的 JSON 動作 schema（click / type / scroll / back / home / open_app / done / unknown）
- 5 組 few-shot 範例（撥話、傳訊、開 App、捲動、完成）
- 常用 App 套件名稱對照表

使用者訊息為純文字，多步驟歷程以前綴行形式嵌入。

### 安全設計

- **BlockingOverlay**：AI 執行期間遮蓋全螢幕，阻擋誤觸
- **AppBlacklist**：金融 App、驗證器、密碼管理工具一律拒絕自動操作
- **AutomationGuard**：最多 8 步、30 秒強制逾時
- **FLAG_SECURE 防護**：嘗試操作受保護視窗時回傳 Blocked

---

## 專案結構

```
app/src/main/java/com/ping/elderlyassistant/
├── MainActivity.kt                    # 權限設定畫面 + ASR 引擎選擇
├── FloatingBubbleService.kt           # 懸浮氣泡前景服務
├── AssistantAccessibilityService.kt   # 無障礙服務橋接
├── NodeSerializer.kt                  # 節點樹序列化
├── AppBlacklist.kt                    # 敏感 App 黑名單
├── BootReceiver.kt                    # 開機自啟
├── ServicePrefs.kt                    # 服務狀態與 ASR 引擎選擇持久化
├── engine/
│   ├── AsrEngine.kt                   # ASR 後端通用介面
│   ├── SenseVoiceEngine.kt            # sherpa-onnx SenseVoice 實作
│   ├── Qwen3AsrEngine.kt              # sherpa-onnx Qwen3-ASR 實作
│   ├── LlmEngine.kt                   # LLM 介面定義
│   ├── GemmaEngine.kt                 # LiteRT LM Gemma 4 實作
│   ├── AudioRecorder.kt               # 麥克風錄音 + VAD
│   └── ModelConfig.kt                 # 模型路徑與推理參數
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
- Qwen3-ASR：[Apache 2.0](https://github.com/QwenLM/Qwen3-ASR)
- Gemma 4：[Gemma Terms of Use](https://ai.google.dev/gemma/terms)
- sherpa-onnx：[Apache 2.0](https://github.com/k2-fsa/sherpa-onnx)
- LiteRT LM：[Apache 2.0](https://github.com/google-ai-edge/LiteRT)
