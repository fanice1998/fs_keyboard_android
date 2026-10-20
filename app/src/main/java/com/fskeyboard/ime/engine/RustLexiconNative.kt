package com.fskeyboard.ime.engine

/**
 * JNI entry points for libfs_lexicon.so.
 * [load] is the only place that calls [System.loadLibrary]. Unit tests must not call it.
 */
internal class RustLexiconNative private constructor() {
    companion object {
        fun load() {
            System.loadLibrary("fs_lexicon")
        }

        /**
         * [table] is the phrase table, [dict] the Rime dictionary and [user] the learned
         * `reading<TAB>text` lines, all UTF-8.
         */
        @JvmStatic
        external fun nativeLoad(table: ByteArray, dict: ByteArray, user: ByteArray): Int

        /** `span<TAB>text<TAB>reading` lines for syllable queries joined by a space. */
        @JvmStatic
        external fun nativeLookup(code: String): String

        /** Remembers [text] under [reading]. False when it was already remembered. */
        @JvmStatic
        external fun nativeLearn(reading: String, text: String): Boolean
    }
}
