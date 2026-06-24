package com.ping.elderlyassistant

import android.view.accessibility.AccessibilityNodeInfo

/**
 * Converts an AccessibilityNodeInfo tree into a compact text representation
 * suitable for feeding to the LLM prompt.
 *
 * Output format (one line per node):
 *   [0] Button(clickable) "撥打" id=com.android.dialer:id/call_button
 *   [1] EditText(clickable,focusable) "搜尋" id=com.android.contacts:id/search_view
 */
object NodeSerializer {

    private const val MAX_NODES = 60

    data class SerializedTree(
        val packageName: String,
        val text: String,
        val nodeCount: Int
    )

    fun serialize(root: AccessibilityNodeInfo?, packageName: String): SerializedTree {
        if (root == null) return SerializedTree(packageName, "(no window)", 0)

        val lines = mutableListOf<String>()
        traverseBfs(root, lines)

        val body = lines.joinToString("\n")
        return SerializedTree(packageName, body, lines.size)
    }

    private fun traverseBfs(root: AccessibilityNodeInfo, out: MutableList<String>) {
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)

        while (queue.isNotEmpty() && out.size < MAX_NODES) {
            val (node, depth) = queue.removeFirst()

            if (shouldInclude(node)) {
                out.add(format(out.size, node, depth))
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                if (child.isVisibleToUser) queue.add(child to depth + 1)
            }
        }
    }

    private fun shouldInclude(node: AccessibilityNodeInfo): Boolean {
        val hasText = !node.text.isNullOrBlank()
        val hasDesc = !node.contentDescription.isNullOrBlank()
        val hasId = !node.viewIdResourceName.isNullOrBlank()
        val isInteractive = node.isClickable || node.isLongClickable || node.isEditable || node.isFocusable
        return node.isVisibleToUser && (hasText || hasDesc || (hasId && isInteractive))
    }

    private fun format(index: Int, node: AccessibilityNodeInfo, depth: Int): String {
        val type = simpleClassName(node.className?.toString())

        val flags = buildList {
            if (node.isClickable) add("clickable")
            if (node.isEditable) add("editable")
            if (node.isFocusable && !node.isClickable) add("focusable")
            if (!node.isEnabled) add("disabled")
        }.joinToString(",")

        val typeStr = if (flags.isNotEmpty()) "$type($flags)" else type

        val label = (node.text ?: node.contentDescription)?.toString()?.trim()
        val labelStr = if (!label.isNullOrBlank()) " \"$label\"" else ""

        val id = node.viewIdResourceName?.removePrefix(node.packageName?.toString() + ":")
        val idStr = if (!id.isNullOrBlank()) " id=$id" else ""

        val indent = "  ".repeat(depth.coerceAtMost(4))
        return "[$index] $indent$typeStr$labelStr$idStr"
    }

    private fun simpleClassName(fullName: String?): String {
        if (fullName.isNullOrBlank()) return "View"
        return fullName.substringAfterLast('.')
    }
}
