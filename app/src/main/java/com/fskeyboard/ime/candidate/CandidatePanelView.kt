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
 * Every candidate, in equal cells, scrolled inside the keyboard.
 * The stage gives this view the keyboard's exact height, so the list scrolls
 * instead of making the window taller.
 */
internal class CandidatePanelView(context: Context) : ScrollView(context) {
    var onCandidate: (Candidate) -> Unit = {}
    var onCandidateHold: (View, Candidate) -> Unit = { _, _ -> }

    private val grid = CandidateGrid(context)
    private val chips = ArrayList<TextView>()
    private var shown: List<Candidate> = emptyList()

    init {
        isClickable = true
        isVerticalScrollBarEnabled = true
        isFillViewport = false
        grid.setPadding(dp(8), dp(8), dp(8), dp(8))
        addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    fun render(candidates: List<Candidate>) {
        val palette = KeyboardPalette.of(isNightMode())
        setBackgroundColor(palette.background)
        if (shown == candidates) return
        shown = candidates
        candidates.forEachIndexed { index, candidate ->
            val view = chips.getOrNull(index) ?: CandidateChips.create(this).also {
                chips += it
                grid.addView(it)
            }
            CandidateChips.bind(
                view,
                candidate,
                emphasized = index == 0,
                palette,
                onCandidate,
                onLongClick = { chosen -> onCandidateHold(view, chosen) },
            )
            view.visibility = VISIBLE
        }
        for (index in candidates.size until chips.size) {
            chips[index].visibility = GONE
        }
        scrollTo(0, 0)
    }
}

/**
 * Equal cells, four to six across. A word wider than one cell takes the width
 * it needs and the row holds fewer of them. The glyph size stays put.
 */
private class CandidateGrid(context: Context) : ViewGroup(context) {
    private val gap = dp(8)
    private val cellHeight = dp(44)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val inner = (width - paddingLeft - paddingRight).coerceAtLeast(0)
        val widths = measureCells(inner)
        val rows = rowCount(widths, inner)
        val content = if (rows == 0) 0 else rows * cellHeight + (rows - 1) * gap
        val height = content + paddingTop + paddingBottom
        setMeasuredDimension(width, resolveSize(height, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val inner = (width - paddingLeft - paddingRight).coerceAtLeast(0)
        val widths = measureCells(inner)
        var x = 0
        var y = 0
        var open = false
        var index = 0
        for (childIndex in 0 until childCount) {
            val child = getChildAt(childIndex)
            if (child.visibility == GONE) continue
            val wanted = widths[index]
            index += 1
            if (open && x + wanted > inner) {
                x = 0
                y += cellHeight + gap
                open = false
            }
            val left = paddingLeft + x
            val top = paddingTop + y
            child.layout(left, top, left + wanted, top + cellHeight)
            x += wanted + gap
            open = true
        }
    }

    private fun measureCells(inner: Int): List<Int> {
        if (inner <= 0) return emptyList()
        val cols = candidateColumns(inner / resources.displayMetrics.density)
        val cell = ((inner - gap * (cols - 1)) / cols).coerceAtLeast(1)
        val widths = ArrayList<Int>()
        val heightSpec = MeasureSpec.makeMeasureSpec(cellHeight, MeasureSpec.EXACTLY)
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility == GONE) continue
            child.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), heightSpec)
            val wanted = maxOf(cell, child.measuredWidth).coerceAtMost(inner)
            child.measure(MeasureSpec.makeMeasureSpec(wanted, MeasureSpec.EXACTLY), heightSpec)
            widths += wanted
        }
        return widths
    }

    private fun rowCount(widths: List<Int>, inner: Int): Int {
        if (widths.isEmpty() || inner <= 0) return 0
        var rows = 0
        var x = 0
        var open = false
        for (wanted in widths) {
            if (open && x + wanted > inner) {
                x = 0
                open = false
            }
            if (!open) rows += 1
            x += wanted + gap
            open = true
        }
        return rows
    }
}
