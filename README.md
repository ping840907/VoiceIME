# VoiceIME — Android 離線語音輸入法

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android-green.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.0-purple.svg)](https://kotlinlang.org)

**VoiceIME** 是一款專為 Android 設計的本機離線語音輸入法（Input Method Service）。提供 **Qwen3-ASR** 與 **X-ASR** 雙辨識引擎，所有語音辨識與模型推理均於裝置端本機（On-Device）完成，無需連網即可高速輸入，保護隱私無外洩疑慮。

---

## ✨ 核心特色

- 🔒 **完全離線、隱私無虞**：模型全於本機運作（僅首次於設定頁下載模型時需要網路連線），日常語音輸入零網路請求。
- 🎙️ **雙語音辨識引擎自由切換**：
  - **Qwen3-ASR (0.6B int8)**：高精確度離線辨識，結合自訂詞庫熱詞（Hotwords）自動對齊，經 opencc4j 轉換為繁體中文，支援自訂停頓偵測靈敏度（VAD）。
  - **X-ASR (Zipformer2 Streaming)**：即時串流辨識，邊講邊出字，延遲極低，原生支援台灣繁體中文、國台語及英語。
- ⚡ **即時直接上屏（Direct Commit）**：辨識文字直接透過 `commitText()` 寫入輸入框，省去傳統輸入法繁瑣的「預覽框」與「確認插入」步驟。
- 💡 **智慧模糊錯字修正**：辨識完成後自動以編輯距離比對使用者自訂詞庫，於鍵盤上方即時提供「錯字 → 正確詞彙」建議 Chip，點擊一鍵修正。
- 📝 **原生選字與候選面板整合**：支援 Android 原生長按拖曳選字或鍵盤上的選取模式（⇧），開啟詞彙候選面板即可直接替換或插入選取內容。
- ⌨️ **便捷實體操作區**：提供游標移動、選取擴展、連續刪除、一鍵喚出系統輸入法切換器（長按 ⚙️）等完整操作。
- 📦 **內建模型下載管理**：設定頁面支援一鍵背景下載解壓 Qwen3-ASR 或 X-ASR 模型，附進度條與狀態通知。

---

## 🏗️ 架構概覽

```
使用者語音輸入
     │
     ▼
 AudioRecorder（麥克風採樣 16 kHz + VAD 靜音偵測）
     │
     ├─────────────────────────────────────────┐
     ▼                                         ▼
 Qwen3AsrEngine                           XAsrEngine
 （OfflineRecognizer，NNAPI / CPU）        （OnlineRecognizer 串流）
 簡體輸出 + opencc4j 轉繁體                 原生繁體中文 / 國台英語
     │                                         │
     │ （一次性直接插入）                         │ （逐 chunk 即時修訂更新）
     └────────────────────┬────────────────────┘
                          ▼
             commitText() 直接插入輸入框
                          │
         ┌────────────────┴────────────────┐
         ▼                                 ▼
   智慧錯字建議 Chip                  選取範圍 / 游標操作
（點擊一鍵修正為自訂詞彙）              （詞彙候選面板快速替換）
```

---

## 📱 鍵盤介面與操作

```
┌────────────────────────────────────────────────────────┐
│  ［建議 Chip：錯字 → 正確詞彙］（辨識完成後智慧顯示）     │
├────────────────────────────────────────────────────────┤
│  頂部區域（依模式自動切換）：                            │
│    • 一般模式：🎙️ 錄音按鈕（含音量回饋動畫）             │
│    • 候選模式：目前選取內容提示 + 使用者自訂詞彙 Chip 列表 │
├────────────────────────────────────────────────────────┤
│  進度條 / 狀態指示文字（如：語音辨識中...）               │
├────────────┬────────────┬────────────┬─────────────────┤
│     ⇧      │     ◀      │     ▶      │       ⌫         │  ← 操作區第一排
├────────────┼────────────┼────────────┼─────────────────┤
│     ⚙️     │  詞彙 / 關閉│    空格    │       ↵         │  ← 操作區第二排
└────────────┴────────────┴────────────┴─────────────────┘
```

### 按鍵功能說明

| 按鍵 | 功能說明 |
|:---:|---|
| **🎙️** | 點擊開始錄音；錄音中點擊可手動提前停止並送出辨識 |
| **⇧** | 切換選取模式（Shift ON/OFF），以目前游標位置為固定錨點 |
| **◀ / ▶** | 移動游標或調整文字選取範圍（支援長按連續移動） |
| **⌫** | 刪除游標前一個字元（支援長按連續退格，50ms/字） |
| **⚙️** | 單擊開啟 VoiceIME 設定頁；**長按直接開啟系統輸入法切換視窗**（Input Method Picker） |
| **詞彙 / 關閉** | 開啟或關閉詞彙候選面板（有選取文字時替換選取內容，無選取時在游標處插入） |
| **空格** | 插入空格字元 |
| **↵** | 送出換行或觸發目前輸入框的 IME Action（搜尋、送出、完成等） |

---

## ⚙️ 推理引擎介紹

可在「VoiceIME 設定」隨時切換辨識引擎，設定值自動儲存。

### 1. Qwen3-ASR（精確度優先，預設）
- **技術基礎**：基於 Alibaba Qwen3-ASR 0.6B 模型量化版（sherpa-onnx `OfflineRecognizer`）。
- **特點**：辨識精確度高，支援自訂詞庫熱詞（Hotwords）動態注入增強特定專有名詞辨識率。
- **後處理**：透過 `opencc4j` 將簡體中文即時轉換為繁體中文。
- **硬體加速**：優先使用 Android NNAPI（NPU / GPU），失敗時自動無縫備援至 CPU 多執行緒運算。

### 2. X-ASR（即時串流速度優先）
- **技術基礎**：基於 Zipformer2 streaming transducer 架構（sherpa-onnx `OnlineRecognizer`）。
- **模型來源**：[Luigi/x-asr-zh-tw-en-streaming-ft75m](https://huggingface.co/Luigi/x-asr-zh-tw-en-streaming-ft75m)
- **特點**：邊說邊出字，超低延遲即時反饋；原生輸出繁體中文，針對台灣國台語口音與常用中英夾雜情境最佳化。
- **上屏機制**：採用 `deleteSurroundingText()` + `commitText()` 即時更新上屏內容，相容各類第三方輸入框。

---

## 📥 模型下載與安裝

模型存放於 App 專用儲存空間（無需額外要求危險檔案權限）。

### 方法 A：App 內一鍵下載（推薦）
1. 安裝並開啟 VoiceIME App。
2. 進入「VoiceIME 設定」選擇欲使用的引擎（Qwen3-ASR 或 X-ASR）。
3. 點擊「下載模型」，App 將自動在背景完成下載、校驗與解壓縮。

### 方法 B：手動放置模型
若需手動推播模型檔案至裝置：

#### Qwen3-ASR 模型路徑
```
/sdcard/Android/data/com.ping.voiceime[.debug]/files/models/qwen3_asr/
├── conv_frontend.onnx
├── encoder.int8.onnx
├── decoder.int8.onnx
└── tokenizer/
    ├── vocab.json
    ├── merges.txt
    └── tokenizer_config.json
```
- 下載來源：[sherpa-onnx ASR Models Releases](https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models) (`sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25.tar.bz2`)

#### X-ASR 模型路徑
```
/sdcard/Android/data/com.ping.voiceime[.debug]/files/models/x_asr/
├── encoder.int8.onnx
├── decoder.onnx
├── joiner.int8.onnx
└── tokens.txt
```
- 下載來源：[Luigi/x-asr-zh-tw-en-streaming-ft75m](https://huggingface.co/Luigi/x-asr-zh-tw-en-streaming-ft75m)

---

## 🛠️ 開發與建置

### 1. 準備 sherpa-onnx AAR
至 [sherpa-onnx Releases](https://github.com/k2-fsa/sherpa-onnx/releases) 下載 `sherpa-onnx-1.13.3.aar`，並放置於專案 `app/libs/` 目錄下：
```
app/libs/sherpa-onnx-1.13.3.aar
```

### 2. 編譯專案
使用 Gradle 進行建置：
```bash
./gradlew assembleDebug
```

### 3. 安裝至裝置
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 4. 啟用輸入法
前往 Android「系統設定」→「系統 / 一般管理」→「語言與鍵盤」→「螢幕鍵盤」→ 啟用 **VoiceIME** 並設定為預設輸入法。

---

## 📁 專案架構

```
app/src/main/java/com/ping/voiceime/
├── VoiceImeService.kt          # InputMethodService 主體（鍵盤渲染、事件派發、即時上屏）
├── UserDictionary.kt           # 使用者自訂詞庫管理與 Levenshtein 模糊搜尋修正
├── DictUsage.kt                # 詞彙使用次數統計（優先推薦常用詞）
├── DictSettingsActivity.kt     # 詞庫設定與增修刪管理頁面
├── ImeSettingsActivity.kt      # 主設定頁（引擎切換、模型下載、權限管理、VAD 靈敏度）
├── ModelDownloadService.kt     # 背景模型下載前台服務（Foreground Service）
└── engine/
    ├── Qwen3AsrEngine.kt       # sherpa-onnx 離線 Qwen3-ASR 辨識封裝（含熱詞注入）
    ├── XAsrEngine.kt           # sherpa-onnx 串流 X-ASR 辨識封裝
    ├── AudioRecorder.kt        # AudioRecord 麥克風錄音、PCM 採樣與 VAD 靜音偵測
    ├── ModelConfig.kt          # 模型檔案路徑、預設參數與 Preferences 封裝
    ├── ModelDownloadSpec.kt    # 模型下載來源定義（URL、MD5、解壓規則）
    ├── ModelDownloadState.kt   # 全域下載進度狀態 StateFlow
    └── ModelDownloader.kt      # HTTP 下載、解壓 tar.bz2 與目錄搬移工具
```

---

## 🤝 致謝與開源授權 (Credits & Acknowledgements)

本專案採用 **[MIT License](LICENSE)** 開源授權，特別感謝以下開源專案與模型作者的貢獻：

| 專案 / 模型 | 作者 / 團隊 | 授權 | 說明 |
|---|---|---|---|
| **[X-ASR (zh-tw-en-streaming)](https://huggingface.co/Luigi/x-asr-zh-tw-en-streaming-ft75m)** | [Luigi](https://huggingface.co/Luigi) | Apache 2.0 / Open Source | 感謝 Luigi 訓練並開源針對台灣繁體中文、國台語及英語微調之串流 Zipformer 語音模型 |
| **[sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)** | [Next-gen Kaldi (k2-fsa)](https://github.com/k2-fsa) | Apache 2.0 | 提供強大且高效的跨平台 On-Device 語音辨識 Runtime |
| **[Qwen3-ASR](https://github.com/QwenLM)** | Alibaba Qwen Team | Apache 2.0 | 提供優異表現的開源語音辨識大模型 |
| **[opencc4j](https://github.com/houbb/opencc4j)** | [houbb](https://github.com/houbb) | Apache 2.0 | 提供純 Java/Kotlin 高效繁簡字詞轉換支援 |
| **[Material Components for Android](https://github.com/material-components/material-components-android)** | Google | Apache 2.0 | 現代化 Material Design 介面元件庫 |
| **[Commons Compress](https://commons.apache.org/proper/commons-compress/)** | Apache Software Foundation | Apache 2.0 | 用於 tar.bz2 模型封存檔於 Android 端本機解壓縮 |
