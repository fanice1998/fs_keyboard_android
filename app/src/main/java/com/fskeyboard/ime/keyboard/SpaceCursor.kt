package com.fskeyboard.ime.keyboard

/**
 * Finger travel on the space bar.
 * Movement inside [slop] stays a tap. Past that, the bar is a trackpad and each
 * sample reports the total pixels from where the glide engaged, so the caret can
 * snap to wherever the finger is instead of stepping one character at a time.
 */
internal class SpaceCursor(
    private val slop: Float,
) {
    var tracking: Boolean = false
        private set

    private var originX = 0f
    private var originY = 0f
    private var anchorX = 0f
    private var anchorY = 0f

    fun down(x: Float, y: Float) {
        tracking = false
        originX = x
        originY = y
        anchorX = x
        anchorY = y
    }

    /**
     * Null while the finger has not yet left the tap slop.
     * The event that crosses the slop returns (0, 0): that distance is not travel.
     * Later samples are the total from the point where the slop was crossed.
     */
    fun move(x: Float, y: Float): Pair<Float, Float>? {
        if (!tracking) {
            val dx = x - originX
            val dy = y - originY
            if (dx * dx + dy * dy < slop * slop) return null
            tracking = true
            anchorX = x
            anchorY = y
            return 0f to 0f
        }
        return (x - anchorX) to (y - anchorY)
    }

    /** A still hold becomes a trackpad without spending the distance so far. */
    fun hold() {
        if (tracking) return
        tracking = true
        anchorX = originX
        anchorY = originY
    }
}
