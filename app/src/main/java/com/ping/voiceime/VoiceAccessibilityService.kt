package com.ping.voiceime

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

class VoiceAccessibilityService : AccessibilityService() {

    data class KeyboardInfo(
        val isVisible: Boolean,
        val keyboardTop: Int,
        val keyboardHeight: Int
    )

    data class EditorSnapshot(
        val node: AccessibilityNodeInfo?,
        val originalText: String,
        val selectionStart: Int,
        val selectionEnd: Int,
        val capturedAtMs: Long = System.currentTimeMillis()
    )

    companion object {
        private const val TAG = "VoiceAccessibility"

        @Volatile
        var instance: VoiceAccessibilityService? = null
            private set

        fun isServiceRunning(): Boolean = instance != null

        /**
         * Callback fired when soft keyboard status or bounds change.
         */
        var onKeyboardStateChanged: ((KeyboardInfo) -> Unit)? = null

        /**
         * Callback fired when entering or exiting text input state.
         * true = editable field is focused; false = no editable field focused.
         */
        var onInputFocusStateChanged: ((Boolean) -> Unit)? = null

        /**
         * Callbacks for X-button (Undo) auto-invalidation
         */
        var onManualTypingDetected: (() -> Unit)? = null
        var onCursorMoved: (() -> Unit)? = null
        var onInputFocusLost: (() -> Unit)? = null
    }

    private var lastFocusedNode: AccessibilityNodeInfo? = null
    private var currentInputState: Boolean? = null
    private var currentKeyboardInfo: KeyboardInfo = KeyboardInfo(false, 0, 0)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isAutomatedActionInProgress = false
    var lastSnapshot: EditorSnapshot? = null
        private set

