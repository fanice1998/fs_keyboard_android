package com.fskeyboard.ime.keyboard

import java.text.BreakIterator
import kotlin.math.abs
import kotlin.math.floor

/**
 * Moves a cursor by graphemes horizontally and by newline-delimited lines vertically.
 * A horizontal step stops at the ends of its line, so it does not wrap to the next one.
 * A vertical step keeps the column, and never lands in the middle of a grapheme.
 *
 * [nearest] is the glide. Finger pixels are measured from the caret where the gesture
 * started, then snapped to the closest grapheme on the closest line. Soft-wrapped
 * visual lines are not known here: a line ends at a newline.
 */
internal object CursorMotion {
    fun move(text: String, cursor: Int, dx: Int, dy: Int): Int {
        var index = cursor.coerceIn(0, text.length)
        if (dx != 0) index = shiftGraphemes(text, index, dx)
        if (dy != 0) index = shiftLines(text, index, dy)
        return index
    }

    /**
     * [dxPx] and [dyPx] are pixels from [origin], in the same space as [charWidth] and [lineHeightPx].
     * A tie goes to the boundary further along the finger. [charWidth] is measured per grapheme.
     */
    fun nearest(
        text: String,
        origin: Int,
        dxPx: Float,
        dyPx: Float,
        charWidth: (String) -> Float,
        lineHeightPx: Float,
    ): Int {
        if (text.isEmpty()) return 0
        val cursor = origin.coerceIn(0, text.length)
        val lines = linesOf(text)
        val lineIndex = lines.indexOfFirst { cursor <= it.end }.let { if (it < 0) lines.lastIndex else it }
        val line = lines[lineIndex]
        val originX = widthBetween(text, line.start, cursor.coerceIn(line.start, line.end), charWidth)
        val height = if (lineHeightPx > 0f) lineHeightPx else 1f
        val targetIndex = lineIndexFor(lineIndex + dyPx / height, dyPx, lines.lastIndex)
        val target = lines[targetIndex]
        val targetX = (originX + dxPx).coerceAtLeast(0f)
        return nearestBoundary(text, target.start, target.end, targetX, dxPx, charWidth)
    }

    private fun shiftGraphemes(text: String, cursor: Int, delta: Int): Int {
        val start = lineStart(text, cursor)
        val end = lineEnd(text, cursor)
        val breaks = BreakIterator.getCharacterInstance()
        breaks.setText(text)
        var index = cursor.coerceIn(start, end)
        repeat(abs(delta)) {
            val next = if (delta > 0) breaks.following(index) else breaks.preceding(index)
            if (next == BreakIterator.DONE || next < start || next > end) return index
            index = next
        }
        return index
    }

    private fun shiftLines(text: String, cursor: Int, lines: Int): Int {
        val column = cursor - lineStart(text, cursor)
        var start = lineStart(text, cursor)
        repeat(abs(lines)) {
            if (lines > 0) {
                val end = lineEnd(text, start)
                if (end >= text.length) return snap(text, (start + column).coerceAtMost(end))
                start = end + 1
            } else {
                if (start == 0) return snap(text, column.coerceAtMost(lineEnd(text, 0)))
                start = lineStart(text, start - 1)
            }
        }
        val end = lineEnd(text, start)
        return snap(text, (start + column).coerceIn(start, end))
    }

    private fun lineStart(text: String, cursor: Int): Int {
        if (cursor <= 0) return 0
        val newline = text.lastIndexOf('\n', cursor - 1)
        return if (newline < 0) 0 else newline + 1
    }

    private fun lineEnd(text: String, start: Int): Int {
        val newline = text.indexOf('\n', start)
        return if (newline < 0) text.length else newline
    }

    /** Pulls an index that split a grapheme back to that grapheme's start. */
    private fun snap(text: String, index: Int): Int {
        val clamped = index.coerceIn(0, text.length)
        if (clamped == 0 || clamped == text.length) return clamped
        val breaks = BreakIterator.getCharacterInstance()
        breaks.setText(text)
        if (breaks.isBoundary(clamped)) return clamped
        val previous = breaks.preceding(clamped)
        return if (previous == BreakIterator.DONE) 0 else previous
    }

    private fun linesOf(text: String): List<Line> {
        val lines = ArrayList<Line>()
        var start = 0
        while (start <= text.length) {
            val newline = text.indexOf('\n', start)
            if (newline < 0) {
                lines.add(Line(start, text.length))
                break
            }
            lines.add(Line(start, newline))
            start = newline + 1
            if (start == text.length) {
                lines.add(Line(start, start))
                break
            }
        }
        if (lines.isEmpty()) lines.add(Line(0, 0))
        return lines
    }

    /** Halfway between two lines follows [dyPx]. */
    private fun lineIndexFor(raw: Float, dyPx: Float, last: Int): Int {
        val base = floor(raw.toDouble()).toInt()
        val frac = raw - base
        val index = when {
            frac > 0.5f -> base + 1
            frac < 0.5f -> base
            dyPx > 0f -> base + 1
            else -> base
        }
        return index.coerceIn(0, last)
    }

    private fun nearestBoundary(
        text: String,
        start: Int,
        end: Int,
        targetX: Float,
        dxPx: Float,
        charWidth: (String) -> Float,
    ): Int {
        if (start >= end) return start.coerceIn(0, text.length)
        val breaks = BreakIterator.getCharacterInstance()
        breaks.setText(text)
        var boundary = start
        var width = 0f
        var best = start
        var bestDistance = abs(targetX)
        while (true) {
            val next = breaks.following(boundary)
            if (next == BreakIterator.DONE || next > end) break
            width += charWidth(text.substring(boundary, next))
            val distance = abs(width - targetX)
            if (distance < bestDistance || (distance == bestDistance && furtherAlong(next, best, dxPx))) {
                best = next
                bestDistance = distance
            }
            boundary = next
        }
        return best
    }

    private fun widthBetween(
        text: String,
        start: Int,
        end: Int,
        charWidth: (String) -> Float,
    ): Float {
        if (end <= start) return 0f
        val breaks = BreakIterator.getCharacterInstance()
        breaks.setText(text)
        var boundary = start
        var width = 0f
        while (boundary < end) {
            val next = breaks.following(boundary)
            if (next == BreakIterator.DONE || next > end) break
            width += charWidth(text.substring(boundary, next))
            boundary = next
        }
        return width
    }

    private fun furtherAlong(candidate: Int, current: Int, dxPx: Float): Boolean = when {
        dxPx > 0f -> candidate > current
        dxPx < 0f -> candidate < current
        else -> false
    }

    private data class Line(val start: Int, val end: Int)
}

/**
 * Where to put the caret after setComposingText(text, 1), which leaves it at the end.
 * Null when [caret] is already there, or when the composing text does not fit before [selectionEnd].
 */
internal fun composingSelection(selectionEnd: Int, textLength: Int, caret: Int): Int? {
    if (textLength <= 0 || caret < 0 || caret >= textLength) return null
    val start = selectionEnd - textLength
    if (start < 0) return null
    return start + caret
}
