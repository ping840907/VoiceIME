# VoiceIME — Android 本機離線語音輸入法

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android-green.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.0-purple.svg)](https://kotlinlang.org)

**VoiceIME** 是一款專為 Android 設計的高隱私、純裝置端本機（On-Device）離線語音輸入法。結合 **X-ASR** 輕量即時串流模型與 **Qwen3-ASR** 高精確度語音模型，提供「系統鍵盤」與「智慧懸浮泡泡」雙輸入型態，所有語音辨識與模型推論全程在裝置本機離線運作，無需連網即可高速輸入。

---

## 核心特色

- **完全離線、高度隱私**：語音辨識與 AI 模型推論全程在手機本機完成（僅首次下載模型時需要網路），日常輸入零雲端請求，徹底杜絕隱私外洩。
- **雙模型 ASR 架構自由切換**：
  - **X-ASR (Zipformer2 Streaming，約 120MB)**：輕量即時串流模型，邊說邊出字，超低延遲反饋；原生支援台灣繁體中文、國台語及中英夾雜。
  - **Qwen3-ASR (0.6B int8，約 900MB)**：高精確度辨識模型，支援繁體中文輸出、可選中文全形標點符號過濾，並支援自訂停頓偵測秒數（VAD 0.5s - 2.5s）。
  - **雙引擎模式**：兼具速度與準確度——說話時由 X-ASR 即時串流顯示文字，說話結束後自動切換至 Qwen3-ASR 進行全文精準潤飾與替換輸入。
- **雙輸入模式支援**：
  - **語音輸入法鍵盤 (IME Keyboard)**：完整鍵盤操作區（游標移動、選取擴展、連續退格）、長按設定鍵快速切換系統輸入法、長按「詞彙」鍵直達詞庫設定。
  - **懸浮語音泡泡 (Floating Bubble)**：
    - **穩定雙視窗架構 (Rock-Solid)**：按鈕視窗固定尺寸（60dp x 106dp），吸附螢幕左右邊界絕無抖動或位移；文字預覽卡片獨立浮現展開。
    - **智慧情境感知 X 鍵**：錄音中點擊立即中止、模型推論中點擊中斷、自動貼上後點擊一鍵復原文字框內容。
    - **無障礙直接填入**：透過 Accessibility 服務自動尋找目前焦點欄位貼入；若焦點遺失則自動備援複製至剪貼簿。
- **自訂詞庫與熱詞支援**：
  - 支援關鍵字全字匹配取代。
  - 支援 CSV 格式詞庫匯入、匯出、搜尋與維護。
- **純淨現代化介面 (Material 3)**：
  - 依「系統權限」、「輸入方式」、「辨識引擎」、「詞彙管理」、「使用說明」清晰分區。
  - 無繁雜裝飾符號與多餘技術術語，回歸原生作業系統的純淨俐落質感。
  - 針對 Android 13+「受限制的設定」提供專屬權限引導彈窗。

---

## 架構概覽

```
語音輸入入口（系統鍵盤 或 懸浮語音泡泡）
          │
          ▼
   AudioRecorder（16 kHz PCM 採樣 + VAD 靜音斷句偵測）
          │
          ├────────────────────────┬────────────────────────┐
          ▼                        ▼                        ▼
      [X-ASR]                 [Qwen3-ASR]               [雙引擎模式]
  Zipformer2 串流推論        Offline 端到端推論     X-ASR 即時串流預覽
   即說即出、超低延遲        高精確度、繁體轉換     + Qwen3-ASR 精準定稿
          │                        │                        │
          └────────────────────────┼────────────────────────┘
                                   ▼
                       文字後處理與詞庫對齊
               （標點過濾 / 自訂詞庫匹配 / 繁體轉換）
                                   │
                    ┌──────────────┴──────────────┐
                    ▼                             ▼
           [鍵盤模式：commitText]        [懸浮泡泡模式：Accessibility]
             直接插入焦點輸入框             自動填入焦點欄位 / 剪貼簿備援
                    │                             │
                    ▼                             ▼
             智慧錯字建議 Chip              情境感知 X 鍵（一鍵復原）
```

---

## 操作說明

### 1. 語音輸入法鍵盤

```
┌────────────────────────────────────────────────────────┐
│  ［建議 Chip：錯字 → 正確詞彙］（辨識完成後智慧顯示）     │
├────────────────────────────────────────────────────────┤
│  狀態列 / 錄音按鈕（含即時音量回饋）                      │
├────────────┬────────────┬────────────┬─────────────────┤
│     ⇧      │     ◀      │     ▶      │       ⌫         │  ← 操作區第一排
├────────────┼────────────┼────────────┼─────────────────┤
│     ⚙      │    詞彙    │    空格    │       ↵         │  ← 操作區第二排
└────────────┴────────────┴────────────┴─────────────────┘
```

| 按鍵 | 操作說明 |
|:---:|---|
| **錄音按鈕** | 點擊開始錄音；錄音中再次點擊可手動提前停止並送出辨識 |
| **⇧** | 切換文字選取模式（Shift ON/OFF） |
| **◀ / ▶** | 移動游標或調整選取範圍（支援長按連續移動） |
| **⌫** | 刪除游標前文字（支援長按連續刪除） |
| **⚙** | 單擊開啟設定頁面；**長按快速開啟系統輸入法切換器** |
| **詞彙** | 單擊開啟詞彙候選面板；**長按直接開啟自訂詞庫管理頁面** |
| **空格 / ↵** | 插入空白字元 / 送出換行或觸發 IME Action |

### 2. 懸浮語音泡泡

- **展開與收合**：鍵盤彈出時泡泡自動顯示，鍵盤收起時自動淡出；亦可於非鍵盤環境獨立使用。
- **語音輸入**：點擊麥克風按鈕即開始錄音，辨識完成後透過無障礙服務自動填入目前焦點文字框。
- **拖曳與吸附**：按住錄音鍵即可在螢幕兩側自由拖曳，放開後自動平滑吸附至最近邊緣（左右兩側均無預覽位移）。
- **情境感知 X 鍵**：
  - **錄音中**：顯示於按鈕上方，點擊立即取消並丟棄當前錄音。
  - **推論中**：點擊中斷背景模型運算。
  - **填入後**：點擊自動將文字框內容撤回復原至貼入前的狀態。

---

## 辨識引擎詳細規格

| 引擎 | 模型大小 | 特點 | 適用情境 |
|---|:---:|---|---|
| **X-ASR** | 約 120MB | Zipformer2 串流推論，邊講邊出字，超低延遲，原生繁中 | 日常快速即時傳送訊息、短句輸入 |
| **Qwen3-ASR** | 約 900MB | Alibaba Qwen3-ASR 0.6B 量化版，辨識率高，支援 VAD 斷句與標點過濾 | 長篇文字輸入、高精準度需求、會議紀錄 |
| **雙引擎模式** | 需雙模型 | X-ASR 即時串流顯示預覽 + 停頓後 Qwen3-ASR 全文精確校正覆蓋 | 同時追求即時視覺反饋與高準確度 |

---

## 模型下載與安裝

模型存放於 App 專用儲存空間（無需額外要求危險檔案權限）。

### 方法 A：App 內一鍵下載（推薦）
1. 安裝並開啟 VoiceIME App。
2. 進入「VoiceIME 設定」頁面。
3. 點擊 X-ASR 或 Qwen3-ASR 區塊中的「下載模型」，App 將啟動前台服務在背景自動完成下載與解壓縮。

### 方法 B：手動放置模型
手動推播模型檔案至裝置儲存目錄：

- **Qwen3-ASR 目錄**：
  `/sdcard/Android/data/com.ping.voiceime/files/models/qwen3_asr/`
  - 需包含：`conv_frontend.onnx`, `encoder.int8.onnx`, `decoder.int8.onnx`, `tokenizer/`
- **X-ASR 目錄**：
  `/sdcard/Android/data/com.ping.voiceime/files/models/x_asr/`
  - 需包含：`encoder.int8.onnx`, `decoder.onnx`, `joiner.int8.onnx`, `tokens.txt`

---

## 開發與建置

### 1. 準備環境
- Android Studio Ladybug 或更新版本
- JDK 17 / JBR
- Android SDK (API 34 / compileSdk 34, minSdk 26)

### 2. 單元測試
```bash
./gradlew testDebugUnitTest
```

### 3. 編譯與安裝
```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 專案結構

```
app/src/main/java/com/ping/voiceime/
├── VoiceImeApplication.kt         # Application 進入點（生命週期與初始化）
├── VoiceImeService.kt             # InputMethodService 主體（鍵盤渲染、即時上屏、詞庫快捷）
├── FloatingBubbleService.kt       # 懸浮語音泡泡前台服務（雙視窗架構、情境 X 鍵、無障礙連動）
├── VoiceAccessibilityService.kt   # 無障礙服務（焦點輸入框自動填入、快照復原、鍵盤狀態偵測）
├── ImeSettingsActivity.kt         # 主設定頁（引擎切換、雙引擎模式、VAD 設置、權限管理）
├── DictSettingsActivity.kt        # 自訂詞庫管理頁面（CSV 匯入/匯出、詞彙維護）
├── UserDictionary.kt              # 自訂詞庫與熱詞匹配邏輯
├── ModelDownloadService.kt        # 背景模型下載前台服務
└── engine/
    ├── AudioRecorder.kt           # 麥克風錄音、PCM 採樣與 VAD 靜音斷句偵測
    ├── ModelConfig.kt             # 引擎偏好設定、模型路徑與標點過濾函式
    ├── Qwen3AsrEngine.kt          # sherpa-onnx 離線 Qwen3-ASR 辨識封裝
    ├── XAsrEngine.kt              # sherpa-onnx 串流 X-ASR 辨識封裝
    ├── ModelDownloadSpec.kt       # 模型下載規格與校驗
    ├── ModelDownloadState.kt      # 全域下載進度狀態
    └── ModelDownloader.kt         # HTTP 下載、tar.bz2 解壓管理
```

---

## 致謝與開源授權

本專案採用 **[MIT License](LICENSE)** 開源授權，特別感謝以下開源專案與模型作者的貢獻：

| 專案 / 模型 | 作者 / 團隊 | 授權 | 說明 |
|---|---|---|---|
| **[X-ASR (zh-tw-en-streaming)](https://huggingface.co/Luigi/x-asr-zh-tw-en-streaming-ft75m)** | [Luigi](https://huggingface.co/Luigi) | Apache 2.0 | 感謝 Luigi 訓練並開源針對台灣繁體中文、國台語及英語最佳化之串流語音模型 |
| **[sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)** | [Next-gen Kaldi (k2-fsa)](https://github.com/k2-fsa) | Apache 2.0 | 提供強大且高效的跨平台 On-Device 語音辨識 Runtime |
| **[Qwen3-ASR](https://github.com/QwenLM)** | Alibaba Qwen Team | Apache 2.0 | 提供優異表現的開源語音辨識大模型 |
| **[opencc4j](https://github.com/houbb/opencc4j)** | [houbb](https://github.com/houbb) | Apache 2.0 | 提供純 Java/Kotlin 高效繁簡轉換支援 |
| **[Material Components for Android](https://github.com/material-components/material-components-android)** | Google | Apache 2.0 | 現代化 Material Design 介面元件庫 |
| **[Commons Compress](https://commons.apache.org/proper/commons-compress/)** | Apache Software Foundation | Apache 2.0 | 用於 tar.bz2 模型封存檔於本機解壓縮 |
