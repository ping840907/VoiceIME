# VoiceIME — 離線語音輸入法

Android 離線語音輸入鍵盤（Input Method Service）。以 Qwen3-ASR 為核心引擎，在裝置本機完成語音辨識，不需要網路連線。

---

## 架構概覽

```
使用者語音
     │
     ▼
 AudioRecorder（VAD + PCM 採樣）
     │ FloatArray (16 kHz)
     ▼
 Qwen3AsrEngine（sherpa-onnx 離線辨識）
     │ 簡體中文文字
     ▼
 opencc4j（ZhConverterUtil.toTraditional）
     │ 繁體中文文字
     ▼
 pendingText（預覽區顯示）
     │
     ├── 長按選取 → UserDictionary 替換詞候選面板
     ├── 確認插入 → InputConnection.commitText()
     └── 詞彙鍵 → 直接插入自定義詞彙
```

---

## 主要元件

| 類別 | 說明 |
|------|------|
| `VoiceImeService` | `InputMethodService` 主體，管理鍵盤 UI 與輸入流程 |
| `Qwen3AsrEngine` | sherpa-onnx `OfflineRecognizer` 封裝，支援 NNAPI → CPU 備援 |
| `AudioRecorder` | 麥克風錄音，內建 VAD（靜音偵測自動停止） |
| `SelectableTextView` | 自製長按 + 拖曳選取 TextView，不觸發系統焦點搶奪 |
| `UserDictionary` | SharedPreferences JSON 詞彙庫（key=from, value=to） |
| `DictSettingsActivity` | 詞彙管理頁面（新增 / 刪除） |
| `ImeSettingsActivity` | 顯示 ASR Provider 狀態（NNAPI / CPU） |
| `ModelConfig` | 模型路徑常數與推理參數 |

---

## 鍵盤版面

```
┌────────────────────────────────────────┐
│  辨識結果預覽區（長按可選取文字）           │
├────────────────────────────────────────┤
│  進度條 / 狀態文字                        │
├──────────┬────────────┬────────────────┤
│  ⌫ / 取消 │    🎙️ MIC  │   ↵ / 確認插入 │
├──────────┴────────────┴────────────────┤
│  ⚙️      │    空格     │    詞彙         │
└──────────┴────────────┴────────────────┘
```

### 按鍵行為

| 按鍵 | 無辨識文字 | 有辨識文字 |
|------|-----------|-----------|
| ⌫ | 刪除游標前一字 | 取消辨識結果 |
| ⌫ 長按 | 連續刪除（50 ms/字） | — |
| ↵ | 送出 Enter / IME action | 確認插入辨識結果 |
| 🎙️ | 開始錄音 | 開始錄音（取代舊結果） |
| 🎙️（錄音中） | 提前停止 | — |
| ⚙️ | 開啟詞彙設定 Activity | — |
| 詞彙 | 顯示自定義詞彙插入面板 | 同左 |
| 空格 | 插入空白字元 | — |

### 候選詞替換流程

1. 辨識完成 → 文字顯示於預覽區
2. 長按預覽區文字 → 選取單字（可用 ◀ ▶ 調整範圍）
3. 候選詞面板顯示 UserDictionary 中的詞彙
4. 點選詞彙 → 替換選取範圍的文字
5. 點「確認插入」提交全文，或點「取消選取」繼續編輯

### 詞彙直接插入流程

1. 點「詞彙」鍵 → 顯示詞彙插入面板
2. 點選任一詞彙 → 直接 `commitText()` 至目標輸入框
3. 面板自動關閉

---

## 推理引擎

兩個引擎可在設定頁切換，選擇持久化於 `SharedPreferences("asr_engine_selection")`。

### Qwen3-ASR（離線，預設）

| Provider 優先順序 | 說明 |
|-----------------|------|
| `nnapi` | Android NNAPI，自動路由至 NPU / DSP / GPU（Android 8.1+） |
| `cpu` | 純軟體備援，所有裝置可用 |

活躍 provider 持久化於 `SharedPreferences("asr_engine")`，可在 `ImeSettingsActivity` 查看。

錄音完成後一次性呼叫 `OfflineRecognizer.decode()`，結果透過 opencc4j 轉為繁體中文。

### X-ASR（串流，原生繁體）

基於 Zipformer2 streaming transducer（sherpa-onnx `OnlineRecognizer`）。錄音期間每個 1024-frame chunk 即時送入引擎，部分辨識結果即時顯示於預覽區。原生輸出繁體中文，不需 opencc4j 後處理。

使用 CPU provider（NNAPI 對 transducer 支援有限）。

### 繁簡轉換

僅 Qwen3 引擎套用 **opencc4j 1.8.1**（`ZhConverterUtil.toTraditional()`）。X-ASR 引擎直接輸出繁體中文，`postProcess()` 會根據選取的引擎決定是否呼叫 opencc4j。

---

## 模型安裝

模型放置於 App 外部專用儲存（無需 READ_EXTERNAL_STORAGE）：

### Qwen3-ASR

