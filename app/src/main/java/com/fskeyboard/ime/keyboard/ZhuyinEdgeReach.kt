package com.fskeyboard.ime.keyboard

/**
 * ㄇ sits against a left blank and ㄣ against a right blank on the staggered rows.
 * The hit target takes most of that blank. The drawn key takes half, so the
 * wider key is visible and a tap in the rest of the blank still lands.
 */
internal object ZhuyinEdgeReach {
    const val HIT = 0.85f
    const val DRAW = 0.5f

    data class Reach(
        val hitLeft: Float,
        val hitRight: Float,
        val drawLeft: Float,
        val drawRight: Float,
    )

    fun adjust(
        label: String,
        left: Float,
        right: Float,
        blankLeft: Float,
        blankRight: Float,
    ): Reach? {
        if (left >= right) return null
        return when (label) {
            "ㄇ" -> if (blankLeft <= 0f) null else expandLeft(left, right, blankLeft)
            "ㄣ" -> if (blankRight <= 0f) null else expandRight(left, right, blankRight)
            else -> null
        }
    }

    private fun expandLeft(left: Float, right: Float, blank: Float) = Reach(
        hitLeft = left - blank * HIT,
        hitRight = right,
        drawLeft = left - blank * DRAW,
        drawRight = right,
    )

    private fun expandRight(left: Float, right: Float, blank: Float) = Reach(
        hitLeft = left,
        hitRight = right + blank * HIT,
        drawLeft = left,
        drawRight = right + blank * DRAW,
    )
}
