package com.fskeyboard.ime.candidate

import android.app.AlertDialog
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.fskeyboard.ime.R
import com.fskeyboard.ime.ui.KeyboardPalette
import com.fskeyboard.ime.ui.dp
import com.fskeyboard.ime.ui.isNightMode

/**
 * Candidates in one scrolling row. The zhuyin being typed shows only in the editor.
 * The text on the left is a status message, shown only when there is one.
 * The arrow on the right opens every candidate over the keys.
 * The bar is at least 40dp tall; chips are 18sp with 4dp above and below.
 * The chips are one [CandidateStrip], not a view apiece.
 */
internal class CandidateBarView(context: Context) : LinearLayout(context) {
    var onCandidate: (Candidate) -> Unit = {}
    var onExpand: () -> Unit = {}

    /** Clears the system clipboard after the user confirms the long-press prompt. */
    var onClearClipboard: () -> Unit = {}

    /** Whether the candidate panel is open; flips the arrow. */
    var expanded: Boolean = false
        set(value) {
            field = value
            expand.text = if (value) "▴" else "▾"
            expand.contentDescription = if (value) "收合候選字" else "展開候選字"
        }

    private val status = TextView(context)
    private val strip = CandidateStrip(context)
    private val scroll = HorizontalScrollView(context)
    private val expand = TextView(context)
    private var shown: List<Candidate> = emptyList()
    private var clipboardPrompt: AlertDialog? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val pad = dp(8)
        setPadding(pad, dp(2), pad, dp(2))
        minimumHeight = dp(40)

        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        status.maxLines = 1
        status.visibility = GONE
        addView(status, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        strip.onCandidate = { onCandidate(it) }
        strip.onClipboardHold = { showClipboardPrompt() }
        scroll.isHorizontalScrollBarEnabled = false
        scroll.addView(strip, FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        addView(scroll, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))

        sideKey(expand, "▾", "展開候選字") { onExpand() }
        expand.visibility = INVISIBLE
    }

    fun render(state: CandidateUiState) {
        val palette = KeyboardPalette.of(isNightMode())
        setBackgroundColor(palette.background)
        status.setTextColor(palette.hint)
        status.text = state.message
        status.visibility = if (state.message.isEmpty()) GONE else VISIBLE
        scroll.visibility = if (state.message.isEmpty()) VISIBLE else GONE
        expand.setTextColor(palette.text)
        (expand.background as? GradientDrawable)?.setColor(palette.accent)
        val clipboard = state.candidates.singleOrNull()?.source == CandidateSource.CLIPBOARD
        expand.visibility = when {
            clipboard -> GONE
            state.candidates.isEmpty() -> INVISIBLE
            else -> VISIBLE
        }
        placeStrip(clipboard)
        if (!clipboard) dismissClipboardPrompt()
        val changed = shown != state.candidates
        shown = state.candidates
        strip.show(state.candidates, palette)
        if (changed) scroll.scrollTo(0, 0)
    }

    override fun onDetachedFromWindow() {
        dismissClipboardPrompt()
        super.onDetachedFromWindow()
    }

    /** A clipboard chip is one short row, so it sits in the middle instead of at the leading edge. */
    private fun placeStrip(clipboard: Boolean) {
        val params = strip.layoutParams as? FrameLayout.LayoutParams ?: return
        val gravity = if (clipboard) Gravity.CENTER else Gravity.CENTER_VERTICAL or Gravity.START
        if (params.gravity == gravity) return
        params.gravity = gravity
        strip.layoutParams = params
    }

    private fun showClipboardPrompt() {
        if (windowToken == null || clipboardPrompt?.isShowing == true) return
        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.clipboard_prompt_title)
            .setMessage(R.string.clipboard_prompt_message)
            .setPositiveButton(R.string.clipboard_clear) { _, _ -> onClearClipboard() }
            .setNegativeButton(R.string.clipboard_dismiss, null)
            .create()
        dialog.setOnDismissListener {
            if (clipboardPrompt === dialog) clipboardPrompt = null
        }
        val window = dialog.window
        if (window == null) return
        window.setType(WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG)
        window.attributes = window.attributes.apply { token = windowToken }
        clipboardPrompt = dialog
        try {
            dialog.show()
        } catch (_: Exception) {
            clipboardPrompt = null
        }
    }

    private fun dismissClipboardPrompt() {
        clipboardPrompt?.dismiss()
        clipboardPrompt = null
    }

    private fun sideKey(view: TextView, label: String, description: String, onClick: () -> Unit) {
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        view.gravity = Gravity.CENTER
        view.minWidth = dp(40)
        view.setPadding(dp(8), dp(4), dp(8), dp(4))
        view.background = CandidateChips.rounded(this, 0)
        view.text = label
        view.contentDescription = description
        view.setOnClickListener { onClick() }
        addView(
            view,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(4)
            },
        )
    }
}
