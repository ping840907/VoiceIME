# VoiceIME — 離線語音輸入法

Android 離線語音輸入鍵盤（Input Method Service）。以 Qwen3-ASR 或 X-ASR 為辨識引擎，在裝置本機完成語音辨識，辨識過程不需要網路連線（僅首次下載模型時需要）。

---

## 架構概覽

```
使用者語音
     │
     ▼
 AudioRecorder（VAD + PCM 採樣）
     │ FloatArray (16 kHz)
     ├─────────────────────────────────┐
     ▼                                 ▼
 Qwen3AsrEngine                   XAsrEngine
 （OfflineRecognizer）             （OnlineRecognizer）
 簡體中文                          繁體中文（逐 chunk 即時）
     │ opencc4j                        │
     ▼                                 ▼
              pendingText（預覽區）
                    │
        ┌───────────┼───────────┐
        ▼           ▼           ▼
     長按/單點    詞彙鍵      確認插入
   → 游標面板  → 游標面板  → commitText()
```

---

## 主要元件

| 類別 | 說明 |
|------|------|
| `VoiceImeService` | `InputMethodService` 主體，管理鍵盤 UI 與輸入流程 |
| `Qwen3AsrEngine` | sherpa-onnx `OfflineRecognizer` 封裝，支援 NNAPI → CPU 備援，含 UserDictionary 熱詞 |
| `XAsrEngine` | sherpa-onnx `OnlineRecognizer` 封裝（streaming transducer），含 UserDictionary 熱詞 |
| `AudioRecorder` | 麥克風錄音，內建 VAD（靜音偵測自動停止），支援離線與串流兩種模式 |
| `SelectableTextView` | 自製長按 + 拖曳選取 TextView，不觸發系統焦點搶奪 |
| `UserDictionary` | SharedPreferences JSON 詞彙庫 |
| `DictSettingsActivity` | 詞彙管理頁面（新增 / 刪除） |
| `ImeSettingsActivity` | 引擎切換與模型狀態頁面 |
| `ModelConfig` | 模型路徑常數與推理參數 |

---

## 鍵盤版面

```
┌────────────────────────────────────────┐
│  辨識結果預覽區（長按 / 單點進入游標面板）  │
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
| ⚙️ | 開啟設定 Activity | — |
| 詞彙 | 開啟游標面板（直接插入模式） | 開啟游標面板（插入至游標位置） |
| 空格 | 插入空白字元 | — |

### 游標面板

長按預覽區、單點預覽區、或按「詞彙」鍵，都會進入同一個游標面板。

```
┌────────────────────────────────────────┐
│  辨識結果（含游標 |）                    │
├──────────────────────────────────────  │
│  插入位置 / 選取範圍提示                  │
├────────────────────────────────────────┤
│  [詞彙 Chip 列表]                       │
├──────┬───────────┬───────────┬─────────┤
│  ⇧   │     ◀     │     ▶     │  關閉   │
└──────┴───────────┴───────────┴─────────┘
```

**游標模式**（Shift OFF）
- ◀ / ▶：移動插入游標
- 點擊詞彙 Chip：在游標位置插入
- 按 ⇧：進入選取模式，錨點固定在目前游標位置

**選取模式**（Shift ON）
- ◀ / ▶：從錨點延伸 / 收縮選取範圍
- 點擊詞彙 Chip：替換選取範圍
- 按 ⇧ 或「取消選取」：游標回到錨點，回到游標模式

長按拖曳進入面板時，Shift 預設為 ON（拖曳起點為錨點）。

---

## 推理引擎

兩個引擎可在設定頁切換，選擇持久化於 `SharedPreferences("asr_engine_selection")`。

### Qwen3-ASR（離線，預設）

| Provider 優先順序 | 說明 |
|-----------------|------|
| `nnapi` | Android NNAPI，自動路由至 NPU / DSP / GPU（Android 8.1+） |
| `cpu` | 純軟體備援，所有裝置可用 |

錄音完成後一次性呼叫 `OfflineRecognizer.decode()`，結果透過 opencc4j 轉為繁體中文。UserDictionary 詞彙在載入引擎時作為熱詞（`OfflineQwen3AsrModelConfig.hotwords`）注入，詞彙更新時自動重新載入。

### X-ASR（串流，原生繁體）

基於 Zipformer2 streaming transducer（sherpa-onnx `OnlineRecognizer`）。錄音期間每個 chunk 即時送入引擎，部分辨識結果即時顯示於預覽區。原生輸出繁體中文，不需 opencc4j 後處理。

使用 `modified_beam_search` 解碼（transducer 熱詞的必要條件）。UserDictionary 詞彙於每次錄音時透過 `createStream(hotwords)` 動態傳入，無需重載引擎。

---

## 模型安裝

模型放置於 App 外部專用儲存（無需 READ_EXTERNAL_STORAGE）。

**自動下載（推薦）**：在設定頁點擊「下載模型」，App 會直接從下方來源抓取並自動解壓縮/安裝到正確位置，僅此步驟需要網路連線；辨識過程仍完全於裝置本機執行。也可以用下方手動方式自行放置。

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

1. 開啟 App，於設定頁點擊「下載模型」（需要網路連線）
2. 啟用輸入法後，在任意輸入框點擊鍵盤圖示切換至 VoiceIME
3. App 啟動時自動在背景預載模型（首次約 3–10 秒）
4. 麥克風按鈕亮起後即可開始語音輸入

---

## 專案結構

```
app/src/main/java/com/ping/voiceim/
├── VoiceImeService.kt          # InputMethodService 主體
├── SelectableTextView.kt       # 自製長按選取 TextView
├── UserDictionary.kt           # 詞彙庫 SharedPreferences 封裝 + 模糊修正比對
├── DictUsage.kt                # 詞彙使用次數統計
├── DictSettingsActivity.kt     # 詞彙管理頁面
├── ImeSettingsActivity.kt      # 設定頁（引擎選擇、模型下載、VAD 靈敏度）
└── engine/
    ├── Qwen3AsrEngine.kt       # sherpa-onnx Qwen3-ASR 封裝（離線）
    ├── XAsrEngine.kt           # sherpa-onnx X-ASR 封裝（串流）
    ├── AudioRecorder.kt        # 麥克風錄音 + VAD
    ├── ModelConfig.kt          # 模型路徑與參數常數
    ├── ModelDownloadSpec.kt    # 模型下載來源設定
    └── ModelDownloader.kt      # HTTP 下載 + tar.bz2 解壓縮
```

---

## 授權

程式碼採 MIT 授權。

| 元件 | 授權 |
|------|------|
| sherpa-onnx | Apache 2.0 |
| Qwen3-ASR-0.6B | Apache 2.0 |
| opencc4j | Apache 2.0 |
| Material Components for Android | Apache 2.0 |
