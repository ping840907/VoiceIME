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

    private var selAnchor  = -1
    private var isDragging = false

    private val gestureDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onLongPress(e: MotionEvent) {
                val offset = offsetAt(e.x, e.y)
                if (offset < 0 || text.isEmpty()) return
                selAnchor  = offset
                isDragging = true
                fire(offset, (offset + 1).coerceAtMost(text.length))
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
                val offset = offsetAt(event.x, event.y).coerceIn(0, text.length)
                val start  = minOf(selAnchor, offset)
                // always keep at least 1-char range so the panel never collapses mid-drag
                val end    = (maxOf(selAnchor, offset) + 1).coerceAtMost(text.length)
                if (end > start) fire(start, end)
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> isDragging = false
        }
        return true
    }

    private fun offsetAt(x: Float, y: Float): Int {
        val l = layout ?: return -1
        val line = l.getLineForVertical(y.toInt() + scrollY)
        return l.getOffsetForHorizontal(line, x + scrollX)
    }

    private fun fire(start: Int, end: Int) = onSelectionChanged?.invoke(start, end)
}
