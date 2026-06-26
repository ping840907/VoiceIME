package com.ping.voiceim

import android.content.Context
import android.util.AttributeSet
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.widget.AppCompatTextView

/**
 * A selectable TextView that:
 * - Fires [onSelectionChanged] whenever the selection range changes.
 * - Replaces the system copy/paste action bar with a single "套用詞彙" item.
 *   Tapping it fires [onApplyRequested] and closes the action mode.
 */
class SelectableTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : AppCompatTextView(context, attrs, defStyleAttr) {

    var onSelectionChanged: ((selStart: Int, selEnd: Int) -> Unit)? = null
    var onApplyRequested: (() -> Unit)? = null

    init {
        setTextIsSelectable(true)
        // Suppress the default copy/paste/share/web-search action bar and
        // replace it with our own single action.
        setCustomSelectionActionModeCallback(object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                menu.clear()
                menu.add(0, MENU_APPLY, 0, "套用詞彙")
                return true
            }
            override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false
            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                if (item.itemId == MENU_APPLY) {
                    onApplyRequested?.invoke()
                    mode.finish()
                    return true
                }
                return false
            }
            override fun onDestroyActionMode(mode: ActionMode) {}
        })
    }

    public override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        onSelectionChanged?.invoke(selStart, selEnd)
    }

    companion object {
        private const val MENU_APPLY = 1
    }
}
