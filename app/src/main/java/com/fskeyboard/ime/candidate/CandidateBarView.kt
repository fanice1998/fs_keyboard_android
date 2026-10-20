package com.fskeyboard.ime.candidate

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.fskeyboard.ime.ui.KeyboardPalette
import com.fskeyboard.ime.ui.dp
import com.fskeyboard.ime.ui.isNightMode

/**
 * Candidates in one scrolling row. The zhuyin being typed shows only in the editor.
 * The text on the left is a status message, shown only when there is one.
 * The arrow on the right opens every candidate over the keys.
 */
internal class CandidateBarView(context: Context) : LinearLayout(context) {
    var onCandidate: (Candidate) -> Unit = {}

    /** The multi-function key next to the arrow. It has no function yet. */
    var onMore: () -> Unit = {}
    var onExpand: () -> Unit = {}

    /** Whether the candidate panel is open; flips the arrow. */
    var expanded: Boolean = false
        set(value) {
            field = value
            expand.text = if (value) "▴" else "▾"
            expand.contentDescription = if (value) "收合候選字" else "展開候選字"
        }

    private val status = TextView(context)
    private val strip = LinearLayout(context)
    private val scroll = HorizontalScrollView(context)
    private val more = TextView(context)
    private val expand = TextView(context)
    private val chips = ArrayList<TextView>()
    private var shown: List<Candidate> = emptyList()

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val pad = dp(8)
        setPadding(pad, dp(4), pad, dp(4))
        minimumHeight = dp(48)

        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        status.maxLines = 1
        status.visibility = GONE
        addView(status, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        strip.orientation = HORIZONTAL
        strip.gravity = Gravity.CENTER_VERTICAL
        scroll.isHorizontalScrollBarEnabled = false
        scroll.addView(strip, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        addView(scroll, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))

        sideKey(more, "⋯", "更多功能") { onMore() }
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
        for (key in listOf(more, expand)) {
            key.setTextColor(palette.text)
            (key.background as? GradientDrawable)?.setColor(palette.accent)
        }
        expand.visibility = if (state.candidates.isEmpty()) INVISIBLE else VISIBLE

        if (shown == state.candidates) return
        shown = state.candidates
        state.candidates.forEachIndexed { index, candidate ->
            val view = chips.getOrNull(index) ?: CandidateChips.create(this).also {
                it.layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                    marginStart = dp(4)
                    marginEnd = dp(4)
                }
                chips += it
                strip.addView(it)
            }
            CandidateChips.bind(view, candidate, emphasized = index == 0, palette, onCandidate)
            view.visibility = VISIBLE
        }
        for (index in state.candidates.size until chips.size) {
            chips[index].visibility = GONE
        }
        scroll.scrollTo(0, 0)
    }

    private fun sideKey(view: TextView, label: String, description: String, onClick: () -> Unit) {
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        view.gravity = Gravity.CENTER
        view.minWidth = dp(40)
        view.setPadding(dp(8), dp(6), dp(8), dp(6))
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
