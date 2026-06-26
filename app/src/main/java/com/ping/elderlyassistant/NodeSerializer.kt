package com.ping.elderlyassistant

import android.view.accessibility.AccessibilityNodeInfo

/**
 * Converts an AccessibilityNodeInfo tree into two formats:
 *
 *  serialize()      — balanced (up to 60 nodes), used for Phase 1 debug logging
 *  serializeForLlm() — aggressive (up to 35 nodes), used for LLM prompting.
 *                     Prioritises interactive nodes; strips purely structural ones.
 *
 * Output format (one line per node):
 *   [0] Button(clickable) "撥打" id=com.android.dialer:id/call_button
 *   [1] EditText(editable,focusable) "搜尋聯絡人" id=...
 */
object NodeSerializer {

    private const val MAX_NODES_DEBUG = 60
    const val MAX_NODES_LLM          = 35   // CPU (2048-token context)
    const val MAX_NODES_LLM_GPU      = 20   // GPU (1024-token context)

    // Class names that are purely layout containers — skip unless they have text
    private val CONTAINER_CLASSES = setOf(
        "LinearLayout", "RelativeLayout", "FrameLayout", "ConstraintLayout",
        "CoordinatorLayout", "ScrollView", "HorizontalScrollView",
        "RecyclerView", "ListView", "GridView", "ViewGroup", "View"
    )

    data class SerializedTree(
        val packageName: String,
        val text: String,
        val nodeCount: Int
    )

    // ── Public API ─────────────────────────────────────────────────────────────

    /** Broader serialisation for Logcat / Phase 1 debugging. */
    fun serialize(root: AccessibilityNodeInfo?, packageName: String): SerializedTree =
        serializeInternal(root, packageName, MAX_NODES_DEBUG, aggressive = false)

    /**
     * Compact serialisation optimised for LLM token budget.
     *   - Drops layout containers with no useful text
     *   - Promotes interactive nodes to top of output
     *   - Truncates at [maxNodes] with a summary line
     */
    fun serializeForLlm(
        root: AccessibilityNodeInfo?,
        packageName: String,
        maxNodes: Int = MAX_NODES_LLM
    ): SerializedTree = serializeInternal(root, packageName, maxNodes, aggressive = true)

    // ── Implementation ────────────────────────────────────────────────────────

    private fun serializeInternal(
        root: AccessibilityNodeInfo?,
        packageName: String,
        maxNodes: Int,
        aggressive: Boolean
    ): SerializedTree {
        if (root == null) return SerializedTree(packageName, "(no window)", 0)

        val allNodes = mutableListOf<NodeInfo>()
        collectBfs(root, allNodes, maxNodes * 3, aggressive)   // gather generously, then prune

        val selected = if (aggressive) pruneForLlm(allNodes, maxNodes) else allNodes.take(maxNodes)

        val lines = selected.mapIndexed { idx, n -> format(idx, n) }
        val suffix = if (aggressive && allNodes.size > maxNodes)
            "\n... (共 ${allNodes.size} 個節點，已截斷至前 $maxNodes 個最相關節點)" else ""

        return SerializedTree(packageName, lines.joinToString("\n") + suffix, lines.size)
    }

    // ── BFS collection ─────────────────────────────────────────────────────────

    private data class NodeInfo(
        val node: AccessibilityNodeInfo,
        val depth: Int,
        val score: Int   // higher = more LLM-relevant
    )

    private fun collectBfs(
        root: AccessibilityNodeInfo,
        out: MutableList<NodeInfo>,
        cap: Int,
        aggressive: Boolean
    ) {
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)

        while (queue.isNotEmpty() && out.size < cap) {
            val (node, depth) = queue.removeFirst()
            if (!node.isVisibleToUser) continue

            if (shouldInclude(node, aggressive)) {
                out.add(NodeInfo(node, depth, relevanceScore(node)))
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it to depth + 1) }
            }
        }
    }

    private fun shouldInclude(node: AccessibilityNodeInfo, aggressive: Boolean): Boolean {
        val hasText  = !node.text.isNullOrBlank()
        val hasDesc  = !node.contentDescription.isNullOrBlank()
        val hasId    = !node.viewIdResourceName.isNullOrBlank()
        val isActive = node.isClickable || node.isLongClickable || node.isEditable ||
                node.isFocusable || node.isScrollable || node.isCheckable

        return if (aggressive) {
            // Strict: must have text OR description, or be an interactive element with an ID
            (hasText || hasDesc) || (isActive && hasId)
        } else {
            // Relaxed: any node with text, description, or interactive + ID
            hasText || hasDesc || (isActive && hasId)
        }
    }

    /** Higher = shown earlier / kept during pruning. */
    private fun relevanceScore(node: AccessibilityNodeInfo): Int {
        var score = 0
        if (node.isClickable)   score += 10
        if (node.isEditable)    score += 12
        if (node.isScrollable)  score += 5
        if (!node.text.isNullOrBlank()) score += 8
        if (!node.contentDescription.isNullOrBlank()) score += 6
        if (!node.viewIdResourceName.isNullOrBlank()) score += 4
        if (node.isEnabled)     score += 2
        return score
    }

    /** For LLM: sort by relevanceScore (interactive nodes first), de-dup, skip pure containers. */
    private fun pruneForLlm(nodes: List<NodeInfo>, max: Int): List<NodeInfo> {
        val seen = mutableSetOf<String>()
        val result = mutableListOf<NodeInfo>()

        for (n in nodes.sortedByDescending { it.score }) {
            if (result.size >= max) break
            val cls = simpleClassName(n.node.className?.toString())
            if (cls in CONTAINER_CLASSES && n.node.text.isNullOrBlank() &&
                n.node.contentDescription.isNullOrBlank()) continue
            val key = dedupeKey(n.node)
            if (seen.add(key)) result.add(n)
        }

        return result
    }

    // ── Formatting ────────────────────────────────────────────────────────────

    private fun format(index: Int, info: NodeInfo): String {
        val node  = info.node
        val type  = simpleClassName(node.className?.toString())
        val flags = buildList {
            if (node.isClickable)  add("clickable")
            if (node.isEditable)   add("editable")
            if (node.isScrollable) add("scrollable")
            if (node.isFocusable && !node.isClickable && !node.isEditable) add("focusable")
            if (node.isCheckable)  add("checkable")
            if (!node.isEnabled)   add("disabled")
        }.joinToString(",")

        val typeStr  = if (flags.isNotEmpty()) "$type($flags)" else type
        val label    = (node.text ?: node.contentDescription)?.toString()?.trim()
        val labelStr = if (!label.isNullOrBlank()) " \"$label\"" else ""
        val id       = node.viewIdResourceName
        val idStr    = if (!id.isNullOrBlank()) " id=$id" else ""
        val indent   = "  ".repeat(info.depth.coerceAtMost(3))

        return "[$index] $indent$typeStr$labelStr$idStr"
    }

    private fun dedupeKey(node: AccessibilityNodeInfo): String {
        val id    = node.viewIdResourceName ?: ""
        val text  = node.text?.toString() ?: ""
        val desc  = node.contentDescription?.toString() ?: ""
        return "$id|$text|$desc"
    }

    private fun simpleClassName(fullName: String?): String =
        if (fullName.isNullOrBlank()) "View" else fullName.substringAfterLast('.')
}
