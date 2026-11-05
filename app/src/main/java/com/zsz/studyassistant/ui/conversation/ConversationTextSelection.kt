package com.zsz.studyassistant.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.WebView
import android.widget.Toast
import org.json.JSONObject

data class QuoteSelection(val text: String, val anchor: com.zsz.studyassistant.QuoteAnchor? = null)

/** Keep Chromium's selection handles and standard actions; add one app-owned action. */
internal open class ConversationTextSelection(context: Context) : WebView(context) {
    var quoteLabel: String = ""
    var selectionHint: String = ""
    var quoteEnabled: Boolean = false
    var onQuote: (Int, String, String, JSONObject) -> Unit = { _, _, _, _ -> }

    override fun startActionMode(callback: ActionMode.Callback): ActionMode? =
        super.startActionMode(wrap(callback))

    override fun startActionMode(callback: ActionMode.Callback, type: Int): ActionMode? =
        super.startActionMode(wrap(callback), type)

    private fun wrap(callback: ActionMode.Callback): ActionMode.Callback =
        if (callback is SelectionCallback) callback else SelectionCallback(callback)

    private inner class SelectionCallback(val delegate: ActionMode.Callback) : ActionMode.Callback2() {
        private fun updateMenu(menu: Menu) {
            val item = menu.findItem(QUOTE_ID) ?: menu.add(Menu.NONE, QUOTE_ID, 0, quoteLabel).apply {
                // Floating toolbars prioritize required actions over optional/overflow items.
                // Keep system actions intact; OEM assist actions may still take the first slot.
                setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS or MenuItem.SHOW_AS_ACTION_WITH_TEXT)
            }
            item.title = quoteLabel
            item.isVisible = quoteEnabled
        }

        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            if (!delegate.onCreateActionMode(mode, menu)) return false
            updateMenu(menu)
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
            delegate.onPrepareActionMode(mode, menu)
            updateMenu(menu)
            return true
        }

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            if (item.itemId == android.R.id.selectAll) {
                // Select this message's text, not the entire conversation and hidden controls.
                evaluateJavascript("window.dshSelectMessageText && window.dshSelectMessageText();") { result ->
                    if (result != "true") delegate.onActionItemClicked(mode, item)
                }
                return true
            }
            if (item.itemId != QUOTE_ID && item.itemId != android.R.id.copy) {
                return delegate.onActionItemClicked(mode, item)
            }
            val quoting = item.itemId == QUOTE_ID
            if (quoting && !quoteEnabled) return true
            // Read before finishing ActionMode: finishing first collapses Chromium's selection.
            evaluateJavascript("window.dshSelectedText ? window.dshSelectedText() : null;") { result ->
                val selected = runCatching { JSONObject(result) }.getOrNull()
                val text = selected?.optString("text").orEmpty()
                if (text.isNotBlank()) {
                    if (quoting) {
                        if (quoteEnabled) onQuote(selected!!.optInt("idx", -1), text, selected.optString("source"), selected)
                    } else {
                        context.getSystemService(ClipboardManager::class.java)
                            .setPrimaryClip(ClipData.newPlainText(null, text))
                    }
                    mode.finish()
                    evaluateJavascript("window.getSelection().removeAllRanges();", null)
                } else {
                    if (quoting) Toast.makeText(context, selectionHint, Toast.LENGTH_SHORT).show()
                    else delegate.onActionItemClicked(mode, item)
                }
            }
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode) = delegate.onDestroyActionMode(mode)

        override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
            if (delegate is ActionMode.Callback2) delegate.onGetContentRect(mode, view, outRect)
            else super.onGetContentRect(mode, view, outRect)
        }
    }

    private companion object {
        val QUOTE_ID = View.generateViewId()
    }
}
