package com.fskeyboard.ime.keyboard

/**
 * Landscape clusters as fractions of the keyboard width, taken from the
 * landscape template. [SIDE] is the blank before the outer key. Letter keys
 * are [KEY] wide and [PITCH] apart. [ROW] is the row pitch and [KEY_FACE]
 * is the key height, both as fractions of that width. [SCALE] draws each
 * key around the same center. [SCALE] is larger than [FORMER_SCALE], and
 * [SIDE] is narrower, so the keys grow into the side blanks. The extra
 * row height is taken back from the landscape navigation gap.
 */
internal object SplitLayout {
    const val SIDE = 0.068f
    const val KEY = 0.040f
    const val PITCH = 0.050f
    const val RIGHT = 0.692f
    const val INDENT = 0.0105f
    const val FUNCTION = 0.047f
    const val SPACE = 0.193f
    const val ROW = 0.041f
    const val KEY_FACE = 0.0368f
    const val SCALE = 0.84f

    /** Draw scale before the keys grew. The difference is the height to reclaim. */
    const val FORMER_SCALE = 2f / 3f

    const val LEFT_SPACE_X = 0.185f
    const val RIGHT_SPACE_X = 0.621f
    const val RIGHT_SYMBOL_X = 0.826f
    const val ENTER_X = 0.884f
    const val GLOBE_X = 0.127f

    fun leftOrigin(indent: Int): Float = SIDE + indent * INDENT

    fun rightOrigin(indent: Int): Float = RIGHT - indent * INDENT

    /** Start of a uniform key in a cluster. */
    fun keyX(origin: Float, index: Int): Float = origin + index * PITCH

    /**
     * Pulls a shorter right cluster toward the outer edge so the last key
     * still ends on the five-key edge.
     */
    fun rightOriginFor(count: Int, indent: Int, columns: Int = 5): Float {
        val missing = (columns - count).coerceAtLeast(0)
        return rightOrigin(indent) + missing * PITCH
    }

    fun rightEdge(indent: Int = 0): Float = rightOrigin(indent) + 4 * PITCH + KEY

    fun packFrom(origin: Float, widths: List<Float>): List<Pair<Float, Float>> {
        val gutter = PITCH - KEY
        var cursor = origin
        return widths.map { width ->
            val slot = cursor to width
            cursor += if (width <= KEY) PITCH else width + gutter
            slot
        }
    }

    fun packRight(widths: List<Float>, edge: Float): List<Pair<Float, Float>> {
        val gutter = PITCH - KEY
        val out = ArrayList<Pair<Float, Float>>()
        var cursor = edge
        for (width in widths.asReversed()) {
            val x = cursor - width
            out += x to width
            cursor = x - gutter
        }
        out.reverse()
        return out
    }

    /** Left edge after shrinking [templateW] around its template center. */
    fun placedX(templateX: Float, templateW: Float): Float {
        val drawn = templateW * SCALE
        return templateX + (templateW - drawn) / 2f
    }

    fun placedW(templateW: Float): Float = templateW * SCALE

    /** Key height after the same shrink, as a fraction of the keyboard width. */
    fun placedH(templateH: Float = KEY_FACE): Float = templateH * SCALE

    fun rowSpan(widthPx: Float): Float = widthPx * ROW * SCALE

    /** Pixels the larger draw scale adds, so the spacer can give them back. */
    fun growthPx(widthPx: Float, rows: Int): Int {
        if (widthPx <= 0f || rows <= 0) return 0
        val extra = widthPx * ROW * (SCALE - FORMER_SCALE) * rows
        return extra.toInt().coerceAtLeast(0)
    }
}
