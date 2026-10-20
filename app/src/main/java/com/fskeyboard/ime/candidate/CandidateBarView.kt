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

internal class CandidateBarView(context: Context) : LinearLayout(context) {
    var onCandidate: (Candidate) -> Unit = {}
    /** The multi-function key at the end of the bar. It has no function yet. */
    var onMore: () -> Unit = {}

    private val preedit = TextView(context)
    private val strip = LinearLayout(context)
    private val scroll = HorizontalScrollView(context)
    private val more = TextView(context)
    private val chips = ArrayList<TextView>()
    private var shown: List<Candidate> = emptyList()

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val pad = dp(8)
        setPadding(pad, dp(4), pad, dp(4))
        minimumHeight = dp(48)

        preedit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        preedit.maxWidth = dp(96)
        preedit.maxLines = 1
        addView(preedit, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        strip.orientation = HORIZONTAL
        strip.gravity = Gravity.CENTER_VERTICAL
        scroll.isHorizontalScrollBarEnabled = false
        scroll.addView(strip, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        addView(scroll, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))

        more.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        more.gravity = Gravity.CENTER
        more.minWidth = dp(40)
        more.setPadding(dp(8), dp(6), dp(8), dp(6))
        more.background = chipBackground(0)
        more.text = "⋯"
        more.contentDescription = "更多功能"
        more.setOnClickListener { onMore() }
        addView(more, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    fun render(state: CandidateUiState) {
        val palette = KeyboardPalette.of(isNightMode())
        setBackgroundColor(palette.background)
        preedit.setTextColor(if (state.preedit.isEmpty()) palette.hint else palette.text)
        preedit.text = state.preedit.ifEmpty { state.message }
        more.setTextColor(palette.text)
        (more.background as? GradientDrawable)?.setColor(palette.accent)

        if (shown == state.candidates) return
        shown = state.candidates
        state.candidates.forEachIndexed { index, candidate ->
            val view = if (index < chips.size) {
                chips[index]
            } else {
                newChip().also {
                    chips += it
                    strip.addView(it)
                }
            }
            bind(view, candidate, emphasized = index == 0, palette)
            view.visibility = VISIBLE
        }
        for (index in state.candidates.size until chips.size) {
            chips[index].visibility = GONE
        }
    }

    private fun newChip(): TextView {
        return TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = chipBackground(0)
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(4)
                marginEnd = dp(4)
            }
        }
    }

    private fun bind(view: TextView, candidate: Candidate, emphasized: Boolean, palette: KeyboardPalette) {
        view.text = if (candidate.comment.isEmpty()) {
            candidate.text
        } else {
            "${candidate.text} ${candidate.comment}"
        }
        view.contentDescription = candidate.text
        view.setTextColor(palette.text)
        view.setOnClickListener { onCandidate(candidate) }
        (view.background as? GradientDrawable)?.setColor(
            if (emphasized) palette.accent else palette.key,
        )
    }

    private fun chipBackground(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            setColor(color)
        }
    }
}
