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
         * [compiled] is assets/lexicon/lexicon.bin and [user] the learned `reading<TAB>text`
         * lines in UTF-8. 0 on success, -2 when the compiled lexicon is malformed.
         */
        @JvmStatic
        external fun nativeLoad(compiled: ByteArray, user: ByteArray): Int

        /**
         * `span<TAB>text<TAB>reading` lines for syllable queries joined by a space, typed
         * after [before], the text before the cursor.
         */
        @JvmStatic
        external fun nativeLookup(code: String, before: String): String

        /** Remembers [text] under [reading]. False when it was already remembered. */
        @JvmStatic
        external fun nativeLearn(reading: String, text: String): Boolean
    }
}
