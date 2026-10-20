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
    var onToggleScript: () -> Unit = {}

    private val preedit = TextView(context)
    private val strip = LinearLayout(context)
    private val scroll = HorizontalScrollView(context)
    private val script = TextView(context)
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

        script.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        script.gravity = Gravity.CENTER
        script.minWidth = dp(40)
        script.setPadding(dp(8), dp(6), dp(8), dp(6))
        script.background = chipBackground(0)
        script.setOnClickListener { onToggleScript() }
        addView(script, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    fun render(state: CandidateUiState) {
        val palette = KeyboardPalette.of(isNightMode())
        setBackgroundColor(palette.background)
        preedit.setTextColor(if (state.preedit.isEmpty()) palette.hint else palette.text)
        preedit.text = state.preedit.ifEmpty { state.message }
        script.text = if (state.traditional) "臺" else "通"
        script.setTextColor(palette.text)
        (script.background as? GradientDrawable)?.setColor(palette.accent)
        script.contentDescription = if (state.traditional) "臺灣標準" else "通用繁體"

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
