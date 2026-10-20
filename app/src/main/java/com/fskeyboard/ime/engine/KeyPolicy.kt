package com.fskeyboard.ime.engine

import com.fskeyboard.ime.keyboard.KeyboardMode

internal object KeyPolicy {
    enum class Space {
        /** Zhuyin composition: space is 平聲 and never selects a candidate. */
        MarkLevelTone,
        CommitSpace,
    }

    fun space(mode: KeyboardMode, composing: Boolean): Space {
        if (mode == KeyboardMode.ZHUYIN && composing) return Space.MarkLevelTone
        return Space.CommitSpace
    }
}
