package com.ping.elderlyassistant.pipeline

/**
 * Constructs prompts for the Gemma 4 E2B voice agent (LiteRT LM backend).
 *
 * [SYSTEM_INSTRUCTION] — role + schema + few-shot examples.
 *   Injected once per LiteRT conversation via ConversationConfig.systemInstruction().
 *
 * [build] — plain user message (instruction + current screen nodes + optional history).
 *   LiteRT LM handles conversation formatting; no template tags needed here.
 *
 * Few-shot coverage:
 *   1. 撥打電話 (tap a call button visible on screen)
 *   2. 傳送訊息 (type + send in a chat input field)
 *   3. 開啟應用程式 (launch by package when UI is unhelpful)
 *   4. 捲動 / 瀏覽 (scroll when target not yet visible)
 *   5. 任務完成 / 無法執行 (done / unknown — edge cases)
 */
object PromptBuilder {

    // ── Common app package names ──────────────────────────────────────────────
    // Declared first so APP_PACKAGES_HINT can reference it during object init.
    val COMMON_APP_PACKAGES = mapOf(
        "LINE"       to "jp.naver.line.android",
        "line"       to "jp.naver.line.android",
        "微信"       to "com.tencent.mm",
        "YouTube"    to "com.google.android.youtube",
        "youtube"    to "com.google.android.youtube",
        "Chrome"     to "com.android.chrome",
        "Google"     to "com.google.android.googlequicksearchbox",
        "相機"       to "com.android.camera2",
        "地圖"       to "com.google.android.apps.maps",
        "設定"       to "com.android.settings",
        "電話"       to "com.android.dialer",
        "通話"       to "com.android.dialer",
        "聯絡人"     to "com.android.contacts",
        "訊息"       to "com.google.android.apps.messaging",
        "FB"         to "com.facebook.katana",
        "Facebook"   to "com.facebook.katana",
    )

    // ── Supported action JSON schemas ─────────────────────────────────────────
    private val ACTION_SCHEMA = """
        ## 支援的 JSON 動作（只能輸出其中一個）
        {"action":"click","id":"<viewIdResourceName>"}
        {"action":"click","id":"<viewIdResourceName>","text":"<節點文字，輔助辨識>"}
        {"action":"type","id":"<viewIdResourceName>","text":"<要輸入的文字>"}
        {"action":"scroll","direction":"down"}
        {"action":"scroll","direction":"up"}
        {"action":"back"}
        {"action":"home"}
        {"action":"open_app","package":"<packageName>"}
        {"action":"done","reason":"<任務已完成的說明>"}
        {"action":"unknown","reason":"<中文說明無法執行的原因>"}
    """.trimIndent()

