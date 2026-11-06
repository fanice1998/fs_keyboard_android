package com.fskeyboard.ime.engine

import java.io.File

/**
 * One ChiaKey runtime for this process. The lexicon file stays beside
 * [writable] so the engine does not overwrite the release database.
 */
internal class ChiaKeySession(private val root: File) : ZhuyinEngine {
    val database: File = File(root, "ChiaKeySource.db")
    val writable: File = File(root, "writable")

    private val gate = Any()
    @Volatile private var handle = 0L

    override val ready: Boolean
        get() = handle != 0L

    fun open(): Boolean = synchronized(gate) {
        if (handle != 0L) return true
        if (!database.isFile) return false
        if (!ChiaKeyNative.ensureLoaded()) return false
        writable.mkdirs()
        File(writable, "Preferences").mkdirs()
        val opened = ChiaKeyNative.nativeOpen(writable.absolutePath, database.absolutePath)
        handle = opened
        opened != 0L
    }

    fun close() = synchronized(gate) {
        val current = handle
        if (current == 0L) return
        handle = 0L
        ChiaKeyNative.nativeClose(current)
    }

    override fun handleAscii(key: Char): ChiaKeyFrame = call { ChiaKeyNative.nativeAscii(it, key) }

    override fun handleKey(code: Int): ChiaKeyFrame = call { ChiaKeyNative.nativeKey(it, code) }

    override fun select(index: Int): ChiaKeyFrame = call { ChiaKeyNative.nativeSelect(it, index) }

    override fun acknowledge() {
        synchronized(gate) {
            val current = handle
            if (current != 0L) ChiaKeyNative.nativeAck(current)
        }
    }

    override fun reset() {
        synchronized(gate) {
            val current = handle
            if (current == 0L) return
            if (!ChiaKeyNative.nativeReset(current)) handle = 0L
        }
    }

    private fun call(block: (Long) -> String): ChiaKeyFrame = synchronized(gate) {
        val current = handle
        if (current == 0L) ChiaKeyFrame.parse("") else ChiaKeyFrame.parse(block(current))
    }
}