    fun isInputFocused(): Boolean = currentInputState == true
    fun getKeyboardInfo(): KeyboardInfo = currentKeyboardInfo

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "VoiceAccessibilityService connected")
        checkKeyboardState()
        checkActiveWindowInputState()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        onKeyboardStateChanged = null
        onInputFocusStateChanged = null
        onManualTypingDetected = null
        onCursorMoved = null
        onInputFocusLost = null
        lastSnapshot = null
        Log.i(TAG, "VoiceAccessibilityService disconnected")
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {
        Log.w(TAG, "VoiceAccessibilityService interrupted")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                checkKeyboardState()
                mainHandler.postDelayed({ checkKeyboardState() }, 150)
                checkActiveWindowInputState()
            }
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                checkKeyboardState()
                val source = event.source
                if (source != null && isEditableNode(source)) {
                    lastFocusedNode = source
                    notifyInputState(true)
                } else {
                    checkActiveWindowInputState()
                }
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                if (!isAutomatedActionInProgress) {
                    onManualTypingDetected?.invoke()
                }
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                if (!isAutomatedActionInProgress) {
                    onCursorMoved?.invoke()
                }
            }
        }
    }

    fun checkKeyboardState(): KeyboardInfo {
        val windowList = runCatching { windows }.getOrNull() ?: emptyList()
        val screenHeight = resources.displayMetrics.heightPixels
        val imeWindow = windowList.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }

        if (imeWindow != null) {
            val bounds = Rect()
            imeWindow.getBoundsInScreen(bounds)
            // Keyboard is active if height > 100, top is within screen bounds
            if (bounds.height() > 100 && bounds.top < screenHeight && bounds.bottom >= bounds.top) {
                val keyboardHeight = bounds.height()
                val keyboardTop = bounds.top
                val info = KeyboardInfo(isVisible = true, keyboardTop = keyboardTop, keyboardHeight = keyboardHeight)
                updateKeyboardState(info)
                return info
            }
        }
        val info = KeyboardInfo(isVisible = false, keyboardTop = screenHeight, keyboardHeight = 0)
        updateKeyboardState(info)
        return info
    }

    private fun updateKeyboardState(info: KeyboardInfo) {
        if (currentKeyboardInfo == info) return
        currentKeyboardInfo = info
        Log.i(TAG, "updateKeyboardState: isVisible=${info.isVisible}, top=${info.keyboardTop}, height=${info.keyboardHeight}")
        onKeyboardStateChanged?.invoke(info)
    }

    private fun notifyInputState(hasInputFocus: Boolean) {
        if (currentInputState == hasInputFocus) return
        currentInputState = hasInputFocus
        Log.i(TAG, "notifyInputState: hasInputFocus=$hasInputFocus")
        onInputFocusStateChanged?.invoke(hasInputFocus)
        if (!hasInputFocus) {
            onInputFocusLost?.invoke()
        }
    }

    fun checkActiveWindowInputState() {
        val target = findActiveEditableNode()
        if (target != null) {
            lastFocusedNode = target
            notifyInputState(true)
        } else {
            notifyInputState(false)
        }
    }

    private fun isEditableNode(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        if (node.isEditable) return true
        val cls = node.className?.toString() ?: ""
        return cls.contains("EditText", ignoreCase = true) ||
                cls.contains("AutoCompleteTextView", ignoreCase = true) ||
                cls.contains("SearchAutoComplete", ignoreCase = true)
    }

    /**
     * Finds the currently active editable text node across foreground applications,
     * skipping soft keyboard (TYPE_INPUT_METHOD) and overlay windows.
     */
    fun findActiveEditableNode(): AccessibilityNodeInfo? {
        // 1. Global input focus check across all windows
        runCatching { findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()?.let { globalFocus ->
            if (isEditableNode(globalFocus)) {
                Log.d(TAG, "findActiveEditableNode: matched via service.findFocus(FOCUS_INPUT)")
                return globalFocus
            }
        }

        val windowList = runCatching { windows }.getOrNull() ?: emptyList()

        // 2. Prioritize TYPE_APPLICATION windows (the active app underneath the keyboard/bubble)
        val appWindows = windowList.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        for (window in appWindows) {
            val root = window.root ?: continue
            root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { focused ->
                if (isEditableNode(focused)) {
                    Log.d(TAG, "findActiveEditableNode: matched via appWindow.findFocus(FOCUS_INPUT)")
                    return focused
                }
            }
            searchFocusedEditable(root)?.let { focusedEditable ->
                Log.d(TAG, "findActiveEditableNode: matched via appWindow searchFocusedEditable")
                return focusedEditable
            }
            searchAnyEditable(root)?.let { anyEditable ->
                Log.d(TAG, "findActiveEditableNode: matched via appWindow searchAnyEditable")
                return anyEditable
            }
        }

        // 3. Search rootInActiveWindow
        rootInActiveWindow?.let { root ->
            root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { focused ->
                if (isEditableNode(focused)) {
                    Log.d(TAG, "findActiveEditableNode: matched via rootInActiveWindow.findFocus(FOCUS_INPUT)")
                    return focused
                }
            }
            searchFocusedEditable(root)?.let { focusedEditable ->
                Log.d(TAG, "findActiveEditableNode: matched via rootInActiveWindow searchFocusedEditable")
                return focusedEditable
            }
            searchAnyEditable(root)?.let { anyEditable ->
                Log.d(TAG, "findActiveEditableNode: matched via rootInActiveWindow searchAnyEditable")
                return anyEditable
            }
        }

        // 4. Search any remaining windows (explicitly skipping TYPE_INPUT_METHOD)
        for (window in windowList) {
            if (window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue
            val root = window.root ?: continue
            searchFocusedEditable(root)?.let { return it }
            searchAnyEditable(root)?.let { return it }
        }

        // 5. Fallback to cached lastFocusedNode
        lastFocusedNode?.let { last ->
            val refreshed = runCatching { last.refresh() }.getOrDefault(false)
            if (refreshed && isEditableNode(last)) {
                Log.d(TAG, "findActiveEditableNode: matched via refreshed lastFocusedNode")
                return last
            }
            if (isEditableNode(last)) {
                Log.d(TAG, "findActiveEditableNode: matched via lastFocusedNode fallback")
                return last
            }
        }

        return null
    }

    private fun searchFocusedEditable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isFocused && isEditableNode(node)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = searchFocusedEditable(child)
            if (found != null) return found
        }
        return null
    }

    private fun searchAnyEditable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (isEditableNode(node)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = searchAnyEditable(child)
            if (found != null) return found
        }
        return null
    }

    /**
     * Injects [text] into the currently focused editable node in the active foreground window.
     * Tries ACTION_PASTE first so that it inserts at current cursor without wiping existing text.
     * Falls back to ACTION_SET_TEXT (appending to existing text) if paste is not supported.
     */
    fun inputText(text: String): Boolean {
        if (text.isEmpty()) return false

        // Always copy text to clipboard as primary data & universal fallback
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("voice_input", text)
        clipboard.setPrimaryClip(clip)

        val target = findActiveEditableNode()
        if (target == null) {
            Log.w(TAG, "No focused editable node found across all windows and cache")
            return false
        }

        Log.i(TAG, "inputText: target=${target.className}, isEditable=${target.isEditable}, isFocused=${target.isFocused}")

        // Ensure focused
        if (!target.isFocused) {
            runCatching { target.performAction(AccessibilityNodeInfo.ACTION_FOCUS) }
        }

        isAutomatedActionInProgress = true
        try {
            // §6.1 快照捕捉時機點：必須在執行 ACTION_PASTE 動作的前一刻（前 50ms 內）即時捕捉
            val originalText = target.text?.toString() ?: ""
            val selStart = target.textSelectionStart
            val selEnd = target.textSelectionEnd
            lastSnapshot = EditorSnapshot(
                node = target,
                originalText = originalText,
                selectionStart = selStart,
                selectionEnd = selEnd
            )

            // Try ACTION_PASTE first (inserts at cursor without wiping text)
            val pasted = runCatching { target.performAction(AccessibilityNodeInfo.ACTION_PASTE) }.getOrDefault(false)
            Log.i(TAG, "ACTION_PASTE result: $pasted")
            if (pasted) {
                return true
            }

            // Fallback: ACTION_SET_TEXT (append to existing text)
            val combined = originalText + text
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, combined)
            }
            val set = runCatching { target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) }.getOrDefault(false)
            Log.i(TAG, "ACTION_SET_TEXT result: $set")
            return set
        } finally {
            mainHandler.postDelayed({ isAutomatedActionInProgress = false }, 250)
        }
    }

    /**
     * §6.1 Undo the last paste using the captured snapshot.
     */
    fun restoreLastSnapshot(): Boolean {
        val snapshot = lastSnapshot ?: return false
        val target = findActiveEditableNode() ?: snapshot.node ?: return false
        isAutomatedActionInProgress = true
        try {
            if (!target.isFocused) {
                runCatching { target.performAction(AccessibilityNodeInfo.ACTION_FOCUS) }
            }
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, snapshot.originalText)
            }
            val restored = runCatching { target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) }.getOrDefault(false)
            if (snapshot.selectionStart >= 0 && snapshot.selectionEnd >= 0) {
                val selArgs = Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, snapshot.selectionStart)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, snapshot.selectionEnd)
                }
                runCatching { target.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selArgs) }
            }
            lastSnapshot = null
            return restored
        } finally {
            mainHandler.postDelayed({ isAutomatedActionInProgress = false }, 250)
        }
    }
}