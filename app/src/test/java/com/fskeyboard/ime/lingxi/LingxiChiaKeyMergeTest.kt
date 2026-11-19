package com.fskeyboard.ime.lingxi

import com.fskeyboard.ime.candidate.BopomofoOrder
import com.fskeyboard.ime.candidate.Candidate
import com.fskeyboard.ime.candidate.CandidateSource
import com.fskeyboard.ime.candidate.SyllableReading
import com.fskeyboard.ime.compose.ZhuyinCompositionSession
import com.fskeyboard.ime.compose.ZhuyinHost
import com.fskeyboard.ime.engine.ChiaKeyFrame
import com.fskeyboard.ime.engine.EngineSnapshot
import com.fskeyboard.ime.engine.ZhuyinEngine
import com.fskeyboard.ime.keyboard.KeyAction
import com.fskeyboard.ime.keyboard.KeyboardMode
import com.fskeyboard.ime.lexicon.Lexicon
import java.io.File
import kotlin.math.exp
import kotlin.math.ln
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Small fixtures. The full packs are not required. */
class LingxiChiaKeyMergeTest {
    @Test
    fun duplicateSurfaceKeepsLingxiPrior() {
        val q = qstring("ㄋㄧˇ")
        val base = lexicon(listOf("ㄋㄧˇ"), listOf(row(0, "你", 0.42f, 0)))
        val weak = ln(0.2)
        val strong = ln(0.5)
        val merged = mergeChiaUnigrams(
            base,
            listOf(
                ChiaUnigram(q, "你", 0.0),
                ChiaUnigram(q, "妳", weak),
                ChiaUnigram(q, "妳", strong),
            ),
        )
        val lexicon = decodeLexicon(merged.bytes, merged.supplementMask)
        val kept = lexicon.exact(listOf("ㄋㄧˇ")).single { it.surface == "你" }
        assertEquals(0.42f, kept.prw, 0f)
        assertFalse(kept.supplement)
        assertFalse(kept.rare)
        val added = lexicon.exact(listOf("ㄋㄧˇ")).single { it.surface == "妳" }
        assertEquals(exp(strong).toFloat(), added.prw, 0f)
        assertTrue(added.supplement)
        assertEquals(1, merged.added)
    }

    @Test
    fun logProbabilityBecomesPositivePrior() {
        val hello = -0.927933
        val base = lexicon(listOf("ㄚ"), listOf(row(0, "啊", 0.2f, 0)))
        val merged = mergeChiaUnigrams(
            base,
            listOf(
                ChiaUnigram(qstring("ㄋㄧˇ"), "妳", hello),
                ChiaUnigram(qstring("ㄏㄠˇ"), "好", -1000.0),
            ),
        )
        val lexicon = decodeLexicon(merged.bytes, merged.supplementMask)
        val expected = exp(hello).toFloat()
        assertTrue(expected > 1e-6f && expected <= 1f)
        val ni = lexicon.exact(listOf("ㄋㄧˇ")).single()
        assertEquals(expected, ni.prw, 0f)
        assertTrue(ni.supplement)
        assertTrue(ni.prw > 0f)
        val tiny = lexicon.exact(listOf("ㄏㄠˇ")).single()
        assertEquals(1e-6f, tiny.prw, 0f)
        val engine = engine(lexicon)
        assertEquals(listOf("妳"), engine.select(listOf("ㄋㄧˇ"), "", null, useModel = false).candidates)
        assertEquals(listOf("好"), engine.select(listOf("ㄏㄠˇ"), "", null, useModel = false).candidates)
    }

