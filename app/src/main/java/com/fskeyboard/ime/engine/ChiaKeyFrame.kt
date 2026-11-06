package com.fskeyboard.ime.engine

/**
 * One ChiaKey snapshot, carried across JNI as unit-separated text.
 *
 * `reading \u001f composing \u001f committed \u001f 0|1 \u001f cand \u001e cand`
 */
internal data class ChiaKeyFrame(
    val reading: String,
    val composing: String,
    val committed: String,
    val candidatesVisible: Boolean,
    val candidates: List<String>,
) {
    /** Finished Hanzi, then the syllable that is still being typed. */
    val display: String get() = composing + reading

    val active: Boolean
        get() = reading.isNotEmpty() ||
            composing.isNotEmpty() ||
            committed.isNotEmpty() ||
            candidatesVisible

    companion object {
        private const val FIELD = '\u001f'
        private const val ITEM = '\u001e'

        fun parse(raw: String): ChiaKeyFrame {
            val parts = raw.split(FIELD)
            fun field(index: Int) = parts.getOrElse(index) { "" }
            val candidates = field(4).split(ITEM).filter { it.isNotEmpty() }
            return ChiaKeyFrame(
                reading = field(0),
                composing = field(1),
                committed = field(2),
                candidatesVisible = field(3) == "1",
                candidates = candidates,
            )
        }
    }
}
