package com.fskeyboard.ime.keyboard

/** One shift tap capitalizes the next letter. A second tap holds uppercase until shift is tapped again. */
internal enum class ShiftMode {
    OFF,
    ONCE,
    LOCKED,
}

internal class ShiftLock {
    var mode: ShiftMode = ShiftMode.OFF
        private set

    val uppercase: Boolean
        get() = mode != ShiftMode.OFF

    fun onShift() {
        mode = when (mode) {
            ShiftMode.OFF -> ShiftMode.ONCE
            ShiftMode.ONCE -> ShiftMode.LOCKED
            ShiftMode.LOCKED -> ShiftMode.OFF
        }
    }

    /** True when a one-shot shift ended. Caps lock stays through letters. */
    fun onCharacter(): Boolean {
        if (mode != ShiftMode.ONCE) return false
        mode = ShiftMode.OFF
        return true
    }

    /** Drops a one-shot shift. Caps lock stays, so leaving the Latin keys does not cancel it. */
    fun releaseOnce() {
        if (mode == ShiftMode.ONCE) mode = ShiftMode.OFF
    }
}
