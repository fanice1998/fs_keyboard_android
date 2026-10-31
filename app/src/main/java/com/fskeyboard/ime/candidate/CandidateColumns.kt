package com.fskeyboard.ime.candidate

import kotlin.math.roundToInt

/**
 * Expanded candidates use equal cells. [widthDp] is the width those cells share.
 * A narrow phone stays at four. A wide or landscape screen stops at six.
 */
internal fun candidateColumns(widthDp: Float): Int {
    if (widthDp <= 0f) return 4
    return (widthDp / 88f).roundToInt().coerceIn(4, 6)
}
