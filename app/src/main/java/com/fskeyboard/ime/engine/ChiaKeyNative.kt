package com.fskeyboard.ime.engine

/**
 * JNI entry points for libfs_chiakey.so.
 * [ensureLoaded] is the only place that calls [System.loadLibrary].
 */
internal class ChiaKeyNative private constructor() {
    companion object {
        @Volatile
        private var loaded = false

        fun ensureLoaded(): Boolean {
            if (loaded) return true
            return try {
                System.loadLibrary("fs_chiakey")
                loaded = true
                true
            } catch (_: UnsatisfiedLinkError) {
                false
            }
        }

        @JvmStatic
        external fun nativeOpen(writable: String, database: String): Long

        @JvmStatic
        external fun nativeClose(handle: Long)

        /** False when the engine could not be created again. The handle is then dead. */
        @JvmStatic
        external fun nativeReset(handle: Long): Boolean

        @JvmStatic
        external fun nativeAscii(handle: Long, key: Char): String

        @JvmStatic
        external fun nativeKey(handle: Long, code: Int): String

        @JvmStatic
        external fun nativeSelect(handle: Long, index: Int): String

        @JvmStatic
        external fun nativeAck(handle: Long)
    }
}