    @Test
    fun rejectedRowsStayOut() {
        val base = lexicon(listOf("ㄋㄧˇ"), listOf(row(0, "你", 0.4f, 0)))
        val before = decodeLexicon(base).entryCount
        val nine = List(9) { "ㄚ" }
        val bad = mergeChiaUnigrams(
            base,
            listOf(
                ChiaUnigram(qstring(nine), "啊".repeat(9), -1.0),
                ChiaUnigram(qstring("ㄚ"), "啊啊", -1.0),
                ChiaUnigram("!!", "啊", -1.0),
                ChiaUnigram("abc", "啊", -1.0),
                ChiaUnigram("", "啊", -1.0),
                ChiaUnigram(qstring("ㄚ"), "", -1.0),
            ),
        )
        assertEquals(before, decodeLexicon(bad.bytes).entryCount)
        assertEquals(0, bad.added)
        val eight = List(8) { "ㄚ" }
        val kept = mergeChiaUnigrams(
            base,
            listOf(
                ChiaUnigram(qstring(nine), "啊".repeat(9), -1.0),
                ChiaUnigram(qstring(eight), "啊".repeat(8), -1.0),
            ),
        )
        val lexicon = decodeLexicon(kept.bytes, kept.supplementMask)
        assertEquals(before + 1, lexicon.entryCount)
        assertEquals("啊".repeat(8), lexicon.exact(eight).single().surface)
        assertTrue(lexicon.exact(nine).isEmpty())
    }

    @Test
    fun newSyllableIsQueryable() {
        val base = lexicon(listOf("ㄋㄧˇ"), listOf(row(0, "你", 0.4f, 0)))
        val merged = mergeChiaUnigrams(base, listOf(ChiaUnigram(qstring("ㄓㄨㄥ"), "中", -1.0)))
        val lexicon = decodeLexicon(merged.bytes, merged.supplementMask)
        val hit = lexicon.exact(listOf("ㄓㄨㄥ")).single()
        assertEquals("中", hit.surface)
        assertTrue(hit.supplement)
        assertEquals("你", lexicon.exact(listOf("ㄋㄧˇ")).single().surface)
    }

    @Test
    fun usedFlagBitIsNotReused() {
        val base = lexicon(listOf("ㄋㄧˇ"), listOf(row(0, "你", 0.5f, flags = 4)))
        val merged = mergeChiaUnigrams(base, listOf(ChiaUnigram(qstring("ㄓㄨㄥ"), "中", -1.0)))
        assertEquals(8, merged.supplementMask)
        val lexicon = decodeLexicon(merged.bytes, merged.supplementMask)
        val original = lexicon.exact(listOf("ㄋㄧˇ")).single()
        assertFalse(original.supplement)
        assertEquals(0.5f, original.prw, 0f)
        val added = lexicon.exact(listOf("ㄓㄨㄥ")).single()
        assertTrue(added.supplement)
        assertTrue(added.rare)
    }

    @Test
    fun eightNonRareWordsKeepTheirOrder() {
        val words = listOf("你好", "妳好", "泥好", "尼好", "呢好", "擬好", "倪好", "匿好")
        val rows = ArrayList<LexiconRow>()
        words.forEachIndexed { index, word ->
            rows += row(intArrayOf(0, 1), word, (0.9f - index * 0.1f), 0)
        }
        rows += row(intArrayOf(0, 1), "補詞", 1f, SUPPLEMENT)
        rows += row(0, "呢", 0.99f, SUPPLEMENT)
        val engine = engine(lexicon(SYLLABLES, rows))
        val selected = engine.select(SYLLABLES, "", null, useModel = false)
        assertEquals(words, selected.candidates)
        assertFalse(selected.candidates.contains("補詞"))
        assertFalse(selected.candidates.contains("呢"))
    }

    @Test
    fun supplementArcsStayOutOfTheBeam() {
        val rows = listOf(
            row(intArrayOf(0, 1), "你好", 0.4f, 0),
            row(0, "你", 0.3f, 0),
            row(1, "號", 0.99f, SUPPLEMENT),
        )
        val selected = engine(lexicon(SYLLABLES, rows)).select(SYLLABLES, "", null, useModel = false)
        assertEquals(listOf("你好"), selected.candidates)
        assertFalse(selected.candidates.contains("你號"))
    }

    @Test
    fun supplementFillsBehindTheLingxiRow() {
        val rows = listOf(
            row(intArrayOf(0, 1), "你好", 0.4f, 0),
            row(intArrayOf(0, 1), "妳好", 0.95f, SUPPLEMENT),
            row(0, "你", 0.3f, 0),
            row(1, "號", 0.99f, SUPPLEMENT),
        )
        val selected = engine(lexicon(SYLLABLES, rows)).select(SYLLABLES, "", null, useModel = false)
        assertEquals(listOf("你好", "妳好"), selected.candidates)
    }

