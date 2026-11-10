package com.fskeyboard.ime.lexicon

import com.fskeyboard.ime.candidate.UnigramRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FsLexiconTest {
    @Test
    fun theWrittenFileStartsWithTheVersionLine() {
        val file = tempFile()
        FsLexicon(file).addUser("ㄋㄧˇ", "你", 1.5)
        assertEquals("fslexicon 1", file.readLines(Charsets.UTF_8).first())
    }

    @Test
    fun aUserRowIsInTheQueryWithBuiltinRows() {
        val file = tempFile()
        file.writeText(
            "fslexicon 1\nㄋㄧˇ\t你\t-2.0\tbuiltin\n",
            Charsets.UTF_8,
        )
        val lexicon = FsLexicon(file)
        lexicon.addUser("ㄋㄧˇㄏㄠˇ", "你好", 0.0)
        assertEquals(
            listOf(
                Row("ㄋㄧˇ", "你", -2.0, "builtin"),
                Row("ㄋㄧˇㄏㄠˇ", "你好", 0.0, "user"),
            ),
            lexicon.rows(),
        )
    }

    @Test
    fun aBadLineIsSkipped() {
        val file = tempFile()
        file.writeText(
            """
            fslexicon 1
            ㄋㄧˇ	你	-2.0	builtin
            broken
            ㄏㄠˇ	好	not-a-number	user
            ㄨㄛˇ	我	0.2	other
            ㄊㄧㄢ	天	1.0	builtin	extra
            	空	1	user
            ㄋㄧˇㄏㄠˇ	你好	0.5	user

            """.trimIndent() + "\n",
            Charsets.UTF_8,
        )
        assertEquals(
            listOf(
                Row("ㄋㄧˇ", "你", -2.0, "builtin"),
                Row("ㄋㄧˇㄏㄠˇ", "你好", 0.5, "user"),
            ),
            FsLexicon(file).rows(),
        )
    }

    @Test
    fun aWrongVersionIsNotRead() {
        val file = tempFile()
        file.writeText("fslexicon 2\nㄋㄧˇ\t你\t1\tuser\n", Charsets.UTF_8)
        assertTrue(FsLexicon(file).rows().isEmpty())
    }

    @Test
    fun replaceBuiltinKeepsUserRows() {
        val lexicon = FsLexicon(tempFile())
        lexicon.addUser("ㄋㄧˇㄏㄠˇ", "你好", 0.0)
        lexicon.replaceBuiltin(listOf(Row("ㄋㄧˇ", "你", -2.0, "builtin")))
        assertEquals(
            listOf(
                Row("ㄋㄧˇ", "你", -2.0, "builtin"),
                Row("ㄋㄧˇㄏㄠˇ", "你好", 0.0, "user"),
            ),
            lexicon.rows(),
        )
    }

    @Test
    fun unigramsBecomeBuiltinRows() {
        val ni = UnigramRow.of("你", listOf("ㄋㄧˇ"), -2.0)
        val hao = UnigramRow.of("你好", listOf("ㄋㄧˇ", "ㄏㄠˇ"), -0.5)
        val tian = UnigramRow.of("天", listOf("ㄊㄧㄢ"), -1.0)
        val rows = ChiaKeyLexiconConverter.rows { accept ->
            accept(ni.qstring, ni.text, ni.probability)
            accept("\u0001", "壞", 1.0)
            accept(hao.qstring, "你\t好", -0.4)
            accept(hao.qstring, hao.text, hao.probability)
            accept(tian.qstring, tian.text, tian.probability)
        }
        assertEquals(
            listOf(
                Row("ㄋㄧˇ", "你", -2.0, "builtin"),
                Row("ㄋㄧˇㄏㄠˇ", "你好", -0.5, "builtin"),
                Row("ㄊㄧㄢ", "天", -1.0, "builtin"),
            ),
            rows,
        )
    }

    @Test
    fun aMissingDatabaseDoesNotDropUserRows() {
        val file = tempFile()
        val lexicon = FsLexicon(file)
        lexicon.addUser("ㄋㄧˇ", "你", 1.0)
        val root = File(file.parentFile, "missing-chiakey")
        assertFalse(File(root, "ChiaKeySource.db").exists())
        ChiaKeyLexiconConverter.convert(root, lexicon)
        assertEquals(listOf(Row("ㄋㄧˇ", "你", 1.0, "user")), lexicon.rows())
    }

    private fun tempFile(): File {
        val dir = File.createTempFile("fslexicon", "").apply {
            delete()
            mkdirs()
        }
        return File(dir, "fslexicon.txt")
    }
}
