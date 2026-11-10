package com.fskeyboard.ime.engine

internal object ChiaKeyIndexPlan {
    /** One pass. The one-syllable publish is not a plan step. */
    fun ranges(): List<IntRange> = listOf(1..4)
}
