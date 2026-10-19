package com.fskeyboard.ime.engine

/** X11 keysyms that librime's process_key expects. Values are the public keysym numbers. */
internal object RimeKey {
    const val SPACE = 0x0020
    const val BACKSPACE = 0xff08
    const val RETURN = 0xff0d
    const val SHIFT_MASK = 1 shl 0
}
