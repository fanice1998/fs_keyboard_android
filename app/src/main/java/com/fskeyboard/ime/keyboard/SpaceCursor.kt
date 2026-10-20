package com.fskeyboard.ime.keyboard

/**
 * Finger travel on the space bar, turned into cursor steps.
 * Movement inside [slop] stays a tap. Past that, the bar is a trackpad:
 * leftover distance is kept, so reversing undoes the partial step instead of jumping.
 * [xPerStep] and [yPerStep] are in the same pixels as the touch coordinates.
 */
internal class SpaceCursor(
    private val slop: Float,
    private val xPerStep: Float,
    private val yPerStep: Float,
) {
    var tracking: Boolean = false
        private set

    private var originX = 0f
    private var originY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var carryX = 0f
    private var carryY = 0f

    fun down(x: Float, y: Float) {
        tracking = false
        originX = x
        originY = y
        lastX = x
        lastY = y
        carryX = 0f
        carryY = 0f
    }

    /**
     * Null while the finger has not yet left the tap slop.
     * The event that crosses the slop returns (0, 0): that distance is not a step.
     */
    fun move(x: Float, y: Float): Pair<Int, Int>? {
        if (!tracking) {
            val dx = x - originX
            val dy = y - originY
            if (dx * dx + dy * dy < slop * slop) return null
            tracking = true
            lastX = x
            lastY = y
            return 0 to 0
        }
        carryX += x - lastX
        carryY += y - lastY
        lastX = x
        lastY = y
        val stepsX = (carryX / xPerStep).toInt()
        val stepsY = (carryY / yPerStep).toInt()
        carryX -= stepsX * xPerStep
        carryY -= stepsY * yPerStep
        return stepsX to stepsY
    }

    /** A still hold becomes a trackpad without spending the distance so far. */
    fun hold() {
        if (tracking) return
        tracking = true
        lastX = originX
        lastY = originY
        carryX = 0f
        carryY = 0f
    }
}
