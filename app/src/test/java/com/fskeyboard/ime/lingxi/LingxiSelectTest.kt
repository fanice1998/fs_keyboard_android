package com.fskeyboard.ime.lingxi

import com.fskeyboard.ime.candidate.BopomofoChain
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
import com.fskeyboard.ime.keyboard.TouchModel
import com.fskeyboard.ime.keyboard.ZhuyinClass
import com.fskeyboard.ime.keyboard.ZhuyinPhonology
import com.fskeyboard.ime.keyboard.portraitZhuyinAreas
import com.fskeyboard.ime.lexicon.Lexicon
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
    fun anInitialListsCharactersAndInitialsListHello() {
        val lexicon = loadLingxiTables(packRoot()).lexicon
        val open = booleanArrayOf(true)
        val one = lexicon.exact(listOf("ㄋ"), open)
        assertTrue(one.any { it.surface == "你" })
        assertTrue(one.none { it.surface == "你好" })
        val initials = lexicon.exact(listOf("ㄋ", "ㄏ"), booleanArrayOf(true, true))
        assertTrue(initials.any { it.surface == "你好" })
        val selected = engine.select(listOf("ㄋ"), "", remembered = null, useModel = false, openTone = open)
        assertTrue(selected.candidates.isNotEmpty())
        assertTrue("你好" !in selected.candidates)
        val hello = engine.select(
            listOf("ㄋ", "ㄏ"),
            "",
            remembered = null,
            useModel = false,
            openTone = booleanArrayOf(true, true),
        )
        assertTrue(hello.candidates.contains("你好"))
        val nearNi = centreTouch("ㄋ")
        val nearHe = centreTouch("ㄏ")
        assertTrue(nearHe.any { it.probability == 0f && it.label != "ㄏ" })
        val plain = RecordingHost("")
        val plainSession = session(plain)
        type(plainSession, "ㄋㄏ")
        val plainCover = plain.snapshots.last().candidates.first { it.coversOpenSyllables(2) }
        val host = RecordingHost("")
        val typing = session(host)
        typing.onKey(KeyAction.Bopomofo("ㄋ", 's', nearby = nearNi))
        val typed = host.snapshots.last().candidates.filter { !it.literal }
        assertTrue(typed.isNotEmpty())
        assertTrue(typed.none { it.text == "你好" })
        typing.onKey(KeyAction.Bopomofo("ㄏ", 'c', nearby = nearHe))
        val row = host.snapshots.last().candidates
        assertEquals("ㄋㄏ", host.snapshots.last().preedit)
        assertTrue(row.any { it.text == "你好" && !it.literal })
        assertEquals("ㄋㄏ", row.single { it.literal }.text)
        val cover = row.first { it.coversOpenSyllables(2) }
        assertEquals(plainCover.text, cover.text)
        typing.onEnter()
        assertEquals(listOf(cover.text), host.commits)
    }

    @Test
    fun aCompleteSyllableNextToAnInitialStillListsWords() {
        val lexicon = loadLingxiTables(packRoot()).lexicon
        val open = booleanArrayOf(true, true)
        val hello = lexicon.exact(listOf("ㄋ", "ㄏㄠ"), open)
        assertTrue(hello.map { it.surface }.toString(), hello.any { it.surface == "你好" })
        val selected = engine.select(
            listOf("ㄋ", "ㄏㄠ"),
            "",
            remembered = null,
            useModel = false,
            openTone = open,
        )
        assertTrue(selected.candidates.toString(), selected.candidates.contains("你好"))
        val reversed = lexicon.exact(listOf("ㄏㄠ", "ㄋ"), open)
        assertTrue(reversed.map { it.surface }.toString(), reversed.any { it.surface == "好難" })
        val reversedSelect = engine.select(
            listOf("ㄏㄠ", "ㄋ"),
            "",
            remembered = null,
            useModel = false,
            openTone = open,
        )
        assertTrue(reversedSelect.candidates.toString(), reversedSelect.candidates.contains("好難"))
    }

    @Test
    fun anImpossibleSyllableTriesANeighbourKey() {
        val missed = engine.select(
            listOf("ㄅㄧㄚ"),
            "",
            remembered = null,
            useModel = false,
            openTone = booleanArrayOf(true),
        )
        assertTrue(missed.candidates.isEmpty())
        val areas = portraitZhuyinAreas()
        val key = areas.single { it.label == "ㄚ" }
        val nearby = TouchModel.withAdjacent(
            TouchModel.nearby(key.centerX, key.centerY, areas),
            "ㄚ",
            areas,
        )
        assertTrue(nearby.any { it.label == "ㄠ" })
        val host = RecordingHost("")
        val session = session(host)
        session.onKey(KeyAction.Bopomofo("ㄅ", '1'))
        session.onKey(KeyAction.Bopomofo("ㄧ", 'u'))
        session.onKey(KeyAction.Bopomofo("ㄚ", '8', nearby = nearby))
        val row = host.snapshots.last().candidates
        assertEquals("ㄅㄧㄚ", host.snapshots.last().preedit)
        val fixed = row.filter { !it.literal && it.text.isNotEmpty() }
        assertTrue(fixed.map { it.text }.toString(), fixed.any { it.text.codePointCount(0, it.text.length) == 1 })
        val cover = row.first { it.coversOpenSyllables(1) }
        assertEquals(cover.text, 1, cover.text.codePointCount(0, cover.text.length))
        assertEquals("ㄅㄧㄚ", row.single { it.literal }.text)
    }

    @Test
    fun aLegalPairThatIsNotAWordTriesTheNeighbour() {
        val open = booleanArrayOf(true, true)
        val typed = engine.select(
            listOf("ㄋㄧ", "ㄏㄤ"),
            "",
            remembered = null,
            useModel = false,
            openTone = open,
        )
        assertTrue("你好" !in typed.candidates)
        val areas = portraitZhuyinAreas()
        val key = areas.single { it.label == "ㄤ" }
        val nearby = TouchModel.withAdjacent(
            TouchModel.nearby(key.centerX, key.centerY, areas),
            "ㄤ",
            areas,
        )
        assertTrue(nearby.any { it.label == "ㄠ" })
        val host = RecordingHost("")
        val session = session(host)
        session.onKey(KeyAction.Bopomofo("ㄋ", 's'))
        session.onKey(KeyAction.Bopomofo("ㄧ", 'u'))
        session.onKey(KeyAction.Bopomofo("ㄏ", 'c'))
        session.onKey(KeyAction.Bopomofo("ㄤ", ';', nearby = nearby))
        val row = host.snapshots.last().candidates
        assertEquals("ㄋㄧㄏㄤ", host.snapshots.last().preedit)
        assertTrue(row.any { it.text == "你好" && !it.literal })
    }

    @Test
    fun aDifferentClassNeighbourStillCorrects() {
        val areas = portraitZhuyinAreas()
        val key = areas.single { it.label == "ㄗ" }
        val nearby = TouchModel.withAdjacent(
            TouchModel.nearby(key.centerX, key.centerY, areas),
            "ㄗ",
            areas,
        )
        assertTrue(nearby.any { it.label == "ㄧ" })
        val host = RecordingHost("")
        val session = session(host)
        session.onKey(KeyAction.Bopomofo("ㄋ", 's'))
        session.onKey(KeyAction.Bopomofo("ㄗ", 'y', nearby = nearby))
        val row = host.snapshots.last().candidates
        assertEquals("ㄋㄗ", host.snapshots.last().preedit)
        assertTrue(row.map { it.text }.toString(), row.any { it.text == "你" && !it.literal })
    }

    @Test
    fun aNeighbourSlipToAnInitialPrefixStillFindsHello() {
        val missed = engine.select(
            listOf("ㄋ", "ㄎ"),
            "",
            remembered = null,
            useModel = false,
            openTone = booleanArrayOf(true, true),
        )
        assertTrue("你好" !in missed.candidates)
        val areas = portraitZhuyinAreas()
        val key = areas.single { it.label == "ㄎ" }
        val nearby = TouchModel.withAdjacent(
            TouchModel.nearby(key.centerX, key.centerY, areas),
            "ㄎ",
            areas,
        )
        assertTrue(nearby.any { it.label == "ㄏ" })
        val host = RecordingHost("")
        val session = session(host)
        session.onKey(KeyAction.Bopomofo("ㄋ", 's'))
        session.onKey(KeyAction.Bopomofo("ㄎ", 'd', nearby = nearby))
        val row = host.snapshots.last().candidates
        assertEquals("ㄋㄎ", host.snapshots.last().preedit)
        assertTrue(row.map { it.text }.toString(), row.any { it.text == "你好" && !it.literal })
    }

    @Test
    fun theHigherScoringNeighbourBeatsAnEarlierOne() {
        val areas = portraitZhuyinAreas()
        val key = areas.single { it.label == "ㄤ" }
        val nearby = TouchModel.withAdjacent(
            TouchModel.nearby(key.centerX, key.centerY, areas),
            "ㄤ",
            areas,
        )
        val labels = nearby.map { it.label }
        assertTrue(labels.contains("ㄠ"))
        assertTrue(labels.indexOf("ㄠ") > 0)
        val plainHost = RecordingHost("")
        val plain = session(plainHost)
        plain.onKey(KeyAction.Bopomofo("ㄋ", 's'))
        plain.onKey(KeyAction.Bopomofo("ㄧ", 'u'))
        plain.onKey(KeyAction.Bopomofo("ㄏ", 'c'))
        plain.onKey(KeyAction.Bopomofo("ㄤ", ';'))
        val plainRow = plainHost.snapshots.last().candidates
        val plainTexts = plainRow.map { it.text }.toSet()
        val plainCover = plainRow.first { it.coversOpenSyllables(2) }
        val host = RecordingHost("")
        val typing = session(host)
        typing.onKey(KeyAction.Bopomofo("ㄋ", 's'))
        typing.onKey(KeyAction.Bopomofo("ㄧ", 'u'))
        typing.onKey(KeyAction.Bopomofo("ㄏ", 'c'))
        typing.onKey(KeyAction.Bopomofo("ㄤ", ';', nearby = nearby))
        val row = host.snapshots.last().candidates
        val cover = row.first { it.coversOpenSyllables(2) }
        assertEquals(plainCover.text, cover.text)
        val added = row.filter { !it.literal && !it.completion && it.text !in plainTexts }
        assertEquals(added.map { it.text }.toString(), "你好", added.first().text)
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
        assertEquals("你們", chip.visibleLabel())
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
        val hanzi = homophones.filter { !it.literal }
        assertEquals(expected, hanzi.map { it.text })
        assertEquals("ㄋㄧˇ", row.last { it.literal }.text)
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
        val guessed = rawEnter.snapshots.last().candidates.first { it.coversOpenSyllables(1) }
        assertFalse(guessed.literal)
        rawEnterSession.onEnter()
        val rawFinish = RecordingHost("")
        val rawFinishSession = session(rawFinish)
        type(rawFinishSession, "ㄅ")
        rawFinishSession.finish()
        assertEquals(listOf(guessed.text), rawEnter.commits)
        assertEquals(rawEnter.commits, rawFinish.commits)
    }

    @Test
    fun aLongerReadingKeepsSingleCharactersAndTheTypedBopomofo() {
        val host = RecordingHost("請問")
        val session = session(host)
        type(session, "ㄋㄧˇㄏㄠˇ")
        val row = host.snapshots.last().candidates
        assertEquals(2, session.openSyllables)
        val head = row.first { it.coversOpenSyllables(2) }
        assertFalse(head.literal)
        assertFalse(head.completion)
        val letters = row.filter { it.span == 1 && !it.literal && !it.completion }
        assertTrue(letters.any { it.text == "你" })
        val spoken = row.single { it.literal }
        assertEquals("ㄋㄧˇㄏㄠˇ", spoken.text)
        session.acceptCandidate(letters.first { it.text == "你" })
        assertTrue(host.commits.isEmpty())
        assertEquals("你", session.previewComposing)
        assertEquals("ㄏㄠˇ", session.previewReading)
        val rest = host.snapshots.last().candidates.first { it.coversOpenSyllables(1) }
        session.onEnter()
        assertEquals(listOf("你" + rest.text), host.commits)
    }

    @Test
    fun aStitchedSentenceIsNotReplacedByACentreNeighbour() {
        val lexicon = loadLingxiTables(packRoot()).lexicon
        val areas = portraitZhuyinAreas()
        val yuan = areas.single { it.label == "ㄛ" }
        val yuanNear = TouchModel.withAdjacent(
            TouchModel.nearby(yuan.centerX, yuan.centerY, areas),
            "ㄛ",
            areas,
        )
        val open = booleanArrayOf(true, true, true, true)
        val head = listOf("ㄓㄨㄥ", "ㄏㄨㄚ", "ㄇㄧㄣ")
        var alt = ""
        var statistical = emptyList<String>()
        for (name in yuanNear.map { it.label }) {
            if (name.length != 1 || name == "ㄛ") continue
            if (ZhuyinPhonology.classify(name[0]) != ZhuyinClass.FINAL) continue
            val body = "ㄓㄨㄥㄏㄨㄚㄇㄧㄣㄍㄨ$name"
            val chain = BopomofoChain()
            if (!body.all { chain.append(it) } || chain.size != 4) continue
            val syllables = head + "ㄍㄨ$name"
            val exact = lexicon.exact(syllables, open, false)
            if (exact.any { it.extra == 0 }) continue
            val found = engine.select(syllables, "", remembered = null, useModel = false, openTone = open)
            if (found.candidates.isEmpty()) continue
            val key = areas.single { it.label == name }
            val back = TouchModel.withAdjacent(
                TouchModel.nearby(key.centerX, key.centerY, areas),
                name,
                areas,
            )
            if (back.none { it.label == "ㄛ" && it.probability == 0f }) continue
            alt = name
            statistical = found.candidates
            break
        }
        assertTrue(alt, alt.isNotEmpty())
        val host = RecordingHost("")
        val typing = session(host)
        type(typing, "ㄓㄨㄥㄏㄨㄚㄇㄧㄣㄍㄨ")
        val key = areas.single { it.label == alt }
        val nearby = TouchModel.withAdjacent(
            TouchModel.nearby(key.centerX, key.centerY, areas),
            alt,
            areas,
        )
        typing.onKey(KeyAction.Bopomofo(alt, ' ', nearby = nearby))
        val row = host.snapshots.last().candidates
        assertEquals("ㄓㄨㄥㄏㄨㄚㄇㄧㄣㄍㄨ$alt", host.snapshots.last().preedit)
        val cover = row.first { it.coversOpenSyllables(4) }
        assertTrue(cover.text, statistical.contains(cover.text))
        assertNotEquals("中華民國", cover.text)
        typing.onEnter()
        assertEquals(listOf(cover.text), host.commits)
    }

    @Test
    fun anUnfinishedInitialStillListsTheWord() {
        val host = RecordingHost("請問")
        val session = session(host)
        type(session, "ㄋㄧˇ")
        assertEquals("們", session.previewHint)
        type(session, "ㄏ")
        val row = host.snapshots.last().candidates
        assertEquals("ㄋㄧˇㄏ", host.snapshots.last().preedit)
        assertTrue(row.any { it.text == "你好" && it.span == 2 && !it.literal })
        assertTrue(row.any { it.text == "你" && it.span == 1 && !it.literal })
        assertEquals("ㄋㄧˇㄏ", row.single { it.literal }.text)
    }

    @Test
    fun aCentreTouchOnHeDoesNotChangeTheTypedNiYiHeRow() {
        val nearby = centreTouch("ㄏ")
        assertTrue(nearby.any { it.label == "ㄌ" && it.probability == 0f })
        assertTrue(nearby.any { it.label == "ㄒ" && it.probability == 0f })
        val typedHost = RecordingHost("")
        val typed = session(typedHost)
        type(typed, "ㄋㄧㄏ")
        val typedSnapshot = typedHost.snapshots.last()
        assertEquals("ㄋㄧㄏ", typedSnapshot.preedit)
        assertEquals("ㄋㄧㄏ", typedSnapshot.candidates.single { it.literal }.text)
        val touchHost = RecordingHost("")
        val touching = session(touchHost)
        type(touching, "ㄋㄧ")
        touching.onKey(KeyAction.Bopomofo("ㄏ", 'c', nearby = nearby))
        val touchSnapshot = touchHost.snapshots.last()
        assertEquals("ㄋㄧㄏ", touchSnapshot.preedit)
        assertEquals("ㄋㄧㄏ", touchSnapshot.candidates.single { it.literal }.text)
        val typedRows = typedSnapshot.candidates.filter { !it.literal }.map { it.text to it.span }
        val touchRows = touchSnapshot.candidates.filter { !it.literal }.map { it.text to it.span }
        assertEquals(typedRows, touchRows)
        for (row in listOf(typedSnapshot.candidates, touchSnapshot.candidates)) {
            assertTrue(row.map { it.text }.toString(), row.none { it.text.contains("年來") })
            assertTrue(row.map { it.text }.toString(), row.none { it.text.contains("你想") })
            assertTrue(row.map { it.text }.toString(), row.any { !it.literal && it.text == "你好" })
        }
    }

    @Test
    fun aNeighbourOnADoesNotChangeTheTypedBiaRow() {
        val nearby = centreTouch("ㄚ")
        assertTrue(nearby.any { it.label == "ㄠ" })
        val typedHost = RecordingHost("")
        val typed = session(typedHost)
        type(typed, "ㄅㄧㄚ")
        val typedSnapshot = typedHost.snapshots.last()
        val touchHost = RecordingHost("")
        val touching = session(touchHost)
        type(touching, "ㄅㄧ")
        touching.onKey(KeyAction.Bopomofo("ㄚ", '8', nearby = nearby))
        val touchSnapshot = touchHost.snapshots.last()
        assertEquals("ㄅㄧㄚ", typedSnapshot.preedit)
        assertEquals("ㄅㄧㄚ", touchSnapshot.preedit)
        assertEquals("ㄅㄧㄚ", typedSnapshot.candidates.single { it.literal }.text)
        assertEquals("ㄅㄧㄚ", touchSnapshot.candidates.single { it.literal }.text)
        val typedRows = typedSnapshot.candidates.filter { !it.literal }.map { it.text to it.span }
        val touchRows = touchSnapshot.candidates.filter { !it.literal }.map { it.text to it.span }
        assertEquals(typedRows, touchRows)
    }

    @Test
    fun aNeighbourOnAngDoesNotTurnNiYiHangIntoHello() {
        val nearby = centreTouch("ㄤ")
        assertTrue(nearby.any { it.label == "ㄠ" })
        val typedHost = RecordingHost("")
        val typed = session(typedHost)
        type(typed, "ㄋㄧㄏㄤ")
        val typedSnapshot = typedHost.snapshots.last()
        val touchHost = RecordingHost("")
        val touching = session(touchHost)
        type(touching, "ㄋㄧㄏ")
        touching.onKey(KeyAction.Bopomofo("ㄤ", ';', nearby = nearby))
        val touchSnapshot = touchHost.snapshots.last()
        assertEquals("ㄋㄧㄏㄤ", typedSnapshot.preedit)
        assertEquals("ㄋㄧㄏㄤ", touchSnapshot.preedit)
        assertEquals("ㄋㄧㄏㄤ", typedSnapshot.candidates.single { it.literal }.text)
        assertEquals("ㄋㄧㄏㄤ", touchSnapshot.candidates.single { it.literal }.text)
        val typedRows = typedSnapshot.candidates.filter { !it.literal }.map { it.text to it.span }
        val touchRows = touchSnapshot.candidates.filter { !it.literal }.map { it.text to it.span }
        assertEquals(typedRows, touchRows)
        assertTrue(touchSnapshot.candidates.none { it.text == "你好" })
    }

    @Test
    fun aNeighbourOnZiDoesNotTurnNiZiIntoNi() {
        val nearby = centreTouch("ㄗ")
        assertTrue(nearby.any { it.label == "ㄧ" })
        val typedHost = RecordingHost("")
        val typed = session(typedHost)
        type(typed, "ㄋㄗ")
        val typedSnapshot = typedHost.snapshots.last()
        val touchHost = RecordingHost("")
        val touching = session(touchHost)
        type(touching, "ㄋ")
        touching.onKey(KeyAction.Bopomofo("ㄗ", 'y', nearby = nearby))
        val touchSnapshot = touchHost.snapshots.last()
        assertEquals("ㄋㄗ", typedSnapshot.preedit)
        assertEquals("ㄋㄗ", touchSnapshot.preedit)
        assertEquals("ㄋㄗ", typedSnapshot.candidates.single { it.literal }.text)
        assertEquals("ㄋㄗ", touchSnapshot.candidates.single { it.literal }.text)
        val typedRows = typedSnapshot.candidates.filter { !it.literal }.map { it.text to it.span }
        val touchRows = touchSnapshot.candidates.filter { !it.literal }.map { it.text to it.span }
        assertEquals(typedRows, touchRows)
        assertTrue(touchSnapshot.candidates.none { !it.literal && it.text == "你" })
    }

    @Test
    fun aNeighbourOnKeDoesNotTurnNiKeIntoHello() {
        val nearby = centreTouch("ㄎ")
        assertTrue(nearby.any { it.label == "ㄏ" })
        val typedHost = RecordingHost("")
        val typed = session(typedHost)
        type(typed, "ㄋㄎ")
        val typedSnapshot = typedHost.snapshots.last()
        val touchHost = RecordingHost("")
        val touching = session(touchHost)
        type(touching, "ㄋ")
        touching.onKey(KeyAction.Bopomofo("ㄎ", 'd', nearby = nearby))
        val touchSnapshot = touchHost.snapshots.last()
        assertEquals("ㄋㄎ", typedSnapshot.preedit)
        assertEquals("ㄋㄎ", touchSnapshot.preedit)
        assertEquals("ㄋㄎ", typedSnapshot.candidates.single { it.literal }.text)
        assertEquals("ㄋㄎ", touchSnapshot.candidates.single { it.literal }.text)
        val typedRows = typedSnapshot.candidates.filter { !it.literal }.map { it.text to it.span }
        val touchRows = touchSnapshot.candidates.filter { !it.literal }.map { it.text to it.span }
        assertEquals(typedRows, touchRows)
        assertTrue(touchSnapshot.candidates.none { it.text == "你好" })
    }

    private fun centreTouch(label: String) = run {
        val areas = portraitZhuyinAreas()
        val key = areas.single { it.label == label }
        TouchModel.withAdjacent(TouchModel.nearby(key.centerX, key.centerY, areas), label, areas)
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
