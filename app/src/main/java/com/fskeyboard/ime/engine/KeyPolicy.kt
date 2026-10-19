package com.fskeyboard.ime.engine

import com.fskeyboard.ime.candidate.Candidate
import com.fskeyboard.ime.keyboard.KeyboardMode

internal object KeyPolicy {
    enum class Space {
        AcceptFirst,
        SendToRime,
        CommitSpace,
    }

    fun space(mode: KeyboardMode, presented: List<Candidate>, composing: Boolean): Space {
        if (mode == KeyboardMode.ZHUYIN && presented.isNotEmpty()) return Space.AcceptFirst
        if (mode == KeyboardMode.ZHUYIN && composing) return Space.SendToRime
        return Space.CommitSpace
    }

    fun backspaceToRime(mode: KeyboardMode, composing: Boolean): Boolean {
        return mode == KeyboardMode.ZHUYIN && composing
    }
}
