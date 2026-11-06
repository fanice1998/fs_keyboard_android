package com.fskeyboard.ime.engine

/**
 * The zhuyin engine behind one input field. The keyboard sends the ASCII
 * already stored on a bopomofo key. Unit tests supply a fake.
 */
internal interface ZhuyinEngine {
    val ready: Boolean

    fun handleAscii(key: Char): ChiaKeyFrame

    /** [code] is 8 for backspace and 13 for enter. */
    fun handleKey(code: Int): ChiaKeyFrame

    fun select(index: Int): ChiaKeyFrame

    fun acknowledge()

    fun reset()
}

internal object UnavailableZhuyinEngine : ZhuyinEngine {
    private val idle = ChiaKeyFrame.parse("")

    override val ready = false

    override fun handleAscii(key: Char) = idle

    override fun handleKey(code: Int) = idle

    override fun select(index: Int) = idle

    override fun acknowledge() = Unit

    override fun reset() = Unit
}