```
/sdcard/Android/data/com.ping.voiceim[.debug]/files/models/qwen3_asr/
├── conv_frontend.onnx
├── encoder.int8.onnx
├── decoder.int8.onnx
└── tokenizer/
    ├── vocab.json
    ├── merges.txt
    ├── tokenizer_config.json
    └── （其餘詞表檔案）
```

**下載來源**：[sherpa-onnx ASR Models Releases](https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models)

```
sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25.tar.bz2
```

解壓後重新命名資料夾為 `qwen3_asr`，推送至裝置：

```bash
adb push qwen3_asr/ \
  /sdcard/Android/data/com.ping.voiceim.debug/files/models/
```

### X-ASR

```
/sdcard/Android/data/com.ping.voiceim[.debug]/files/models/x_asr/
├── encoder.int8.onnx
├── decoder.onnx
├── joiner.int8.onnx
└── tokens.txt
```

**下載來源**：[Luigi/x-asr-zh-tw-en-streaming-ft75m](https://huggingface.co/Luigi/x-asr-zh-tw-en-streaming-ft75m)

推送至裝置：

```bash
adb push x_asr/ \
  /sdcard/Android/data/com.ping.voiceim.debug/files/models/
```

---

## 建置步驟

### 1. 加入 sherpa-onnx AAR

前往 https://github.com/k2-fsa/sherpa-onnx/releases 下載 `sherpa-onnx-1.13.3.aar`，放入 `app/libs/`。

```groovy
// app/build.gradle
implementation(name: 'sherpa-onnx-1.13.3', ext: 'aar')
```

### 2. 其他依賴（已在 build.gradle）

```groovy
implementation 'com.github.houbb:opencc4j:1.8.1'
implementation 'com.google.android.material:material:1.12.0'
```

### 3. 編譯與安裝

```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

### 4. 啟用輸入法

系統設定 → 一般管理 → 鍵盤清單 → 啟用 VoiceIME → 切換為預設輸入法。

---

## 首次使用

1. 啟用輸入法後，在任意輸入框點擊鍵盤圖示切換至 VoiceIME
2. App 啟動時自動在背景預載模型（首次約 3–10 秒）
3. 麥克風按鈕亮起後即可開始語音輸入

若麥克風圖示顯示為「請前往設定授予麥克風權限」，點擊後會跳轉至 App 系統設定頁面授權。

---

## 技術細節

### VAD（靜音偵測）

`AudioRecorder` 以 16 kHz 單聲道採樣，每 160 個樣本（10 ms）計算 RMS 能量。連續 `VAD_SILENCE_SECONDS`（1.5 s）低於 `VAD_SILENCE_THRESHOLD`（0.012）時自動停止錄音，最長錄音時間 `MAX_RECORD_SECONDS`（15 s）。

### 文字選取

`SelectableTextView` 繼承自 `TextView`，以 `GestureDetector` 偵測長按起點，再追蹤 `ACTION_MOVE` 計算選取範圍。設定 `isFocusable = false` 避免搶奪 IME 視窗焦點，以 `requestDisallowInterceptTouchEvent(true)` 防止外層 `HorizontalScrollView` 攔截拖曳事件。

### 選取錨點模型（Anchor / Focus）

- **anchor**：長按時確定，長按期間保持不動
- **focus**：`▶` 往右移動，`◀` 往左移動（可越過 anchor 反向延伸）
- `selStart = min(anchor, focus)`, `selEnd = max(anchor, focus) + 1`

### UserDictionary

JSON 儲存於 `SharedPreferences("user_dict")`，格式為 `{ "詞彙": "詞彙" }`（key 與 value 目前相同，保留 key 供未來 from→to 替換擴展）。替換時以 index-based 方式操作：`text.substring(0, s) + replacement + text.substring(e)`，避免 `replaceFirst()` 只替換第一個出現位置的問題。

---

## 專案結構

```
app/src/main/java/com/ping/voiceim/
├── VoiceImeService.kt          # InputMethodService 主體
├── SelectableTextView.kt       # 自製長按選取 TextView
├── UserDictionary.kt           # 詞彙庫 SharedPreferences 封裝
├── DictSettingsActivity.kt     # 詞彙管理頁面
├── ImeSettingsActivity.kt      # 設定頁（引擎選擇、模型狀態）
└── engine/
    ├── Qwen3AsrEngine.kt       # sherpa-onnx Qwen3-ASR 封裝（離線）
    ├── XAsrEngine.kt           # sherpa-onnx X-ASR 封裝（串流）
    ├── AudioRecorder.kt        # 麥克風錄音 + VAD（支援離線與串流模式）
    └── ModelConfig.kt          # 模型路徑與參數常數
```

---

## 授權

程式碼採 MIT 授權。

所用第三方元件：

| 元件 | 授權 |
|------|------|
| sherpa-onnx | Apache 2.0 |
| Qwen3-ASR-0.6B | Apache 2.0 |
| opencc4j | Apache 2.0 |
| Material Components for Android | Apache 2.0 |
