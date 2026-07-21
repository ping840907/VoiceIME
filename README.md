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
 簡體中文                          繁體中文（逐 chunk 即時 setComposingText）
     │ opencc4j                        │
     ▼                                 ▼
         commitText() 直接插入輸入框（無暫存/確認步驟）
                    │
       使用者以系統原生選字（長按拖曳）選取要修正的片段
                    │
                    ▼
       詞彙鍵 → 候選面板（InputConnection 操作真實選取範圍）
```

---

## 主要元件

| 類別 | 說明 |
|------|------|
| `VoiceImeService` | `InputMethodService` 主體，管理鍵盤 UI 與輸入流程 |
| `Qwen3AsrEngine` | sherpa-onnx `OfflineRecognizer` 封裝，支援 NNAPI → CPU 備援，含 UserDictionary 熱詞 |
| `XAsrEngine` | sherpa-onnx `OnlineRecognizer` 封裝（streaming transducer） |
| `AudioRecorder` | 麥克風錄音，內建 VAD（靜音偵測自動停止），支援離線與串流兩種模式 |
| `UserDictionary` | SharedPreferences JSON 詞彙庫 |
| `DictSettingsActivity` | 詞彙管理頁面（新增 / 刪除） |
| `ImeSettingsActivity` | 引擎切換與模型狀態頁面 |
| `ModelConfig` | 模型路徑常數與推理參數 |

---

## 鍵盤版面

```
┌────────────────────────────────────────┐
│  （修正建議 Chip，辨識完成後視情況顯示）    │
├────────────────────────────────────────┤
│  頂部區（依模式切換內容，見下）             │
│    一般模式：🎙️ MIC 按鈕                  │
│    候選模式：選取提示 + 詞彙 Chip 列表      │
├────────────────────────────────────────┤
│  進度條 / 狀態文字                        │
├──────┬──────┬──────┬──────┬──────┬──────┤
│  ⌫  │  ◀  │  ⇧  │  ▶  │  空格 │  ↵  │  ← 固定操作區，兩種模式共用同一位置
├──────┴──────┴──────┴──────┴──────┴──────┤
│  ⚙️        │   詞彙 / 關閉（依模式切換）    │
└────────────────────────────────────────┘
```

辨識結果會直接 `commitText()` 插入目前聚焦的輸入框，沒有暫存/預覽/確認插入這幾個步驟——第一次辨識結果就滿意的話，講完就結束了。

只有**頂部區**（🎙️ 按鈕 ↔ 候選面板）跟**詞彙／關閉**按鈕會依模式切換內容；`⌫ ◀ ⇧ ▶ 空格 ↵` 這六個按鍵固定顯示在鍵盤上同一個位置，不論目前是一般模式還是候選模式都能直接使用，不需要先切換回一般模式才能刪字或送出。

### 按鍵行為

| 按鍵 | 說明 |
|------|-----------|
| ⌫ | 刪除游標前一字（長按連續刪除，50 ms/字） |
| ◀ / ▶ | 透過 `InputConnection.setSelection()` 移動真實游標／選取範圍（長按可連續移動） |
| ⇧ | 切換選取模式，錨點固定在目前游標位置 |
| 空格 | 插入空白字元 |
| ↵ | 送出 Enter / IME action |
| 🎙️ | 開始錄音／（錄音中）提前停止 |
| ⚙️ | 開啟設定 Activity |
| 詞彙 / 關閉 | 開啟候選面板／關閉候選面板（依目前模式切換） |

### 候選面板（修正機制）

修正辨識結果不再需要我們自製的選字手勢——直接用**系統原生的長按拖曳選字**選取輸入框裡想修正的片段即可。`VoiceImeService` 透過 `onUpdateSelection()` 觀察輸入框的選取狀態，偵測到非空選取時自動切換到候選模式（若詞典是空的則靜默不切換，避免干擾單純複製貼上的操作）；也可以隨時按「詞彙」鍵手動開啟，此時作用對象是目前的選取範圍（有選取則替換）或游標位置（無選取則插入）。

**游標模式**（Shift OFF）
- ◀ / ▶：移動插入游標
- 點擊詞彙 Chip：在游標位置插入
- 按 ⇧：進入選取模式，錨點固定在目前游標位置

**選取模式**（Shift ON）
- ◀ / ▶：從錨點延伸 / 收縮選取範圍
- 點擊詞彙 Chip：透過 `commitText()` 替換選取範圍
- 按 ⇧ 或「關閉」：游標回到錨點，回到游標模式

原生長按拖曳選字進入候選模式時，Shift 預設為 ON（選取起點為錨點）。

### 修正建議 Chip

辨識完成並插入後，會即時比對剛插入的文字與使用者詞典（編輯距離 1 以內的近似片段），若有命中則在上方顯示「錯字→正確」的建議 Chip，點擊即修正；沒有相關建議或內容已變更則不顯示。

---

## 推理引擎

兩個引擎可在設定頁切換，選擇持久化於 `SharedPreferences("asr_engine_selection")`。

### Qwen3-ASR（精準，可對齊自訂詞彙，支援停頓偵測，預設）

辨識較準確，會依 UserDictionary 自動對齊使用者自訂的詞彙，並可在設定頁調整停頓偵測靈敏度（多久沒說話就自動停止錄音）。

| Provider 優先順序 | 說明 |
|-----------------|------|
| `nnapi` | Android NNAPI，自動路由至 NPU / DSP / GPU（Android 8.1+） |
| `cpu` | 純軟體備援，所有裝置可用 |

錄音完成後一次性呼叫 `OfflineRecognizer.decode()`，結果透過 opencc4j 轉為繁體中文。UserDictionary 詞彙在載入引擎時作為熱詞（`OfflineQwen3AsrModelConfig.hotwords`）注入，詞彙更新時自動重新載入。

### X-ASR（快速，不自動加標點）

即時串流辨識，邊說邊顯示結果，速度較快，但不會自動加入標點符號，也不支援自訂詞彙熱詞。

基於 Zipformer2 streaming transducer（sherpa-onnx `OnlineRecognizer`）。錄音期間每個 chunk 即時送入引擎，部分結果透過 `setComposingText()` 即時顯示於輸入框（標準輸入法組字機制，下劃線樣式），最終結果以 `commitText()` 定案。原生輸出繁體中文，不需 opencc4j 後處理。

`OnlineRecognizer` 偵測到語音停頓（endpoint）時會呼叫 `reset()` 清空內部解碼狀態，但錄音本身不會因此停止（會持續錄到使用者手動停止或達到 15 秒上限）；`VoiceImeService` 因此在每次 reset 前於本地累積已完成的片段，避免說話中間的自然停頓把先前已辨識的內容洗掉。

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

前往 https://github.com/k2-fsa/sherpa-onnx/releases 下載 `sherpa-onnx-1.13.4.aar`，放入 `app/libs/`。

```groovy
// app/build.gradle
implementation(name: 'sherpa-onnx-1.13.4', ext: 'aar')
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
