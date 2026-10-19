package com.fskeyboard.ime.keyboard

import kotlin.math.roundToInt

/** Row height of the key area, in dp. The candidate bar is separate. */
internal object KeyboardHeight {
    const val DEFAULT_ROW_DP = 46
    const val MIN_ROW_DP = 36
    const val MAX_ROW_DP = 64

    fun clamp(rowDp: Int): Int = rowDp.coerceIn(MIN_ROW_DP, MAX_ROW_DP)

    /** 100 is the default row height. */
    fun percentOfDefault(rowDp: Int): Int =
        (clamp(rowDp) * 100f / DEFAULT_ROW_DP).roundToInt()
}
