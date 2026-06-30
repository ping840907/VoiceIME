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

    private var selAnchor  = -1
    private var isDragging = false

    private val gestureDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (text.isEmpty()) return false
                val offset = offsetAt(e.x, e.y).coerceIn(0, text.length)
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

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        when (event.action) {
            MotionEvent.ACTION_MOVE -> if (isDragging) {
                val offset = offsetAt(event.x, event.y).coerceIn(0, (text.length - 1).coerceAtLeast(0))
                val start  = minOf(selAnchor, offset)
                val end    = (maxOf(selAnchor, offset) + 1).coerceAtMost(text.length)
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

    private fun offsetAt(x: Float, y: Float): Int {
        val l = layout ?: return -1
        val line = l.getLineForVertical(y.toInt() + scrollY)
        return l.getOffsetForHorizontal(line, x + scrollX)
    }

    /**
     * Return the CHARACTER INDEX (0-based) under the touch point.
     *
     * [getOffsetForHorizontal] returns a CURSOR position (between characters),
     * so pressing the right half of char i returns i+1. We compare against
     * the cursor's actual x position (getPrimaryHorizontal) to resolve ambiguity.
     */
    private fun charIndexAt(x: Float, y: Float): Int {
        val l    = layout ?: return 0
        val line = l.getLineForVertical(y.toInt() + scrollY)
        val raw  = l.getOffsetForHorizontal(line, x + scrollX)
        val touchX = x + scrollX
        return when {
            raw <= 0           -> 0
            raw >= text.length -> text.length - 1
            else -> {
                // raw is ambiguous: could be right-half of char (raw-1) or left-half of char raw.
                // getPrimaryHorizontal(raw) is the boundary between the two chars.
                if (touchX < l.getPrimaryHorizontal(raw)) raw - 1 else raw
            }
        }.coerceIn(0, text.length - 1)
    }

    private fun fire(start: Int, end: Int) = onSelectionChanged?.invoke(start, end)
}