    // ── Few-shot examples ─────────────────────────────────────────────────────
    private val FEW_SHOT = buildString {

        // Example 1: 撥打電話
        appendLine("### 範例 1：撥打電話")
        appendLine("指令：打電話給媽媽")
        appendLine("畫面節點：")
        appendLine("[0] TextView \"媽媽\" id=com.android.dialer:id/contact_name")
        appendLine("[1] ImageButton(clickable) \"撥打電話給媽媽\" id=com.android.dialer:id/cliv_call")
        appendLine("[2] ImageButton(clickable) \"傳送訊息\" id=com.android.dialer:id/cliv_sms")
        appendLine("輸出：{\"action\":\"click\",\"id\":\"com.android.dialer:id/cliv_call\"}")
        appendLine()

        // Example 2: 傳訊息
        appendLine("### 範例 2：在已開啟的對話框傳送訊息")
        appendLine("指令：跟阿明說我在路上，快到了")
        appendLine("畫面節點：")
        appendLine("[0] TextView \"阿明\" id=com.linecorp.lineclient.android:id/chat_name")
        appendLine("[1] EditText(editable,focusable) \"\" id=com.linecorp.lineclient.android:id/send_message_text")
        appendLine("[2] ImageButton(clickable) \"傳送\" id=com.linecorp.lineclient.android:id/btn_send")
        appendLine("輸出：{\"action\":\"type\",\"id\":\"com.linecorp.lineclient.android:id/send_message_text\",\"text\":\"我在路上，快到了\"}")
        appendLine()

        // Example 3: 開啟 App
        appendLine("### 範例 3：開啟應用程式")
        appendLine("指令：幫我打開 LINE")
        appendLine("畫面節點：")
        appendLine("[0] TextView \"設定\" id=com.android.settings:id/title")
        appendLine("[1] TextView \"Wi-Fi\" id=com.android.settings:id/wifi_settings")
        appendLine("（當前畫面沒有要找的 App，需要直接啟動）")
        appendLine("輸出：{\"action\":\"open_app\",\"package\":\"jp.naver.line.android\"}")
        appendLine()

        // Example 4: 捲動
        appendLine("### 範例 4：需要往下捲動才能看到目標")
        appendLine("指令：找王小明的電話")
        appendLine("畫面節點：")
        appendLine("[0] TextView \"陳大明\" id=com.android.contacts:id/cliv_name")
        appendLine("[1] TextView \"林小花\" id=com.android.contacts:id/cliv_name")
        appendLine("[2] TextView \"黃志明\" id=com.android.contacts:id/cliv_name")
        appendLine("（聯絡人清單可捲動，王小明尚未出現，畫面上無搜尋欄）")
        appendLine("輸出：{\"action\":\"scroll\",\"direction\":\"down\"}")
        appendLine()

        // Example 5: 任務已完成 / 無法執行
        appendLine("### 範例 5：任務完成")
        appendLine("指令：打電話給阿明（第二步，電話已在撥出中）")
        appendLine("畫面節點：")
        appendLine("[0] TextView \"通話中\" id=com.android.incallui:id/call_state_label")
        appendLine("[1] TextView \"阿明\" id=com.android.incallui:id/contactgrid_contact_name")
        appendLine("[2] ImageButton(clickable) \"靜音\" id=com.android.incallui:id/mute")
        appendLine("[3] ImageButton(clickable) \"結束通話\" id=com.android.incallui:id/hangup")
        appendLine("輸出：{\"action\":\"done\",\"reason\":\"電話已成功撥出，目前通話中\"}")
    }

    // ── App package name hints for open_app ──────────────────────────────────
    private val APP_PACKAGES_HINT = buildString {
        appendLine("## 常用 App 套件名稱（open_app 時使用）")
        for ((name, pkg) in COMMON_APP_PACKAGES.entries.distinctBy { it.value }) {
            appendLine("$name → $pkg")
        }
    }.trim()

    // ── System instruction (injected via ConversationConfig.systemInstruction()) ─
    val SYSTEM_INSTRUCTION = buildString {
        appendLine("你是一位 Android 手機操作助理，服務台灣用戶。")
        appendLine("使用者以繁體中文（台灣）或台語（閩南語）下指令；無論輸入語言為何，請以繁體中文（台灣）理解。")
        appendLine()
        appendLine("根據「使用者指令」和「畫面節點」選擇回應方式：")
        appendLine("• 能在畫面上執行操作 → 直接輸出一個 JSON 動作，不加任何其他文字")
        appendLine("• 無法執行或需要說明 → 用繁體中文簡短回覆（不超過 50 字）")
        appendLine("優先直接動手操作，只在確實無法執行時才以文字回覆。")
        appendLine()
        appendLine(ACTION_SCHEMA)
        appendLine()
        appendLine(APP_PACKAGES_HINT)
        appendLine()
        appendLine(FEW_SHOT)
    }.trim()

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Build the user message for a single agent step.
     *
     * The system instruction is handled separately by [GemmaEngine] via
     * ConversationConfig.systemInstruction() — do NOT include it here.
     *
     * @param instruction  Transcribed speech from user
     * @param nodeTree     Output of NodeSerializer.serializeForLlm()
     * @param history      Previous (instruction, action) pairs for multi-step context
     */
    fun build(
        instruction: String,
        nodeTree: String,
        history: List<Pair<String, String>> = emptyList()
    ): String = buildString {
        // Prior steps embedded as context so the model understands progress
        for ((prevInstruction, prevAction) in history) {
            appendLine("前一步驟指令：$prevInstruction")
            appendLine("前一步驟動作：$prevAction")
            appendLine()
        }

        append("## 使用者指令\n$instruction\n\n")
        if (nodeTree.isNotBlank()) {
            append("## 當前畫面節點\n$nodeTree")
        } else {
            append("## 當前畫面節點\n(無法取得 — 無障礙服務可能未連線)")
        }
    }

}