    @Test
    fun supplementBecomesFirstWhenLingxiHasNoRow() {
        val rows = listOf(row(intArrayOf(0, 1), "補詞", 0.2f, SUPPLEMENT))
        val selected = engine(lexicon(SYLLABLES, rows)).select(SYLLABLES, "", null, useModel = false)
        assertEquals(listOf("補詞"), selected.candidates)
    }

    @Test
    fun longerSupplementDoesNotStealTheGrayTail() {
        val syllables = ArrayList<String>()
        syllables += "ㄋㄧˇ"
        repeat(32) { syllables += "S$it" }
        syllables += "ㄇㄣ"
        val rows = ArrayList<LexiconRow>()
        rows += row(0, "你", 0.8f, 0)
        rows += row(intArrayOf(0, 33), "你們", 0.4f, 0)
        repeat(32) { index ->
            rows += row(intArrayOf(0, index + 1), "你補", 0.99f, SUPPLEMENT)
        }
        val bytes = lexicon(syllables, rows)
        val decoded = decodeLexicon(bytes)
        assertEquals(listOf("你們"), decoded.longer(listOf("ㄋㄧˇ"), limit = 32).map { it.surface })
        val lm = lmOf(listOf("你", "你們"), listOf("你" to "你們"))
        assertTrue(lm.bi("你", "你們") > 0.0)
        val selected = engine(decoded, lm).select(listOf("ㄋㄧˇ"), "你", null, useModel = false)
        assertEquals("你", selected.candidates.first())
        assertEquals("們", selected.completion?.tail)
        assertEquals("你們", selected.completion?.surface)
    }

    @Test
    fun compositionPlacesSupplementBetweenSentenceAndBopomofo() {
        val words = listOf("你好", "妳好", "泥好", "尼好", "呢好", "擬好", "倪好", "匿好")
        val rows = ArrayList<LexiconRow>()
        words.forEachIndexed { index, word ->
            rows += row(intArrayOf(0, 1), word, (0.9f - index * 0.1f), 0)
        }
        rows += row(intArrayOf(0, 1), "你好", 1f, SUPPLEMENT)
        rows += row(intArrayOf(0, 1), "補詞", 0.99f, SUPPLEMENT)
        rows += row(0, "你", 0.05f, 0)
        rows += row(0, "呢", 0.99f, SUPPLEMENT)
        val host = MergeHost("")
        val session = session(host, engine(lexicon(SYLLABLES, rows)))
        type(session, "ㄋㄧˇㄏㄠˇ")
        val shown = host.snapshots.last().candidates
        val literalAt = shown.indexOfFirst { it.literal }
        assertTrue(literalAt > 0)
        val before = shown.take(literalAt)
        assertEquals(words + "補詞", before.map { it.text })
        assertEquals(1, shown.count { it.text == "你好" })
        val chip = shown.single { it.text == "補詞" }
        assertEquals(2, chip.span)
        assertFalse(chip.literal)
        assertFalse(chip.completion)
        assertEquals("ㄋㄧˇㄏㄠˇ", shown.single { it.literal }.text)
        val letters = shown.filter { it.span == 1 && !it.literal && !it.completion }.map { it.text }
        assertEquals(listOf("你"), letters)
        assertFalse(shown.any { it.text == "呢" })
        assertTrue(shown.indexOfFirst { it.text == "你" && it.span == 1 } > literalAt)
        session.onEnter()
        assertEquals(listOf("你好"), host.commits)
    }

    @Test
    fun untonedSupplementSurfaceAppearsOnce() {
        val forms = listOf("ㄋㄧˇ", "ㄋㄧˊ", "ㄏㄠˇ", "ㄏㄠˊ")
        val words = listOf("你好", "妳好", "泥好", "尼好", "呢好", "擬好", "倪好", "匿好")
        val rows = ArrayList<LexiconRow>()
        words.forEachIndexed { index, word ->
            rows += row(intArrayOf(0, 2), word, 0.9f - index * 0.05f, 0)
        }
        rows += row(intArrayOf(0, 2), "補詞", 0.4f, SUPPLEMENT)
        rows += row(intArrayOf(1, 3), "補詞", 0.99f, SUPPLEMENT)
        rows += row(intArrayOf(0, 2), "她好", 0.5f, SUPPLEMENT)
        val host = MergeHost("")
        val session = session(host, engine(lexicon(forms, rows)))
        type(session, "ㄋㄧㄏㄠ")
        val shown = host.snapshots.last().candidates
        assertEquals(1, shown.count { it.text == "補詞" })
        val chip = shown.single { it.text == "補詞" }
        assertEquals(2, chip.span)
        assertFalse(chip.literal)
        assertFalse(chip.completion)
        val literalAt = shown.indexOfFirst { it.literal }
        val chipAt = shown.indexOfFirst { it.text == "補詞" }
        val otherAt = shown.indexOfFirst { it.text == "她好" }
        assertTrue(chipAt < otherAt)
        assertTrue(otherAt < literalAt)
        assertEquals("ㄋㄧㄏㄠ", shown[literalAt].text)
    }

