package com.ping.elderlyassistant.pipeline

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.ping.elderlyassistant.AssistantAccessibilityService
import com.ping.elderlyassistant.AppBlacklist
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * Translates one parsed JSON action into a concrete Android operation.
 *
 * Execution order for every action:
 *   1. Pre-flight  — verify the target node still exists on-screen
 *   2. Execute     — perform the action via AccessibilityService or Intent
 *   3. Post-delay  — wait for animation / page transition to settle
 *
 * Supported action types:
 *   click, type, scroll, back, home, open_app, done, unknown
 *
 * Fallback strategy for `click`:
 *   id (viewIdResourceName) → text match → contentDescription match → parent walk
 */
class ActionExecutor(private val context: Context) {

    companion object {
        private const val TAG                = "ActionExecutor"
        private const val POST_CLICK_DELAY   = 400L   // ms — wait for ripple + transition
        private const val POST_TYPE_DELAY    = 150L
        private const val POST_NAVIGATE_DELAY = 600L
    }

    sealed class Result {
        object Success                            : Result()
        object Done                               : Result()   // "done" action
        data class Failure(val reason: String)    : Result()
        data class Blocked(val message: String)   : Result()  // FLAG_SECURE app
    }

    // ── Public entry point ────────────────────────────────────────────────────

    suspend fun execute(jsonStr: String): Result {
        val svc = AssistantAccessibilityService.instance
            ?: return Result.Failure("無障礙服務未連線")

        // Block secure apps before doing anything
        if (AppBlacklist.isSecure(svc.currentPackage)) {
            return Result.Blocked(AppBlacklist.BLOCKED_MESSAGE)
        }

        val json = runCatching { JSONObject(jsonStr) }.getOrElse {
            return Result.Failure("JSON 解析失敗：${it.message}")
        }

        return when (val action = json.optString("action")) {
            "click"    -> executeClick(svc, json)
            "type"     -> executeType(svc, json)
            "scroll"   -> executeScroll(svc, json.optString("direction", "down"))
            "back"     -> executeGlobal(svc, AccessibilityService.GLOBAL_ACTION_BACK,  POST_NAVIGATE_DELAY)
            "home"     -> executeGlobal(svc, AccessibilityService.GLOBAL_ACTION_HOME,  POST_NAVIGATE_DELAY)
            "open_app" -> executeOpenApp(json.optString("package"))
            "done"     -> Result.Done
            "unknown"  -> Result.Failure(json.optString("reason", "模型回報無法執行"))
            else       -> Result.Failure("未知動作類型：$action")
        }.also {
            Log.i(TAG, "action=$action result=$it json=$jsonStr")
        }
    }

    // ── click ─────────────────────────────────────────────────────────────────

    private suspend fun executeClick(svc: AssistantAccessibilityService, json: JSONObject): Result {
        val id   = json.optString("id").trim()
        val text = json.optString("text").trim()
        val root = svc.rootInActiveWindow ?: return Result.Failure("rootInActiveWindow 為空")

        // 1. Try by viewIdResourceName
        if (id.isNotBlank()) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            val target = nodes.firstOrNull { it.isVisibleToUser && (it.isClickable || it.isEnabled) }
                ?: nodes.firstOrNull { it.isVisibleToUser }?.findClickableParent()
            if (target != null) {
                target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                delay(POST_CLICK_DELAY)
                return Result.Success
            }
            Log.d(TAG, "click: id '$id' not found, trying text fallback")
        }

        // 2. Fallback: find by visible text or contentDescription
        val label = text.ifBlank { id.substringAfterLast('/') }
        if (label.isNotBlank()) {
            val byText = root.findAccessibilityNodeInfosByText(label)
            val target = byText.firstOrNull { it.isVisibleToUser && it.isClickable }
                ?: byText.firstOrNull { it.isVisibleToUser }?.findClickableParent()
            if (target != null) {
                target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                delay(POST_CLICK_DELAY)
                return Result.Success
            }
        }

        return Result.Failure("找不到可點擊的節點：id='$id' text='$text'")
    }

    // ── type ──────────────────────────────────────────────────────────────────

    private suspend fun executeType(svc: AssistantAccessibilityService, json: JSONObject): Result {
        val id   = json.optString("id").trim()
        val text = json.optString("text")   // getString would throw if key absent (LLM hallucination)
        if (text.isBlank()) return Result.Failure("type 動作缺少 text 欄位")
        val root = svc.rootInActiveWindow ?: return Result.Failure("rootInActiveWindow 為空")

        val target: AccessibilityNodeInfo? = when {
            id.isNotBlank() -> {
                val nodes = root.findAccessibilityNodeInfosByViewId(id)
                nodes.firstOrNull { it.isVisibleToUser && it.isEditable }
                    ?: nodes.firstOrNull { it.isVisibleToUser }
            }
            else -> findFirstEditable(root)
        }

        if (target == null) return Result.Failure("找不到輸入欄位：id='$id'")

        // Focus first, then set text
        target.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
        target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        delay(80)

        val args = Bundle().apply {
            putString(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val ok = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        delay(POST_TYPE_DELAY)
        return if (ok) Result.Success else Result.Failure("ACTION_SET_TEXT 失敗")
    }

    // ── scroll ────────────────────────────────────────────────────────────────

    private suspend fun executeScroll(svc: AssistantAccessibilityService, direction: String): Result {
        val root = svc.rootInActiveWindow ?: return Result.Failure("rootInActiveWindow 為空")
        val scrollable = root.findFirstScrollable()
            ?: return Result.Failure("畫面中沒有可捲動的元素")

        val action = if (direction == "up")
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        else
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD

        scrollable.performAction(action)
        delay(POST_CLICK_DELAY)
        return Result.Success
    }

    // ── global actions ────────────────────────────────────────────────────────

    private suspend fun executeGlobal(svc: AssistantAccessibilityService, actionId: Int, delayMs: Long): Result {
        svc.performGlobalAction(actionId)
        delay(delayMs)
        return Result.Success
    }

    // ── open_app ──────────────────────────────────────────────────────────────

    private suspend fun executeOpenApp(pkg: String): Result {
        if (pkg.isBlank()) return Result.Failure("未指定 packageName")
        val intent = context.packageManager.getLaunchIntentForPackage(pkg)
            ?: return Result.Failure("裝置上找不到 App：$pkg")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return try {
            context.startActivity(intent)
            delay(POST_NAVIGATE_DELAY)
            Result.Success
        } catch (ex: android.content.ActivityNotFoundException) {
            Result.Failure("無法啟動 App：$pkg")
        }
    }

    // ── Node traversal helpers ────────────────────────────────────────────────

    private fun AccessibilityNodeInfo.findClickableParent(): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = parent
        repeat(6) {     // walk at most 6 levels up
            if (current == null) return null
            if (current!!.isClickable) return current
            current = current!!.parent
        }
        return null
    }

    private fun findFirstEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            if (n.isEditable && n.isVisibleToUser) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        return null
    }

    private fun AccessibilityNodeInfo.findFirstScrollable(): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(this)
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            if (n.isScrollable && n.isVisibleToUser) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        return null
    }
}
