package com.ping.voiceim

import android.content.Context
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.widget.TextView

/**
 * Non-focusable TextView with custom long-press + drag gesture to report
 * a character selection range. Avoids setTextIsSelectable() so the system
 * never steals window focus and dismisses the IME.
 */
class SelectableTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : TextView(context, attrs, defStyleAttr) {

    var onSelectionChanged: ((selStart: Int, selEnd: Int) -> Unit)? = null
    var onSingleTap: ((offset: Int) -> Unit)? = null

    /**
     * When the displayed text has a "|" cursor inserted at position [displayCursorAt],
     * charIndexAt() and drag offsets compensate so all reported indices stay in
     * logical-text (pendingText) coordinates.  Set to -1 when no cursor is inserted.
     */
    var displayCursorAt: Int = -1

    private var selAnchor  = -1
    private var isDragging = false

    private val gestureDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (text.isEmpty()) return false
                val offset = logicalOffsetAt(e.x, e.y).coerceIn(0, logicalLength())
                onSingleTap?.invoke(offset)
                return true
            }
            override fun onLongPress(e: MotionEvent) {
                if (text.isEmpty()) return
                val charIdx = charIndexAt(e.x, e.y)
                selAnchor  = charIdx
                isDragging = true
                parent?.requestDisallowInterceptTouchEvent(true)
                fire(charIdx, charIdx + 1)
            }
        })

    init {
        isFocusable            = false
        isFocusableInTouchMode = false
        isLongClickable        = false
    }

    /** Stop any in-progress drag — call this when the candidate panel opens. */
    fun cancelDrag() {
        isDragging = false
        parent?.requestDisallowInterceptTouchEvent(false)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        when (event.action) {
            MotionEvent.ACTION_MOVE -> if (isDragging) {
                val len    = logicalLength()
                val offset = logicalOffsetAt(event.x, event.y).coerceIn(0, (len - 1).coerceAtLeast(0))
                val start  = minOf(selAnchor, offset)
                val end    = (maxOf(selAnchor, offset) + 1).coerceAtMost(len)
                if (end > start) fire(start, end)
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    /** Length of the logical text (excluding any inserted "|" cursor character). */
    private fun logicalLength(): Int =
        if (displayCursorAt >= 0) text.length - 1 else text.length

    /**
     * Convert a raw cursor position from the displayed layout to a logical cursor
     * position in pendingText coordinates (compensating for an inserted "|").
     */
    private fun rawToLogical(raw: Int): Int =
        if (displayCursorAt >= 0 && raw >= displayCursorAt) raw - 1 else raw

    private fun logicalOffsetAt(x: Float, y: Float): Int {
        val l = layout ?: return -1
        val line = l.getLineForVertical(y.toInt() + scrollY)
        return rawToLogical(l.getOffsetForHorizontal(line, x + scrollX))
    }

    /**
     * Return the CHARACTER INDEX (0-based, in logical/pendingText coordinates)
     * under the touch point.
     *
     * [getOffsetForHorizontal] returns a CURSOR position (between characters),
     * so pressing the right half of char i returns i+1.  We compare against
     * the cursor's actual x position (getPrimaryHorizontal) to resolve ambiguity.
     * Then we subtract 1 for any inserted "|" at or before the result.
     */
    private fun charIndexAt(x: Float, y: Float): Int {
        val l      = layout ?: return 0
        val line   = l.getLineForVertical(y.toInt() + scrollY)
        val raw    = l.getOffsetForHorizontal(line, x + scrollX)
        val touchX = x + scrollX

        val charIdx = when {
            raw <= 0           -> 0
            raw >= text.length -> text.length - 1
            else -> if (touchX < l.getPrimaryHorizontal(raw)) raw - 1 else raw
        }

        // Compensate for inserted "|": positions at or after the cursor shift by -1
        val adjusted   = if (displayCursorAt >= 0 && charIdx >= displayCursorAt) charIdx - 1 else charIdx
        val effectiveMax = (logicalLength() - 1).coerceAtLeast(0)
        return adjusted.coerceIn(0, effectiveMax)
    }

    private fun fire(start: Int, end: Int) = onSelectionChanged?.invoke(start, end)
}
