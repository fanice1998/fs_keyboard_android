package com.fskeyboard.ime.lingxi

import com.fskeyboard.ime.candidate.Candidate
import com.fskeyboard.ime.candidate.CandidateSource
import com.fskeyboard.ime.candidate.SyllableReading
import com.fskeyboard.ime.candidate.accentIndex
import com.fskeyboard.ime.candidate.coversOpenSyllables
import com.fskeyboard.ime.candidate.visibleLabel
import com.fskeyboard.ime.compose.ZhuyinCompositionSession
import com.fskeyboard.ime.compose.ZhuyinHost
import com.fskeyboard.ime.engine.ChiaKeyFrame
import com.fskeyboard.ime.engine.EngineSnapshot
import com.fskeyboard.ime.engine.ZhuyinEngine
import com.fskeyboard.ime.keyboard.KeyAction
import com.fskeyboard.ime.keyboard.KeyboardMode
import com.fskeyboard.ime.lexicon.Lexicon
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
        val open = engine.select(reading, "我ㄗㄞˋ", remembered = null)
        val behind = engine.select(reading, "我ㄗㄞ", remembered = null)
        println(
            "zai open first=${open.candidates.first()} reorder=${open.reorder} " +
                "behind=${behind.candidates.first()} reorder=${behind.reorder}",
        )
        assertEquals("ok", open.reorder)
        assertEquals("ok", behind.reorder)
        assertEquals("在", open.candidates.first())
        assertEquals("在", behind.candidates.first())
        assertEquals("我", withoutOpenReading("我ㄗㄞˋ", reading))
        assertEquals("我", withoutOpenReading("我ㄗㄞ", reading))
        assertEquals("我在", withoutOpenReading("我在ㄗㄞˋ", reading))
        assertEquals("請再試一次", withoutOpenReading("請再試一次", reading))
        assertEquals("miss", lingxiReorderLabel(null, deadlineNs = 10, now = 11))
        assertEquals("miss", lingxiReorderLabel(listOf("在"), deadlineNs = 10, now = 11))
        assertEquals("skip", lingxiReorderLabel(null, deadlineNs = Long.MAX_VALUE, now = 11))
        val expired = engine.select(reading, "我ㄗㄞˋ", remembered = null, deadlineNs = System.nanoTime())
        val openStat = engine.select(reading, "我", remembered = null, useModel = false)
        println("zai expired reorder=${expired.reorder} first=${expired.candidates.first()}")
        assertEquals("miss", expired.reorder)
        assertEquals(openStat.candidates.first(), expired.candidates.first())
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

    @Test
    fun untonedReadingStillFindsTheTonedWord() {
        val open = booleanArrayOf(true)
        val zai = engine.select(listOf("ㄗㄞ"), "", remembered = null, useModel = false, openTone = open)
        val level = engine.select(listOf("ㄗㄞ"), "", remembered = null, useModel = false)
        println("untoned zai=${zai.candidates} level=${level.candidates}")
        assertTrue(zai.candidates.contains("在"))
        assertTrue(zai.candidates.contains("再"))
        assertTrue("在" !in level.candidates)
        val hello = engine.select(
            listOf("ㄋㄧ", "ㄏㄠ"),
            "",
            remembered = null,
            useModel = false,
            openTone = booleanArrayOf(true, true),
        )
        println("untoned hello=${hello.candidates}")
        assertTrue(hello.candidates.contains("你好"))
        val half = engine.select(
            listOf("ㄋㄧˇ", "ㄏㄠ"),
            "",
            remembered = null,
            useModel = false,
            openTone = booleanArrayOf(false, true),
        )
        assertTrue(half.candidates.contains("你好"))
    }

    @Test
    fun untonedLongPhraseOfSixteenSymbolsStaysFast() {
        val syllables = listOf("ㄓㄨㄥ", "ㄏㄨㄚ", "ㄇㄧㄣ", "ㄍㄨㄛ", "ㄓㄨㄤ", "ㄑㄧㄡ", "ㄗㄨㄥ", "ㄏㄨㄟ")
        val symbols = syllables.sumOf { it.length }
        assertTrue(symbols >= 16)
        val open = BooleanArray(syllables.size) { true }
        val surface = "中華民國撞球總會"
        val hits = loadLingxiTables(packRoot()).lexicon.exact(syllables, open)
        assertTrue(hits.any { it.surface == surface })
        assertTrue(engine.census.words > 0)
        val firstStarted = System.nanoTime()
        val first = engine.select(syllables, "", remembered = null, useModel = false, openTone = open)
        val firstMs = (System.nanoTime() - firstStarted) / 1_000_000L
        val started = System.nanoTime()
        val selected = engine.select(syllables, "", remembered = null, useModel = false, openTone = open)
        val millis = (System.nanoTime() - started) / 1_000_000L
        println(
            "untoned long symbols=$symbols syllables=${syllables.size} " +
                "firstMs=$firstMs warmMs=$millis candidates=${selected.candidates}",
        )
        assertTrue(first.candidates.contains(surface))
        assertTrue(selected.candidates.contains(surface))
        assertEquals(first.candidates, selected.candidates)
        assertTrue(millis < 40L)
    }

    @Test
    fun longerReadingSkipsTheExactWordAndStopsAtTheLimit() {
        val lexicon = loadLingxiTables(packRoot()).lexicon
        val prefix = listOf("ㄋㄧˇ")
        val capped = lexicon.longer(prefix, limit = 1)
        val wider = lexicon.longer(prefix, limit = 3)
        println("longer ni3 cap=${capped.map { it.surface }} wide=${wider.map { it.surface }}")
        assertTrue(capped.size <= 1)
        assertTrue(wider.size <= 3)
        assertTrue(wider.all { it.syllables > prefix.size })
        assertEquals(capped.map { it.surface }, wider.take(capped.size).map { it.surface })
        val open = lexicon.longer(listOf("ㄋㄧ"), booleanArrayOf(true), limit = 8)
        println("longer open ni=${open.map { it.reading to it.surface }}")
        assertTrue(open.any { 'ˊ' in it.reading || 'ˇ' in it.reading || 'ˋ' in it.reading || '˙' in it.reading })
    }

    @Test
    fun completionContinuesOnlyAConfidentLongerWord() {
        val tables = loadLingxiTables(packRoot())
        val syllables = listOf("ㄇㄚˊ")
        val context = "晚上想吃"
        assertNull(engine.completion(syllables, BooleanArray(0), context, ""))
        assertNull(engine.completion(listOf("ㄚ", "ㄚ", "ㄚ"), booleanArrayOf(true, true, true), "我", "啊"))
        assertNull(engine.completion(syllables, BooleanArray(0), context, "麻", deadlineNs = 1L))
        val selected = engine.select(syllables, context, remembered = null, useModel = false)
        val head = selected.candidates.first()
        val hint = engine.completion(syllables, BooleanArray(0), context, head)
        val left = lingxiLeftWord(tables, context)
        val eligible = tables.lexicon.longer(syllables, BooleanArray(0), 32).filter { hit ->
            val extra = hit.syllables - syllables.size
            extra in 1..4 && hit.syllables <= 8 &&
                hit.surface.startsWith(head) &&
                hit.surface.codePointCount(0, hit.surface.length) > head.codePointCount(0, head.length) &&
                tables.lm.bi(left, hit.surface) > 0.0
        }
        assertEquals("嘛", head)
        assertTrue(eligible.isEmpty())
        assertNull(hint)
        assertNull(selected.completion)
        val modeled = engine.select(syllables, context, remembered = null)
        assertEquals("麻", modeled.candidates.first())
        val ma = modeled.completion
        assertNotNull(ma)
        assertEquals("麻婆豆腐", ma!!.surface)
        assertEquals("婆豆腐", ma.tail)
        assertEquals("ㄇㄚˊㄆㄛˊㄉㄡˋㄈㄨˇ", ma.reading)
        assertTrue(tables.lm.bi(left, ma.surface) > 0.0)
        assertFalse(modeled.candidates.contains(ma.surface))
        assertEquals(ma, engine.completion(syllables, BooleanArray(0), context, "麻"))
    }

    @Test
    fun typingKeepsTheHintOffTheUnderlineUntilTheChipIsTapped() {
        val host = RecordingHost("請問")
        val session = session(host)
        type(session, "ㄋㄧˇ")
        val row = host.snapshots.last().candidates
        val homophones = row.filter { !it.completion }
        val preedit = host.snapshots.last().preedit
        assertEquals("ㄋㄧˇ", preedit)
        assertEquals(session.previewComposing + session.previewReading, preedit)
        assertEquals(1, session.openSyllables)
        assertEquals("你", homophones.first().text)
        assertFalse(homophones.any { it.completion })
        assertFalse(homophones.any { it.text == "你們" })
        val chip = row.first()
        assertEquals(1, row.count { it.completion })
        assertTrue(chip.completion)
        assertEquals("你們", chip.text)
        assertEquals("ㄋㄧˇ", chip.labelReading)
        assertEquals("們", chip.labelTail)
        assertEquals("ㄋㄧˇ們", chip.visibleLabel())
        assertEquals("們", session.previewHint)
        assertEquals("ㄋㄧˇㄇㄣˊ", chip.reading)
        assertEquals(homophones.first().text + session.previewHint, chip.text)
        assertEquals(row.indexOf(homophones.first()), accentIndex(row, session.openSyllables))
        assertFalse(preedit.contains(session.previewHint))
        val line = host.lines.last()
        assertTrue(line.startsWith("lingxi key-to-row ms="))
        assertTrue(line.contains(" reorder="))
        assertFalse(line.contains("ㄋ"))
        assertFalse(line.contains("你"))
        val expected = engine.select(
            listOf("ㄋㄧˇ"),
            "請問",
            remembered = null,
            useModel = line.endsWith("reorder=ok"),
        ).candidates
        assertEquals(expected, homophones.map { it.text })
        session.onEnter()
        assertEquals(listOf("你"), host.commits)
        type(session, "ㄋㄧˇ")
        val afterEnter = host.snapshots.last().candidates
        assertTrue(afterEnter.first().completion)
        assertEquals("ㄋㄧˇㄇㄣˊ", afterEnter.first().reading)
        assertEquals("你", afterEnter.first { it.coversOpenSyllables(1) }.text)

        val spaceHost = RecordingHost("晚上想吃")
        val spaced = session(spaceHost)
        type(spaced, "ㄇㄚ")
        spaced.onSpace()
        assertTrue(spaceHost.commits.isEmpty())
        assertEquals("ㄇㄚ", spaceHost.snapshots.last().preedit)
        val level = spaceHost.snapshots.last().candidates.first { it.coversOpenSyllables(1) }
        assertFalse(level.completion)
        spaced.onEnter()
        assertEquals(listOf(level.text), spaceHost.commits)

        val tapHost = RecordingHost("請問")
        val tapping = session(tapHost)
        type(tapping, "ㄋㄧˇ")
        val completion = tapHost.snapshots.last().candidates.first()
        assertTrue(completion.completion)
        tapping.acceptCandidate(completion)
        assertEquals(listOf("你們"), tapHost.commits)
        assertEquals("", tapping.previewHint)
        assertEquals("", tapping.previewReading)
        type(tapping, "ㄋㄧˇ")
        val again = tapHost.snapshots.last().candidates
        assertTrue(again.first().completion)
        assertEquals("你們", again.first().text)
        assertEquals("你", again.first { it.coversOpenSyllables(1) }.text)

        val madeHost = RecordingHost("請問")
        madeHost.created = listOf(
            Candidate(
                text = "自造",
                engineIndex = -1,
                source = CandidateSource.SYNTHETIC,
                span = 1,
                reading = "ㄋㄧˇ",
            ),
        )
        val made = session(madeHost)
        type(made, "ㄋㄧˇ")
        val madeRow = madeHost.snapshots.last().candidates
        assertTrue(madeRow.first().completion)
        assertEquals("你們", madeRow.first().text)
        assertEquals("自造", madeRow[1].text)
        assertFalse(madeRow[1].completion)
        assertTrue(madeRow[1].coversOpenSyllables(1))
        assertEquals("你", madeRow.drop(2).first { !it.completion }.text)
        made.onEnter()
        assertEquals(listOf("自造"), madeHost.commits)
    }

    @Test
    fun enterAndFinishConvertTheSameCoveringHanzi() {
        val entered = RecordingHost("請問")
        val enterSession = session(entered)
        type(enterSession, "ㄋㄧˇ")
        enterSession.onEnter()
        val finished = RecordingHost("請問")
        val finishSession = session(finished)
        type(finishSession, "ㄋㄧˇ")
        finishSession.finish()
        assertEquals(listOf("你"), entered.commits)
        assertEquals(entered.commits, finished.commits)

        val rawEnter = RecordingHost("")
        val rawEnterSession = session(rawEnter)
        type(rawEnterSession, "ㄅ")
        assertTrue(rawEnter.snapshots.last().candidates.none { it.coversOpenSyllables(1) })
        rawEnterSession.onEnter()
        val rawFinish = RecordingHost("")
        val rawFinishSession = session(rawFinish)
        type(rawFinishSession, "ㄅ")
        rawFinishSession.finish()
        assertEquals(listOf("ㄅ"), rawEnter.commits)
        assertEquals(rawEnter.commits, rawFinish.commits)
    }

    private fun session(host: RecordingHost): ZhuyinCompositionSession {
        val slot = LingxiSlot()
        slot.engine = engine
        return ZhuyinCompositionSession(IdleZhuyinEngine, NoLexicon, host, slot)
    }

    private fun type(session: ZhuyinCompositionSession, glyphs: String) {
        for (glyph in glyphs) session.onKey(KeyAction.Bopomofo(glyph.toString(), ' '))
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

private object IdleZhuyinEngine : ZhuyinEngine {
    private val idle = ChiaKeyFrame.parse("")
    override val ready = true
    override fun handleAscii(key: Char) = idle
    override fun handleKey(code: Int) = idle
    override fun select(index: Int) = idle
    override fun acknowledge() = Unit
    override fun reset() = Unit
}

private object NoLexicon : Lexicon {
    override fun following(before: String): List<String> = emptyList()
    override fun partialCandidates(readings: List<SyllableReading>): List<Candidate> = emptyList()
}

private class RecordingHost(private val context: String) : ZhuyinHost {
    val commits = ArrayList<String>()
    val snapshots = ArrayList<EngineSnapshot>()
    val lines = ArrayList<String>()
    var created: List<Candidate> = emptyList()

    override fun mode(): KeyboardMode = KeyboardMode.ZHUYIN
    override fun commit(text: String) { commits += text }
    override fun apply(snapshot: EngineSnapshot) { snapshots += snapshot }
    override fun edit(block: () -> Unit) { block() }
    override fun promote(candidate: Candidate) = Unit
    override fun rememberPhrase(candidate: Candidate) = Unit
    override fun savePreference() = Unit
    override fun createdMatching(reading: String): List<Candidate> = created
    override fun refreshIdleSuggestions() = Unit
    override fun committedContext(): String = context
    override fun lingxiLine(message: String) { lines += message }
}
