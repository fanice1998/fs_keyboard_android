package com.fskeyboard.ime.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BundledChiaKeyLexiconTest {
    @Test
    fun installCopiesTheAssetAndLeavesWritableAlone() {
        val root = tempRoot()
        val writable = File(root, "writable").apply { mkdirs() }
        val learned = File(writable, "SmartMandarinUserData.db").apply { writeBytes(byteArrayOf(7, 7)) }
        val destination = File(root, "ChiaKeySource.db")
        val bytes = byteArrayOf(1, 2, 3, 4)
        BundledChiaKeyLexicon.install({ bytes.inputStream() }, destination)
        assertTrue(destination.isFile)
        assertArrayEquals(bytes, destination.readBytes())
        assertArrayEquals(byteArrayOf(7, 7), learned.readBytes())
        assertTrue(writable.isDirectory)
        BundledChiaKeyLexicon.install({ byteArrayOf(9).inputStream() }, destination)
        assertArrayEquals(bytes, destination.readBytes())
    }

    @Test
    fun aMissingAssetDoesNotCreateTheDatabase() {
        val root = tempRoot()
        val destination = File(root, "ChiaKeySource.db")
        BundledChiaKeyLexicon.install({ null }, destination)
        assertFalse(destination.exists())
        BundledChiaKeyLexicon.install({ throw java.io.IOException("missing") }, destination)
        assertFalse(destination.exists())
        assertFalse(File(root, "writable").exists())
    }

    private fun tempRoot() = File.createTempFile("chiakey-bundle", "").apply {
        delete()
        mkdirs()
    }
}
