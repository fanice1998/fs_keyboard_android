package com.fskeyboard.ime.lingxi

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The shipped selector, from an empty composition, against the full public pack. */
class LingxiSelectTest {
    @Test
    fun fullPackKeepsBothBigramClassesAndEnglish() {
        val census = engine.census
        println(
            "lingxi census characters=${census.characters} words=${census.words} " +
                "unigrams=${census.unigrams} multi=${census.multiCharBigrams} " +
                "single=${census.singleCharBigrams} english=${census.englishWords}",
        )
        assertTrue(census.characters > 0)
        assertTrue(census.words > 0)
        assertTrue(census.unigrams > 0)
        assertTrue(census.multiCharBigrams > 0)
        assertTrue(census.singleCharBigrams > 0)
        assertTrue(census.englishWords > 0)
        assertRefused(LingxiCensus(characters = 1, words = 1, unigrams = 1, multiCharBigrams = 0, singleCharBigrams = 1, englishWords = 1))
        assertRefused(LingxiCensus(characters = 1, words = 1, unigrams = 1, multiCharBigrams = 1, singleCharBigrams = 0, englishWords = 1))
        assertRefused(LingxiCensus(characters = 1, words = 1, unigrams = 1, multiCharBigrams = 1, singleCharBigrams = 1, englishWords = 0))
        val empty = File(System.getProperty("java.io.tmpdir"), "lingxi-missing-english")
        empty.deleteRecursively()
        empty.mkdirs()
        try {
            loadLingxiTables(empty)
            fail("missing english subpack")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message.orEmpty().contains("english"))
        } finally {
            empty.deleteRecursively()
        }
    }

    @Test
    fun zaiFollowsContextAndARememberedChoice() {
        val reading = listOf("ㄗㄞˋ")
        val home = engine.select(reading, "我在家", remembered = null)
        val again = engine.select(reading, "請再試一次", remembered = null)
        val homeStat = engine.select(reading, "我在家", remembered = null, useModel = false)
        val againStat = engine.select(reading, "請再試一次", remembered = null, useModel = false)
        println(
            "zai home first=${home.candidates.first()} reorder=${home.reorder} " +
                "stat=${homeStat.candidates.first()}",
        )
        println(
            "zai again first=${again.candidates.first()} reorder=${again.reorder} " +
                "stat=${againStat.candidates.first()}",
        )
        assertEquals("ok", home.reorder)
        assertEquals("ok", again.reorder)
        assertEquals("在", home.candidates.first())
        assertEquals("再", again.candidates.first())
        assertTrue(
            home.candidates.first() != homeStat.candidates.first() ||
                again.candidates.first() != againStat.candidates.first(),
        )
        val other = home.candidates.first { it != home.candidates.first() }
        val pinned = engine.select(reading, "我在家", remembered = other)
        assertEquals(other, pinned.candidates.first())
        assertTrue(pinned.candidates.containsAll(home.candidates))
    }

    private fun assertRefused(census: LingxiCensus) {
        try {
            census.requireFull()
            fail("empty table qualified")
        } catch (_: IllegalArgumentException) {
        }
    }

    companion object {
        private val engine: LingxiEngine by lazy { loadLingxiEngine(packRoot()) }

        private fun packRoot(): File {
            val dir = System.getProperty("user.dir") ?: error("user.dir")
            val cwd = File(dir)
            val parent = cwd.parentFile ?: cwd
            val options = listOf(File(cwd, "data/lingxi"), File(parent, "data/lingxi"))
            return options.firstOrNull { File(it, "data/lexicon.bin").isFile }
                ?: error("full lingxi pack missing under data/lingxi")
        }
    }
}
