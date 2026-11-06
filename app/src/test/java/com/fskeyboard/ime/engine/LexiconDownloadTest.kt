package com.fskeyboard.ime.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LexiconDownloadTest {
    @Test
    fun acceptsTheChiaKeyReleaseAndCdn() {
        assertTrue(
            LexiconDownload.allowed(
                "https://github.com/chiakich/ChiaKey-Lexicon/releases/download/2026.06.7/ChiaKeySource.db",
            ),
        )
        assertTrue(
            LexiconDownload.allowed(
                "https://cdn.chiaki.ch/chiakey/lexicon/ChiaKeySource.db",
            ),
        )
    }

    @Test
    fun rejectsOtherHostsAndPlainHttp() {
        assertFalse(LexiconDownload.allowed("http://github.com/chiakich/ChiaKey-Lexicon/releases/download/x/ChiaKeySource.db"))
        assertFalse(LexiconDownload.allowed("https://example.com/ChiaKeySource.db"))
        assertFalse(LexiconDownload.allowed("https://github.com/other/ChiaKey-Lexicon/releases/download/x/ChiaKeySource.db"))
        assertFalse(LexiconDownload.allowed("https://cdn.chiaki.ch/other/ChiaKeySource.db"))
    }

    @Test
    fun requiresTheManifestDigestToMatchTheChecksumFile() {
        val sums = """
            aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa  ChiaKeySource.db
            bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb  metadata.json
        """.trimIndent()
        assertTrue(
            LexiconDownload.digestAgrees(
                "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                sums,
                "ChiaKeySource.db",
            ),
        )
        assertFalse(
            LexiconDownload.digestAgrees(
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                sums,
                "ChiaKeySource.db",
            ),
        )
        assertFalse(LexiconDownload.digestAgrees("abc", sums, "missing.db"))
    }

    @Test
    fun acceptsOnlySchemaVersionOne() {
        assertTrue(LexiconDownload.schemaSupported(1))
        assertFalse(LexiconDownload.schemaSupported(2))
        assertFalse(LexiconDownload.schemaSupported(0))
    }

    @Test
    fun installsThePublishedDatabaseUnchanged() {
        val published = byteArrayOf(0x53, 0x51, 0x4c, 0x69, 0x74, 0x65)
        assertArrayEquals(published, LexiconDownload.installPayload(published))
    }

    @Test
    fun niHaoUsesTheChiaKeyStandardKeySequence() {
        assertEquals("su3cl3", com.fskeyboard.ime.keyboard.ZhuyinSpelling.keysOf("ㄋㄧˇㄏㄠˇ"))
    }
}
