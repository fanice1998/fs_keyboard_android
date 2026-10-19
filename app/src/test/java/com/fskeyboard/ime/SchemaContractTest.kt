package com.fskeyboard.ime

import com.fskeyboard.ime.keyboard.ZhuyinLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SchemaContractTest {
    @Test
    fun schemaDefaultsToTaiwanStandardAndUserDict() {
        val schema = readProjectFile("src/main/assets/rime/shared/fs_zhuyin.schema.yaml")
        val defaults = readProjectFile("src/main/assets/rime/shared/default.yaml")
        assertTrue(schema.contains("schema_id: fs_zhuyin"))
        assertTrue(schema.contains("opencc_config: t2tw.json"))
        assertTrue(schema.contains("enable_user_dict: true"))
        assertTrue(schema.contains("enable_sentence: true"))
        assertTrue(schema.contains("enable_completion: true"))
        assertTrue(Regex("name: zh_tw\\s+reset: 1").containsMatchIn(schema))
        assertTrue(defaults.contains("schema: fs_zhuyin"))
        assertFalse(schema.contains("luna_pinyin"))
        assertFalse(schema.contains("import_preset"))
        assertFalse(schema.contains("fs_pinyin"))
        assertEquals("2", readProjectFile("src/main/assets/rime/shared/VERSION").trim())
    }

    @Test
    fun algebraMatchesTheZhuyinKeyMapInBothDirections() {
        val schema = readProjectFile("src/main/assets/rime/shared/fs_zhuyin.schema.yaml")
        val algebra = section(schema, "algebra:", "translator:")
        val preedit = section(schema, "preedit_format:", "simplifier:")
        assertTrue(schema.contains("alphabet: \"${ZhuyinLayout.ALPHABET}\""))
        for (key in ZhuyinLayout.keys) {
            if (key.label == "ㄥ") {
                assertTrue(algebra.contains("\"xform/ㄥ/\\\\//\""))
                assertTrue(preedit.contains("\"xform/\\\\//ㄥ/\""))
            } else {
                assertTrue(key.label, algebra.contains("\"xform/${key.label}/${key.key}/\""))
                val shown = if (key.key == '.') "\"xform/\\\\./ㄡ/\"" else "\"xform/${key.key}/${key.label}/\""
                assertTrue(key.label, preedit.contains(shown))
            }
        }
        val deriveAt = algebra.indexOf("\"derive/[3467]//\"")
        assertTrue(deriveAt > algebra.lastIndexOf("xform/"))
    }

    @Test
    fun sampleDictionaryIsTraditionalZhuyinWithToneLast() {
        val dict = readProjectFile("src/main/assets/rime/shared/fs_zhuyin.dict.yaml")
        assertTrue(dict.contains("use_preset_vocabulary: false"))
        assertTrue(dict.contains("name: fs_zhuyin"))
        assertTrue(dict.contains("注音\tㄓㄨˋ ㄧㄣ\t"))
        assertTrue(dict.contains("你好\tㄋㄧˇ ㄏㄠˇ\t"))
        assertTrue(dict.contains("輸入法\tㄕㄨ ㄖㄨˋ ㄈㄚˇ\t"))
        assertTrue(dict.contains("臺灣\tㄊㄞˊ ㄨㄢ\t"))
        assertTrue(dict.contains("們\tㄇㄣ˙\t"))
        val tones = setOf('˙', 'ˊ', 'ˇ', 'ˋ')
        dict.lineSequence().filter { it.count { ch -> ch == '\t' } == 2 }.forEach { line ->
            val code = line.split('\t')[1]
            code.split(' ').forEach { syllable ->
                assertTrue(syllable, syllable.first() !in tones)
                assertTrue(syllable, syllable.dropLast(1).none { it in tones })
            }
        }
    }

    @Test
    fun openCcDefaultIsT2twAndS2twRemainsOptional() {
        val taiwan = readProjectFile("src/main/assets/rime/shared/opencc/t2tw.json")
        assertTrue(taiwan.contains("CJK_Compatibility_Ideographs.ocd2"))
        assertTrue(taiwan.contains("TWVariants.ocd2"))
        assertTrue(taiwan.contains("TWVariantsPhrases.ocd2"))
        assertFalse(taiwan.contains("STCharacters.ocd2"))
        assertFalse(taiwan.contains("mmseg"))
        val simplified = readProjectFile("src/main/assets/rime/shared/opencc/s2tw.json")
        assertTrue(simplified.contains("STCharacters.ocd2"))
        assertTrue(simplified.contains("TWVariants.ocd2"))
        assertTrue(simplified.contains("TWVariantsPhrases.ocd2"))
    }

    @Test
    fun jniMethodsMatchTheKotlinDeclarations() {
        val jni = readProjectFile("src/main/cpp/rime_jni.cpp")
        val kotlin = readProjectFile("src/main/java/com/fskeyboard/ime/bridge/RimeNative.kt")
        listOf(
            "startup",
            "shutdown",
            "createSession",
            "processKey",
            "selectCandidate",
            "selectSchema",
            "setOption",
            "getOption",
        ).forEach { name ->
            assertTrue(name, jni.contains("RimeNative_$name"))
            assertTrue(name, kotlin.contains("fun $name"))
        }
        assertTrue(jni.contains("select_candidate"))
    }

    private fun section(text: String, start: String, end: String): String {
        val from = text.indexOf(start)
        val to = text.indexOf(end, from + start.length)
        assertTrue(from >= 0 && to > from)
        return text.substring(from, to)
    }
}
