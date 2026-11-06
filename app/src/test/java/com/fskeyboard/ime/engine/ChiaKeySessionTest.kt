package com.fskeyboard.ime.engine

import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class ChiaKeySessionTest {
    @Test
    fun staysClosedWhenTheDatabaseIsMissing() {
        val root = tempRoot()
        val session = ChiaKeySession(root)
        assertFalse(session.open())
        assertFalse(session.ready)
        root.deleteRecursively()
    }

    @Test
    fun staysClosedWhenTheLibraryIsMissing() {
        val root = tempRoot()
        File(root, "ChiaKeySource.db").writeBytes(byteArrayOf(1))
        val session = ChiaKeySession(root)
        assertFalse(session.open())
        assertFalse(session.ready)
        assertTrueFile(File(root, "writable/SmartMandarinUserData.db"))
        root.deleteRecursively()
    }

    private fun assertTrueFile(user: File) {
        assertFalse(user.exists())
    }

    private fun tempRoot() = File.createTempFile("chiakey-session", "").apply {
        delete()
        mkdirs()
    }
}