    @Test
    fun enterSendsSupplementWhenLingxiHasNoCoveringWord() {
        val rows = listOf(row(intArrayOf(0, 1), "補詞", 0.3f, SUPPLEMENT))
        val host = MergeHost("")
        val session = session(host, engine(lexicon(SYLLABLES, rows)))
        type(session, "ㄋㄧˇㄏㄠˇ")
        val shown = host.snapshots.last().candidates
        assertEquals("補詞", shown.first { !it.literal }.text)
        assertTrue(shown.indexOfFirst { it.text == "補詞" } < shown.indexOfFirst { it.literal })
        session.onEnter()
        assertEquals(listOf("補詞"), host.commits)
    }

    @Test
    fun supplementOnlySurfaceDoesNotStealLeftContext() {
        val syllables = listOf("ㄋㄧˇ", "ㄏㄠˇ", "ㄚ")
        val rows = listOf(
            row(intArrayOf(0, 1), "你好", 0.9f, SUPPLEMENT),
            row(1, "好", 0.4f, 0),
            row(intArrayOf(1, 2), "好啊", 0.4f, 0),
        )
        val decoded = decodeLexicon(lexicon(syllables, rows))
        val lm = lmOf(listOf("好", "好啊"), listOf("好" to "好啊"))
        val tables = LingxiTables(decoded, lm, LingxiCensus(1, 1, 1, 1, 1, 1))
        assertEquals("好", lingxiLeftWord(tables, "你好"))
        val selected = LingxiEngine(tables, null).select(listOf("ㄏㄠˇ"), "你好", null, useModel = false)
        assertEquals("好", selected.candidates.first())
        assertEquals("啊", selected.completion?.tail)
        assertEquals("好啊", selected.completion?.surface)
    }

    @Test
    fun modelReordersLingxiRowsAndLeavesSupplementsBehind() {
        val rows = listOf(
            row(intArrayOf(0, 1), "你好", 0.5f, 0),
            row(intArrayOf(0, 1), "妳好", 0.4f, 0),
            row(intArrayOf(0, 1), "補詞", 0.99f, SUPPLEMENT),
        )
        val seen = ArrayList<List<String>>()
        val selected = LingxiEngine(tablesOf(lexicon(SYLLABLES, rows)), boostingScorer(seen))
            .select(SYLLABLES, "前文", null)
        assertEquals(listOf(listOf("你好", "妳好")), seen)
        assertEquals(listOf("妳好", "你好", "補詞"), selected.candidates)
        assertEquals("ok", selected.reorder)
        val alone = ArrayList<List<String>>()
        val one = LingxiEngine(
            tablesOf(lexicon(SYLLABLES, listOf(rows[0], rows[2]))),
            boostingScorer(alone),
        ).select(SYLLABLES, "前文", null)
        assertTrue(alone.isEmpty())
        assertEquals("skip", one.reorder)
        assertEquals(listOf("你好", "補詞"), one.candidates)
    }

