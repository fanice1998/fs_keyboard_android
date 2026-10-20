package com.fskeyboard.ime.settings

internal data class SettingsPadding(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

/**
 * Padding that keeps the settings page below the status bar and below a
 * title bar drawn over the content.
 *
 * Target SDK 36 lays the activity out edge to edge, and the framework title
 * is not a system-bar inset. [titleBottom] is that title's bottom in window
 * coordinates. The result is only the overlap, so a page that already starts
 * below the title is not padded twice.
 */
internal object SettingsSafeArea {
    fun padding(
        contentLeft: Int,
        contentTop: Int,
        contentRight: Int,
        contentBottom: Int,
        windowWidth: Int,
        windowHeight: Int,
        titleBottom: Int,
        insetLeft: Int,
        insetTop: Int,
        insetRight: Int,
        insetBottom: Int,
    ): SettingsPadding {
        if (windowWidth <= 0 || windowHeight <= 0) {
            return SettingsPadding(0, 0, 0, 0)
        }
        val blockedTop = maxOf(insetTop.coerceAtLeast(0), titleBottom.coerceAtLeast(0))
        return SettingsPadding(
            left = (insetLeft.coerceAtLeast(0) - contentLeft).coerceAtLeast(0),
            top = (blockedTop - contentTop).coerceAtLeast(0),
            right = (contentRight - (windowWidth - insetRight.coerceAtLeast(0))).coerceAtLeast(0),
            bottom = (contentBottom - (windowHeight - insetBottom.coerceAtLeast(0))).coerceAtLeast(0),
        )
    }
}
