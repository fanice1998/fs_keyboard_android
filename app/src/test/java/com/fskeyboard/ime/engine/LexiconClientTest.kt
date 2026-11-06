package com.fskeyboard.ime.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LexiconClientTest {
    @Test
    fun doesNotFetchAUrlOutsideTheAllowlist() {
        var called = false
        val fetched = runCatching {
            LexiconClient.bytes("https://evil.example/ChiaKeySource.db") {
                called = true
                byteArrayOf(1)
            }
        }
        assertTrue(fetched.isFailure)
        assertFalse(called)
    }

    @Test
    fun fetchesAnAllowlistedUrl() {
        val bytes = LexiconClient.bytes(
            "https://github.com/chiakich/ChiaKey-Lexicon/releases/download/2026.11.2/SHA256SUMS",
        ) { byteArrayOf(4, 5) }
        assertEquals(listOf(4.toByte(), 5.toByte()), bytes.toList())
    }
}