    @Test
    fun createdPhraseStaysAheadOfTheSupplementChip() {
        val words = listOf("你好", "妳好", "泥好", "尼好", "呢好", "擬好", "倪好", "匿好")
        val rows = ArrayList<LexiconRow>()
        words.forEachIndexed { index, word ->
            rows += row(intArrayOf(0, 1), word, 0.9f - index * 0.1f, 0)
        }
        rows += row(intArrayOf(0, 1), "補詞", 0.99f, SUPPLEMENT)
        rows += row(intArrayOf(0, 1), "她好", 0.95f, SUPPLEMENT)
        val host = MergeHost("")
        host.created = listOf(
            Candidate(
                text = "補詞",
                engineIndex = -1,
                source = CandidateSource.SYNTHETIC,
                span = 2,
                reading = "ㄋㄧˇㄏㄠˇ",
            ),
        )
        val session = session(host, engine(lexicon(SYLLABLES, rows)))
        type(session, "ㄋㄧˇㄏㄠˇ")
        val shown = host.snapshots.last().candidates
        val createdAt = shown.indexOfFirst { it.text == "補詞" }
        val sentenceAt = shown.indexOfFirst { it.text == "你好" }
        val otherAt = shown.indexOfFirst { it.text == "她好" }
        assertTrue(createdAt >= 0 && sentenceAt >= 0 && otherAt >= 0)
        assertTrue(createdAt < sentenceAt)
        assertTrue(sentenceAt < otherAt)
        assertEquals(1, shown.count { it.text == "補詞" })
        session.onEnter()
        assertEquals(listOf("補詞"), host.commits)
    }

    @Test
    fun throwingUserUnigramReadKeepsDecodedLexicon() {
        val decoded = decodeLexicon(lexicon(listOf("ㄋㄧˇ"), listOf(row(0, "你", 0.4f, 0))))
        val tables = LingxiTables(decoded, bareLm(), LingxiCensus(1, 1, 1, 1, 1, 1))
        val junk = File.createTempFile("lingxi-user-unigrams", ".db")
        junk.writeBytes(byteArrayOf(1, 2, 3, 4))
        junk.deleteOnExit()
        val lines = ArrayList<String>()
        val kept = mergeUserUnigrams(tables, junk) { lines += it }
        assertEquals(1, lines.size)
        assertTrue(lines[0].startsWith("lingxi user unigrams skipped "))
        assertFalse(lines[0].contains("你"))
        assertSame(tables, kept)
        assertEquals("你", kept.lexicon.exact(listOf("ㄋㄧˇ")).single().surface)
    }

    @Test
    fun missingUserUnigramFileIsSkipped() {
        val missing = File(System.getProperty("java.io.tmpdir"), "lingxi-no-user-unigrams.db")
        missing.delete()
        assertFalse(missing.isFile)
        assertTrue(readUserUnigramRows(missing).isEmpty())
    }

    @Test
    fun asciiDumpKeepsThreeFields() {
        val raw = "01\u001f你\u001f-0.5\u001e!!\u001f\u001fnope\u001e".toByteArray(Charsets.UTF_8)
        val rows = chiaRowsFromAscii(raw)
        assertEquals(1, rows.size)
        assertEquals("01", rows[0].qstring)
        assertEquals("你", rows[0].surface)
        assertEquals(-0.5, rows[0].probability, 0.0)
    }

    private fun lexicon(syllables: List<String>, rows: List<LexiconRow>): ByteArray {
        return encodeLexicon(syllables, rows)
    }

    private fun engine(bytes: ByteArray, lm: LingxiLm = bareLm()): LingxiEngine {
        return engine(decodeLexicon(bytes), lm)
    }

    private fun engine(lexicon: LingxiLexicon, lm: LingxiLm = bareLm()): LingxiEngine {
        return LingxiEngine(LingxiTables(lexicon, lm, LingxiCensus(1, 1, 1, 1, 1, 1)), null)
    }

    private fun tablesOf(bytes: ByteArray): LingxiTables {
        return LingxiTables(decodeLexicon(bytes), bareLm(), LingxiCensus(1, 1, 1, 1, 1, 1))
    }

    private fun boostingScorer(seen: MutableList<List<String>>): LingxiScorer {
        return object : LingxiScorer {
            override val manifest = LingxiManifest(
                weight = 1.0,
                textWeight = 0.0,
                neutralExpand = false,
                neutralBases = emptySet(),
                neutralExact = emptySet(),
                warmContext = "",
                warmBase = "",
                warmCandidates = emptyList(),
            )

            override fun score(
                context: String,
                reading: List<String>,
                candidates: List<String>,
                textOnly: Boolean,
            ): DoubleArray {
                seen += candidates
                return DoubleArray(candidates.size) { index -> if (index == candidates.lastIndex) 10.0 else 0.0 }
            }

            override fun warm() = Unit
        }
    }

