package com.ping.elderlyassistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.ping.elderlyassistant.BuildConfig

/**
 * Core accessibility bridge.
 *
 * Phase 1 responsibilities:
 *   - Track the currently active package name and window state
 *   - Expose captureNodeTree() for on-demand node serialization
 *   - Log window changes to Logcat under TAG_WINDOW / TAG_NODES
 *
 * Phase 3+ will add:
 *   - performClick(nodeId) driven by AI action JSON
 *   - performType(nodeId, text) for text input
 *   - Gesture dispatch via dispatchGesture()
 */
class AssistantAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG_WINDOW = "ASSISTANT_WINDOW"
        private const val TAG_NODES  = "ASSISTANT_NODES"

        /** Singleton reference so FloatingBubbleService can call captureNodeTree(). */
        @Volatile
        var instance: AssistantAccessibilityService? = null
            private set
    }

    var currentPackage: String = ""
        private set

    // ── Lifecycle ────────────────────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG_WINDOW, "AccessibilityService connected")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        Log.i(TAG_WINDOW, "AccessibilityService disconnected")
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {
        Log.w(TAG_WINDOW, "AccessibilityService interrupted")
    }

    // ── Event handling ────────────────────────────────────────────────────────

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val pkg = event.packageName?.toString() ?: return
                if (pkg == currentPackage) return      // same app, ignore
                currentPackage = pkg
                Log.i(TAG_WINDOW, "App switched → $pkg | class=${event.className}")
                if (BuildConfig.DEBUG) logNodeTree()
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // Throttle: only log if source has content we care about
                val src = event.source ?: return
                if (src.text != null || src.contentDescription != null) {
                    Log.v(TAG_NODES, "Content changed in $currentPackage")
                }
            }

            else -> { /* no-op */ }
        }
    }

    // ── Public API (called by FloatingBubbleService / future Pipeline) ────────

    /**
     * Serializes the current foreground window's node tree into a compact
     * string ready to be injected into the LLM prompt.
     *
     * Returns null if the service is not connected or the window is empty.
     */
    fun captureNodeTree(): NodeSerializer.SerializedTree? {
        val root: AccessibilityNodeInfo = rootInActiveWindow ?: run {
            Log.w(TAG_NODES, "rootInActiveWindow is null")
            return null
        }
        val tree = NodeSerializer.serialize(root, currentPackage)
        Log.d(TAG_NODES, "Captured ${tree.nodeCount} nodes for $currentPackage")
        Log.v(TAG_NODES, "\n${tree.text}")
        return tree
    }

    /**
     * Compact LLM-optimised serialisation (≤35 nodes, interactive-first).
     * Use this variant when building the Qwen3 prompt to stay within the token budget.
     */
    fun captureNodeTreeForLlm(): NodeSerializer.SerializedTree? {
        val root: AccessibilityNodeInfo = rootInActiveWindow ?: return null
        return NodeSerializer.serializeForLlm(root, currentPackage)
    }

    /**
     * Performs a click on the node whose viewIdResourceName matches [nodeId].
     * Returns true if the node was found and clicked.
     *
     * Used by Phase 3+ action executor.
     */
    fun performClickById(nodeId: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByViewId(nodeId)
        val target = nodes.firstOrNull { it.isClickable && it.isVisibleToUser }
        return target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
    }

    /**
     * Types [text] into the node whose viewIdResourceName matches [nodeId].
     */
    fun performTypeById(nodeId: String, text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByViewId(nodeId)
        val target = nodes.firstOrNull { it.isEditable && it.isVisibleToUser }
        if (target == null) return false
        target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = android.os.Bundle().apply {
            putString(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /**
     * Dispatches a tap gesture at screen coordinates ([x], [y]).
     * Used as fallback when no matching viewId is available.
     */
    fun performTapAt(x: Float, y: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private fun logNodeTree() {
        val tree = captureNodeTree() ?: return
        Log.i(TAG_NODES, "=== Node tree for $currentPackage (${tree.nodeCount} nodes) ===")
        tree.text.lines().forEach { Log.i(TAG_NODES, it) }
    }
}
