package com.fskeyboard.ime.ui

internal data class EdgePadding(
    val left: Int,
    val right: Int,
    val bottom: Int,
)

/**
 * [reportedBottom] is null until the window actually reports insets.
 * A missing report uses the navigation-bar fallback so the first frame does
 * not draw keys underneath the bar. An explicit 0 means there is no bar.
 */
internal object KeyboardInsets {
    fun resolve(left: Int, right: Int, reportedBottom: Int?, fallbackBottom: Int): EdgePadding {
        val bottom = (reportedBottom ?: fallbackBottom).coerceAtLeast(0)
        return EdgePadding(
            left = left.coerceAtLeast(0),
            right = right.coerceAtLeast(0),
            bottom = bottom,
        )
    }
}
