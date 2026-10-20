package com.fskeyboard.ime.candidate

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import com.fskeyboard.ime.ui.KeyboardPalette
import com.fskeyboard.ime.ui.dp
import com.fskeyboard.ime.ui.isNightMode

/**
 * Every candidate, wrapped into rows. It covers the keys while open, so the keyboard
 * keeps its height. Taps on empty space do not reach the keys underneath.
 */
internal class CandidatePanelView(context: Context) : ScrollView(context) {
    var onCandidate: (Candidate) -> Unit = {}

    private val flow = FlowLayout(context, gap = dp(8))
    private val chips = ArrayList<TextView>()
    private var shown: List<Candidate> = emptyList()

    init {
        isClickable = true
        isVerticalScrollBarEnabled = true
        flow.setPadding(dp(8), dp(8), dp(8), dp(8))
        addView(flow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    fun render(candidates: List<Candidate>) {
        val palette = KeyboardPalette.of(isNightMode())
        setBackgroundColor(palette.background)
        if (shown == candidates) return
        shown = candidates
        candidates.forEachIndexed { index, candidate ->
            val view = chips.getOrNull(index) ?: CandidateChips.create(this).also {
                chips += it
                flow.addView(it)
            }
            CandidateChips.bind(view, candidate, emphasized = index == 0, palette, onCandidate)
            view.visibility = VISIBLE
        }
        for (index in candidates.size until chips.size) {
            chips[index].visibility = GONE
        }
        scrollTo(0, 0)
    }
}

/** Lays children left to right and starts a new row when the next one does not fit. */
private class FlowLayout(context: Context, private val gap: Int) : ViewGroup(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val available = (width - paddingLeft - paddingRight).coerceAtLeast(0)
        val childWidth = MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST)
        val childHeight = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility != GONE) child.measure(childWidth, childHeight)
        }
        var bottom = 0
        place(available) { child, _, top ->
            bottom = maxOf(bottom, top + child.measuredHeight)
        }
        setMeasuredDimension(width, resolveSize(bottom + paddingTop + paddingBottom, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        place(width - paddingLeft - paddingRight) { child, left, top ->
            child.layout(
                paddingLeft + left,
                paddingTop + top,
                paddingLeft + left + child.measuredWidth,
                paddingTop + top + child.measuredHeight,
            )
        }
    }

    /** Walks visible, already measured children in rows, with each one's offset. */
    private inline fun place(available: Int, visit: (View, Int, Int) -> Unit) {
        var left = 0
        var top = 0
        var rowHeight = 0
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility == GONE) continue
            if (left > 0 && left + child.measuredWidth > available) {
                left = 0
                top += rowHeight + gap
                rowHeight = 0
            }
            visit(child, left, top)
            left += child.measuredWidth + gap
            rowHeight = maxOf(rowHeight, child.measuredHeight)
        }
    }
}
