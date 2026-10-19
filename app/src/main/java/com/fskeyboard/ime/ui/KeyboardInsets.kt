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
 *
 * The keyboard-switch and hide buttons are the IME caption bar. In gesture
 * navigation that bar is taller than [navigationBars], and the two regions
 * share one bottom edge, so clearance is the tallest of them rather than
 * their sum. On Samsung the reported caption inset is often still the short
 * gesture pill, so the system frame height is a floor there.
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

    fun bottomClearance(
        navigationBars: Int,
        captionBar: Int,
        mandatoryGestures: Int,
        tappableElements: Int,
        embeddedBar: Int,
        systemFrame: Int,
        preferSystemFrame: Boolean,
    ): Int {
        val measured = maxOf(
            navigationBars,
            captionBar,
            mandatoryGestures,
            tappableElements,
            embeddedBar,
        ).coerceAtLeast(0)
        val frame = systemFrame.coerceAtLeast(0)
        return if (preferSystemFrame) maxOf(measured, frame) else measured
    }
}