    private fun row(syllable: Int, surface: String, prw: Float, flags: Int): LexiconRow {
        return row(intArrayOf(syllable), surface, prw, flags)
    }

    private fun row(reading: IntArray, surface: String, prw: Float, flags: Int): LexiconRow {
        return LexiconRow(reading, surface, prw, flags)
    }

    private fun qstring(syllable: String): String = qstring(listOf(syllable))

    private fun qstring(syllables: List<String>): String {
        return BopomofoOrder.qstring(syllables) ?: error("qstring $syllables")
    }

    private fun session(host: MergeHost, engine: LingxiEngine): ZhuyinCompositionSession {
        val slot = LingxiSlot()
        slot.engine = engine
        return ZhuyinCompositionSession(MergeReadyEngine, MergeNoLexicon, host, slot)
    }

    private fun type(session: ZhuyinCompositionSession, glyphs: String) {
        for (glyph in glyphs) session.onKey(KeyAction.Bopomofo(glyph.toString(), ' '))
    }

    private fun bareLm(): LingxiLm {
        return LingxiLm(byteArrayOf(), intArrayOf(0), floatArrayOf(), intArrayOf(0), byteArrayOf(), byteArrayOf())
    }

    private fun lmOf(words: List<String>, pairs: List<Pair<String, String>>): LingxiLm {
        val ids = HashMap<String, Int>(words.size)
        val blob = ArrayList<Byte>()
        val off = ArrayList<Int>()
        off += 0
        for (word in words) {
            ids[word] = ids.size
            blob += word.toByteArray(Charsets.UTF_8).toList()
            off += blob.size
        }
        val grouped = pairs.groupBy { ids.getValue(it.first) }
        val leftStart = IntArray(words.size + 1)
        val right = ArrayList<Byte>()
        val quality = ArrayList<Byte>()
        var cursor = 0
        for (left in words.indices) {
            leftStart[left] = cursor
            val edges = grouped[left].orEmpty().sortedBy { ids.getValue(it.second) }
            for (edge in edges) {
                val rightId = ids.getValue(edge.second)
                right += (rightId and 0xFF).toByte()
                right += ((rightId ushr 8) and 0xFF).toByte()
                right += ((rightId ushr 16) and 0xFF).toByte()
                quality += 0
                cursor += 1
            }
        }
        leftStart[words.size] = cursor
        return LingxiLm(
            blob.toByteArray(),
            off.toIntArray(),
            FloatArray(words.size) { 1f },
            leftStart,
            right.toByteArray(),
            quality.toByteArray(),
        )
    }

    private companion object {
        val SYLLABLES = listOf("ㄋㄧˇ", "ㄏㄠˇ")
        const val SUPPLEMENT = 6
    }
}

private object MergeReadyEngine : ZhuyinEngine {
    private val idle = ChiaKeyFrame.parse("")
    override val ready = true
    override fun handleAscii(key: Char) = idle
    override fun handleKey(code: Int) = idle
    override fun select(index: Int) = idle
    override fun acknowledge() = Unit
    override fun reset() = Unit
}

private object MergeNoLexicon : Lexicon {
    override fun following(before: String): List<String> = emptyList()
    override fun partialCandidates(readings: List<SyllableReading>): List<Candidate> = emptyList()
}

private class MergeHost(private val context: String) : ZhuyinHost {
    val commits = ArrayList<String>()
    val snapshots = ArrayList<EngineSnapshot>()
    var created: List<Candidate> = emptyList()

    override fun mode(): KeyboardMode = KeyboardMode.ZHUYIN
    override fun commit(text: String) { commits += text }
    override fun apply(snapshot: EngineSnapshot) { snapshots += snapshot }
    override fun edit(block: () -> Unit) { block() }
    override fun promote(candidate: Candidate) = Unit
    override fun rememberPhrase(candidate: Candidate) = Unit
    override fun savePreference() = Unit
    override fun createdMatching(reading: String): List<Candidate> {
        return created.filter { it.reading == reading }
    }
    override fun refreshIdleSuggestions() = Unit
    override fun committedContext(): String = context
    override fun lingxiLine(message: String) = Unit
}
