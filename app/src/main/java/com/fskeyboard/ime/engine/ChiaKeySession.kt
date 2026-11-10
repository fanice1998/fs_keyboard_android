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
    private var opening = false

    override val ready: Boolean
        get() = handle != 0L

    /**
     * Opens the native runtime. The database load does not hold [gate], so a
     * field switch can reset or ignore a key instead of waiting on this call.
     */
    fun open(): Boolean {
        synchronized(gate) {
            if (handle != 0L) return true
            if (opening) return false
            opening = true
        }
        var opened = 0L
        try {
            opened = openNative()
        } finally {
            synchronized(gate) {
                opening = false
                if (handle == 0L && opened != 0L) handle = opened
            }
        }
        return handle != 0L
    }

    private fun openNative(): Long {
        if (!database.isFile) return 0L
        if (!ChiaKeyNative.ensureLoaded()) return 0L
        writable.mkdirs()
        File(writable, "Preferences").mkdirs()
        return ChiaKeyNative.nativeOpen(writable.absolutePath, database.absolutePath)
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
            // A failed clear must not drop the runtime. The next key still
            // needs this handle; closing it left zhuyin dead until relaunch.
            ChiaKeyNative.nativeReset(current)
        }
    }

    private fun call(block: (Long) -> String): ChiaKeyFrame = synchronized(gate) {
        val current = handle
        if (current == 0L) ChiaKeyFrame.parse("") else ChiaKeyFrame.parse(block(current))
    }
}
