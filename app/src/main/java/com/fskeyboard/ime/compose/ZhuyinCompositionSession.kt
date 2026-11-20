package com.fskeyboard.ime.compose

import com.fskeyboard.ime.candidate.BopomofoChain
import com.fskeyboard.ime.candidate.Candidate
import com.fskeyboard.ime.candidate.coversOpenSyllables
import com.fskeyboard.ime.lingxi.LINGXI_CANDIDATE
import com.fskeyboard.ime.lingxi.LINGXI_EXTRA_PENALTY
import com.fskeyboard.ime.lingxi.LINGXI_KEY_BUDGET_NS
import com.fskeyboard.ime.lingxi.LingxiSlot
import com.fskeyboard.ime.lingxi.withoutOpenReading
import kotlin.math.ln
import com.fskeyboard.ime.candidate.CandidateSource
import com.fskeyboard.ime.candidate.SyllableReading
import com.fskeyboard.ime.candidate.ZhuyinComposition
import com.fskeyboard.ime.engine.ChiaKeyFrame
import com.fskeyboard.ime.engine.EngineSnapshot
import com.fskeyboard.ime.engine.ZhuyinEngine
import com.fskeyboard.ime.keyboard.KeyAction
import com.fskeyboard.ime.keyboard.KeyboardMode
import com.fskeyboard.ime.keyboard.ZhuyinClass
import com.fskeyboard.ime.keyboard.ZhuyinPhonology
import com.fskeyboard.ime.keyboard.ZhuyinTone
import com.fskeyboard.ime.latency.KeyStages
import com.fskeyboard.ime.lexicon.Lexicon

private const val ZHUYIN_CHAR_LIMIT = 48
private const val ZHUYIN_PREFIX_LIMIT = 8
private const val ZHUYIN_SUPPLEMENT_LIMIT = 8

/** One-key substitutions scored when the typed reading is not a complete lexicon word. */
private const val CORRECTION_TRIES = 12

/**
 * Closest substituted readings decoded with the lexicon model.
 * Prefix rows often share one score, so the first row is not the word.
 */
private const val SLIP_DECODE = 6

/** One composition. A key that is not bopomofo returns false and the controller continues. */
internal interface Composer {
    fun onKey(action: KeyAction): Boolean
}

/** What the zhuyin session still asks the controller to do. Field writes go through [com.fskeyboard.ime.session.EditorSession]. */
internal interface ZhuyinHost {
    fun mode(): KeyboardMode
    fun commit(text: String)
    fun apply(snapshot: EngineSnapshot)
    fun edit(block: () -> Unit)
    fun promote(candidate: Candidate)
    fun rememberPhrase(candidate: Candidate)
    fun savePreference()
    fun createdMatching(reading: String): List<Candidate>
    fun refreshIdleSuggestions()

    /** Committed text before the caret, for the select model. Empty in tests that do not type into a field. */
    fun committedContext(): String = ""

    /** Device log for the key-to-row clock. Tests leave this empty. */
    fun lingxiLine(message: String) {}
}

/**
 * Bopomofo composition for one field.
 * [com.fskeyboard.ime.keyboard.ZhuyinComposer] is the syllable buffer, not this type.
 * English composition stays on the controller.
 */
internal class ZhuyinCompositionSession(
    private val engine: ZhuyinEngine,
    private val lexicon: Lexicon,
    private val host: ZhuyinHost,
    private val lingxi: LingxiSlot? = null,
) : Composer {
    private val composition = ZhuyinComposition()
    private var frame = ChiaKeyFrame.parse("")

    /**
     * Syllables typed without a tone between them.
     * While [holdingSentence] is set, this chain is the whole reading and the engine
     * no longer owns those keys. One open syllable still goes to the engine, so the
     * next initial continues in that walker.
     * A finished one-syllable tone is copied into [pendingTone] before its chain is
     * released. The next syllable keeps that tone on [enginePrefix] until the whole
     * composition is held. [mirrored] is the open chain's engine keys.
     * [enginePrefixKeys] counts keys already in the engine for [enginePrefix].
     * [chosenPrefix] is Hanzi already tapped while bopomofo is still open. It stays
     * in front of [chain]. A tap that leaves only Hanzi writes them at once.
     * [chosenSpans] records the bopomofo that produced each of those Hanzi, latest last,
     * so backspace can put that reading back in front of whatever is still open.
     */
    private val chain = BopomofoChain()
    private var holdingSentence = false
    private var mirrored = 0
    private val enginePrefix = ArrayList<SyllableReading>()
    private var enginePrefixKeys = 0
    private var pendingTone: List<SyllableReading> = emptyList()
    private var pendingToneText = ""
    private val chosenPrefix = StringBuilder()
    private val chosenSpans = ArrayList<ChosenSpan>()
    private val remembered = HashMap<String, String>()

    /** One touch per glyph in [chain], including a tone mark. A 平聲 mark has none. */
    private val touches = ArrayList<KeyTouch>()
    private var keyStartedNs = 0L
    private var servedByLingxi = false
    private var lastReorder = "skip"

    var previewComposing: String = ""
        private set
    var previewReading: String = ""
        private set

    /** Untyped tail of a lexicon word. Drawn on the bubble only. */
    var previewHint: String = ""
        private set

    /** Syllables still open. The candidate row uses this to accent the chip Enter commits. */
    var openSyllables: Int = 0
        private set

    private var offeredHint = ""
    private var shownCandidates: List<Candidate> = emptyList()

    val hasComposition: Boolean
        get() = frame.active || holdingSentence || !chain.isEmpty || pendingTone.isNotEmpty() ||
            chosenPrefix.isNotEmpty()

    fun pendingText(): String = composition.pending.text

    fun pendingCaret(): Int = composition.pending.caret

    fun chosenTextLength(): Int = composition.chosenText.length

    fun compositionLength(): Int = composition.text.length

    fun movePendingCaret(next: Int) {
        composition.pending.moveCaret(next)
    }

    fun resetEngine() {
        engine.reset()
    }

    fun clearFrame() {
        frame = ChiaKeyFrame.parse("")
    }

    /** Drops the chain and the unused syllable buffer. The engine frame stays. */
    fun dropChain() {
        composition.clear()
        clearChain()
    }

    override fun onKey(action: KeyAction): Boolean {
        if (action !is KeyAction.Bopomofo) return false
        if (host.mode() != KeyboardMode.ZHUYIN) return false
        if (!engine.ready) return true
        keyStartedNs = System.nanoTime()
        val glyph = action.label.firstOrNull() ?: return true
        // The preview keeps the typed bopomofo and tone marks until a candidate is tapped.
        // The router gap stops here so compose is only the append.
        KeyStages.finishRouterGap()
        val composeStart = System.nanoTime()
        val held = chain.append(glyph)
        if (held) {
            holdingSentence = true
            noteTouch(action)
            mergeChainSyllables()
        }
        KeyStages.noteCompose(System.nanoTime() - composeStart)
        if (!held) {
            keyStartedNs = 0L
            return true
        }
        showHeld()
        return true
    }

    /**
     * Space while a composition is open. False when this keyboard is not holding zhuyin,
     * so the controller can commit an ASCII space.
     */
    fun onSpace(): Boolean {
        if (host.mode() != KeyboardMode.ZHUYIN || !hasComposition) return false
        if (chosenPrefix.isNotEmpty() && !openZhuyinSyllable()) {
            commitChosenPrefix(trailing = " ")
            host.refreshIdleSuggestions()
            return true
        }
        if (!holdingSentence && !chain.isEmpty && !chain.lastToneChosen()) {
            holdComposition(mirrored)
        }
        if (holdingSentence) {
            chain.markLevel()
            showHeld()
        }
        return true
    }

    /** True when the key was consumed and the field text must stay. */
    fun onBackspace(): Boolean {
        if (host.mode() != KeyboardMode.ZHUYIN) return false
        if (chosenSpans.isNotEmpty() && !chain.isEmpty) {
            restoreLatestChosen()
            return true
        }
        if (holdingSentence) {
            backspaceChain()
            if (chain.isEmpty) {
                holdingSentence = false
                if (chosenPrefix.isNotEmpty()) commitChosenPrefix() else showFrame()
            } else {
                showHeld()
            }
            return true
        }
        if (frame.active) {
            frame = engine.handleKey(8)
            clearPendingTone()
            if (chain.isEmpty) {
                enginePrefix.clear()
                enginePrefixKeys = 0
                touches.clear()
            } else {
                backspaceChain()
            }
            mirrored = chain.text.length
            showFrame()
            return true
        }
        if (pendingTone.isNotEmpty()) {
            clearPendingTone()
            mirrored = 0
            showFrame()
            return true
        }
        if (chosenPrefix.isNotEmpty() && chain.isEmpty && !frame.active) {
            chosenPrefix.deleteCharAt(chosenPrefix.lastIndex)
            if (chosenPrefix.isEmpty()) {
                clearChain()
                frame = ChiaKeyFrame.parse("")
                host.apply(EngineSnapshot())
            } else {
                host.edit { presentComposition("", emptyList()) }
            }
            return true
        }
        if (hasComposition) return true
        return false
    }

    /**
     * True when Enter committed the open composition instead of the editor action.
     * With 靈犀, open bopomofo becomes the first Hanzi that covers every typed syllable.
     * The partial lexicon keeps the typed letters. The prediction stays on its own chip.
     */
    fun onEnter(): Boolean {
        if (host.mode() != KeyboardMode.ZHUYIN || !hasComposition) return false
        commitComposition()
        return true
    }

    /**
     * A partial phrase or an engine row. Clipboard and the lexicon chip are not handled here.
     * False when this candidate belongs to English or an idle suggestion.
     */
    fun acceptCandidate(candidate: Candidate): Boolean {
        if (candidate.source == CandidateSource.SYNTHETIC &&
            (holdingSentence || !chain.isEmpty || pendingTone.isNotEmpty())
        ) {
            acceptPartial(candidate)
            return true
        }
        if (frame.active && candidate.engineIndex >= 0) {
            frame = engine.select(candidate.engineIndex)
            // The chain still holds the syllable select just consumed.
            enginePrefix.clear()
            enginePrefixKeys = 0
            chain.clear()
            touches.clear()
            mirrored = 0
            holdingSentence = false
            clearPendingTone()
            showFrame()
            return true
        }
        return false
    }

    /** Commits the segment on screen. Punctuation and leaving zhuyin use the same conversion as Enter. */
    fun finish() {
        commitComposition()
    }

    /**
     * Writes the open composition.
     * 靈犀 replaces the bopomofo with a covering Hanzi. The partial lexicon keeps the letters.
     * [acceptPartial] edits on its own, so this does not wrap that call in [ZhuyinHost.edit].
     */
    private fun commitComposition() {
        val reading = if (!chain.isEmpty) chain.text else pendingToneText
        if (reading.isNotEmpty() && lingxi?.engine != null) {
            val target = shownCandidates.firstOrNull { it.coversOpenSyllables(openSyllableCount()) }
            if (target != null) {
                acceptPartial(target)
                return
            }
        }
        val text = chosenPrefix.toString() + reading
        host.edit {
            clearChain()
            frame = ChiaKeyFrame.parse("")
            if (text.isNotEmpty()) host.commit(text)
            engine.reset()
            host.apply(EngineSnapshot())
        }
    }

    private fun openSyllableCount(): Int {
        return when {
            !chain.isEmpty -> chain.size
            pendingTone.isNotEmpty() -> pendingTone.size
            else -> 0
        }
    }

    fun reload() {
        if (holdingSentence || !chain.isEmpty) showHeld() else showFrame()
    }

    fun afterPartialLexicon() {
        when {
            holdingSentence -> showHeld()
            !chain.isEmpty && !chain.lastToneChosen() -> showFrame()
            pendingTone.isNotEmpty() -> showFrame()
        }
    }

    private fun showFrame() {
        if (frame.committed.isNotEmpty()) {
            val text = frame.committed
            frame = ChiaKeyFrame.parse("")
            clearChain()
            host.commit(text)
            engine.acknowledge()
            host.apply(EngineSnapshot())
            return
        }
        val typedReading = when {
            !chain.isEmpty -> chain.text
            pendingToneText.isNotEmpty() -> pendingToneText
            else -> ""
        }
        if (typedReading.isNotEmpty() || chosenPrefix.isNotEmpty()) {
            if (frame.composing.isNotEmpty()) frame = frame.copy(composing = "")
            val readings = if (!chain.isEmpty) chain.readings() else pendingTone
            val offered = if (readings.isEmpty()) emptyList() else offer(readings)
            presentComposition(typedReading, offered)
            return
        }
        val engineCandidates = frame.candidates.mapIndexed { index, text ->
            Candidate(text = text, engineIndex = index, source = CandidateSource.RIME)
        }
        val toneOffer = engineCandidates.isEmpty() && pendingTone.isNotEmpty()
        val candidates = when {
            engineCandidates.isNotEmpty() -> engineCandidates
            toneOffer -> offer(pendingTone)
            !chain.isEmpty && !chain.lastToneChosen() -> offer(chain.readings())
            else -> emptyList()
        }
        if (chosenPrefix.isNotEmpty()) {
            if (frame.composing.isNotEmpty()) frame = frame.copy(composing = "")
            val reading = when {
                !chain.isEmpty -> chain.text
                toneOffer -> pendingToneText
                else -> ""
            }
            presentComposition(reading, if (reading.isEmpty()) emptyList() else candidates)
            return
        }
        val inline = if (toneOffer && frame.composing.isEmpty()) pendingToneText else frame.display
        val bubbleReading = when {
            !chain.isEmpty -> chain.text
            toneOffer && frame.composing.isEmpty() -> pendingToneText
            else -> frame.reading
        }
        previewComposing = frame.composing
        previewReading = bubbleReading
        previewHint = ""
        noteShown(candidates)
        host.apply(
            EngineSnapshot(
                preedit = inline,
                composingPreview = inline,
                candidates = candidates,
            ),
        )
    }

    /**
     * Joins chain syllables that only split because the phonology cannot extend one glyph
     * into the next — ㄚ is a FINAL and ㄧ a MEDIAL, so ㄚㄧㄢ arrives as ㄚ + ㄧㄢ even
     * though the lexicon knows the single syllable ㄚㄧㄢ. Only a join the Lingxi lexicon
     * holds as one complete syllable (extra == 0) is kept; the last syllable keeps its
     * tone and [touches] stays, one key per touch. An earlier syllable already toned, or
     * no engine, leaves the chain as typed.
     */
    private fun mergeChainSyllables() {
        val engine = lingxi?.engine ?: return
        val readings = chain.readings()
        if (readings.size < 2) return
        if (readings.dropLast(1).any { it.tone != ZhuyinTone.NONE }) return
        val tone = readings.last().tone
        val body = buildString { for (reading in readings) append(reading.body) }
        val hits = try {
            engine.exactHits(listOf(body + tone.mark), booleanArrayOf(!tone.chosen), includeSupplements = false)
        } catch (_: Exception) {
            return
        }
        if (hits.none { it.extra == 0 }) return
        chain.clear()
        chain.insertFront(listOf(SyllableReading(body, tone)))
    }

    /**
     * The held reading, underlined in the editor and shown in the bubble.
     * Engine composing text is a prediction, not the bopomofo still open.
     * Chosen Hanzi stays in front of that reading.
     */
    private fun showHeld() {
        if (frame.composing.isNotEmpty()) frame = frame.copy(composing = "")
        val readings = chain.readings()
        val lookupStart = System.nanoTime()
        val found = offer(readings)
        KeyStages.noteLookup(System.nanoTime() - lookupStart)
        presentComposition(chain.text, found)
    }

    /**
     * Chosen Hanzi, then the bopomofo still open. [frame.composing] is not part of it.
     */
    private fun presentComposition(reading: String, candidates: List<Candidate>) {
        val hanzi = chosenPrefix.toString()
        val shown = hanzi + reading
        previewComposing = hanzi
        previewReading = reading
        previewHint = if (reading.isEmpty()) "" else offeredHint
        val gapStart = System.nanoTime()
        val rows = withCreated(reading, withNextWordChips(candidates))
        KeyStages.addGap(System.nanoTime() - gapStart)
        noteShown(rows)
        host.apply(
            EngineSnapshot(
                preedit = shown,
                composingPreview = shown,
                candidates = rows,
                readingSyllables = chain.size,
                rankLocked = servedByLingxi,
            ),
        )
        finishKeyClock()
        servedByLingxi = false
    }

    /**
     * Next-word chips while bopomofo is still open. The committed context is read with the
     * open reading stripped, so a trailing non-Han character does not empty [Lexicon.following].
     * Each chip is a gray completion (completion = true, so Enter skips it) whose span covers
     * every open syllable, so a tap commits the whole word and drops the unfinished reading.
     * When the typed syllables are a prefix of one of a next word's real readings, only the
     * matching words stay; when nothing matches, or no reading is known, the full list stays.
     */
    private fun withNextWordChips(candidates: List<Candidate>): List<Candidate> {
        if (chain.isEmpty) return candidates
        val syllables = chain.readings().map { it.body + it.tone.mark }
        val context = withoutOpenReading(host.committedContext(), syllables)
        val next = lexicon.following(context)
        if (next.isEmpty()) return candidates
        val partial = chain.text
        val engine = lingxi?.engine
        val matched = if (engine != null) {
            next.filter { surface -> engine.readingsOf(surface).any { it.startsWith(partial) } }
        } else {
            emptyList()
        }
        val kept = if (matched.isEmpty()) next else matched
        val have = HashSet<String>()
        for (candidate in candidates) if (candidate.completion) have += candidate.text
        val chips = ArrayList<Candidate>(kept.size)
        for (surface in kept) {
            if (!have.add(surface)) continue
            chips += Candidate(
                text = surface,
                engineIndex = LINGXI_CANDIDATE,
                source = CandidateSource.SYNTHETIC,
                span = chain.size,
                completion = true,
            )
        }
        if (chips.isEmpty()) return candidates
        return candidates + chips
    }

    /**
     * 靈犀 rows when the pack is resident. Otherwise the partial lexicon.
     * An unfinished next syllable keeps the previous row, so the bar does not blank mid-word.
     */
    private fun offer(readings: List<SyllableReading>): List<Candidate> {
        val previousHint = offeredHint
        val previous = shownCandidates
        val found = lookup(readings)
        if (found.isEmpty() && readings.size > 1 && previous.any { !it.literal }) {
            offeredHint = previousHint
            return withLiteral(readings, previous.filter { !it.literal })
        }
        if (found.isEmpty()) return withLiteral(readings, found)
        return found
    }

    private fun lookup(readings: List<SyllableReading>): List<Candidate> {
        offeredHint = ""
        val engine = lingxi?.engine
        if (engine == null || readings.isEmpty()) {
            return lexicon.partialCandidates(readings)
        }
        val syllables = readings.map { it.body + it.tone.mark }
        val openTone = BooleanArray(readings.size) { !readings[it].tone.chosen }
        val readingKey = buildString { for (syllable in syllables) append(syllable) }
        val started = keyStartedNs
        val deadline = if (started == 0L) System.nanoTime() + LINGXI_KEY_BUDGET_NS else started + LINGXI_KEY_BUDGET_NS
        // A prefix such as ㄋ or ㄋㄏ is a lexicon row. A stitched sentence is not,
        // so a neighbour must not discard that select.
        val slip = correctedReading(readings, syllables, openTone, remembered[readingKey], deadline)
        val typed = try {
            engine.select(
                syllables,
                host.committedContext(),
                remembered[readingKey],
                deadline,
                openTone = openTone,
            )
        } catch (_: Exception) {
            com.fskeyboard.ime.lingxi.LingxiResult(emptyList(), "miss")
        }
        val result = when {
            slip == null -> typed
            typed.candidates.isEmpty() -> slip.result
            slip.leads -> mergeSlip(slip.result, typed, typed)
            else -> mergeSlip(typed, slip.result, typed)
        }
        val chosen = result
        servedByLingxi = true
        lastReorder = chosen.reorder
        if (chosen.candidates.isEmpty()) return emptyList()
        // A row the typed reading only partially spells (extra > 0) does not cover every
        // open syllable, so Enter must not commit it as the covering Hanzi. A complete
        // syllable (extra == 0) keeps span = readings.size.
        val extraBySurface = HashMap<String, Int>()
        try {
            for (hit in engine.exactHits(syllables, openTone, includeSupplements = true)) {
                val known = extraBySurface[hit.surface]
                if (known == null || hit.extra > known) extraBySurface[hit.surface] = hit.extra
            }
        } catch (_: Exception) {
        }
        val rows = chosen.candidates.map { text ->
            val extra = extraBySurface[text] ?: 0
            Candidate(
                text = text,
                engineIndex = LINGXI_CANDIDATE,
                source = CandidateSource.SYNTHETIC,
                span = if (extra > 0) 0 else readings.size,
                reading = readingKey,
            )
        }
        val phrases = chosen.completion?.let { hint ->
            offeredHint = hint.tail
            listOf(
                Candidate(
                    text = hint.surface,
                    engineIndex = LINGXI_CANDIDATE,
                    source = CandidateSource.SYNTHETIC,
                    span = readings.size,
                    reading = hint.reading,
                    completion = true,
                    labelReading = readingKey,
                    labelTail = hint.tail,
                ),
            ) + rows
        } ?: rows
        return withChoices(readings, syllables, openTone, withCreated(readingKey, phrases))
    }

    private fun withChoices(
        readings: List<SyllableReading>,
        syllables: List<String>,
        openTone: BooleanArray,
        phrases: List<Candidate>,
    ): List<Candidate> {
        val rows = ArrayList<Candidate>(phrases.size + 24)
        rows += phrases
        appendSupplements(rows, syllables, openTone)
        val literal = literalCandidate(readings)
        if (literal != null) rows += literal
        val engine = lingxi?.engine
        if (engine == null || readings.size < 2) return rows
        val seen = HashSet<String>(rows.size)
        for (row in rows) seen += "${row.span}\t${row.text}"
        for (length in readings.size - 1 downTo 1) {
            val hits = try {
                engine.exactHits(
                    syllables.subList(0, length),
                    openTone.copyOfRange(0, length),
                    includeSupplements = false,
                )
            } catch (_: Exception) {
                emptyList()
            }
            val reading = buildString {
                for (index in 0 until length) append(syllables[index])
            }
            val limit = if (length == 1) ZHUYIN_CHAR_LIMIT else ZHUYIN_PREFIX_LIMIT
            var kept = 0
            for (hit in hits.sortedByDescending { it.prw }) {
                if (hit.surface.isEmpty()) continue
                if (length == 1 && hit.surface.codePointCount(0, hit.surface.length) != 1) continue
                if (!seen.add("$length\t${hit.surface}")) continue
                rows += Candidate(
                    text = hit.surface,
                    engineIndex = LINGXI_CANDIDATE,
                    source = CandidateSource.SYNTHETIC,
                    span = length,
                    reading = reading,
                )
                kept += 1
                if (kept == limit) break
            }
        }
        return rows
    }

    private fun appendSupplements(
        rows: ArrayList<Candidate>,
        syllables: List<String>,
        openTone: BooleanArray,
    ) {
        val engine = lingxi?.engine ?: return
        val seen = ArrayList<String>()
        for (row in rows) {
            if (!row.completion) seen += row.text
        }
        val ranked = try {
            engine.topSupplements(syllables, openTone, seen, ZHUYIN_SUPPLEMENT_LIMIT)
        } catch (_: Exception) {
            return
        }
        val reading = buildString(syllables.sumOf { it.length }) {
            for (syllable in syllables) append(syllable)
        }
        for (hit in ranked) {
            rows += Candidate(
                text = hit.surface,
                engineIndex = LINGXI_CANDIDATE,
                source = CandidateSource.SYNTHETIC,
                span = syllables.size,
                reading = reading,
            )
        }
    }

    private fun withLiteral(readings: List<SyllableReading>, rows: List<Candidate>): List<Candidate> {
        if (rows.any { it.literal }) return rows
        val literal = literalCandidate(readings) ?: return rows
        return rows + literal
    }

    private fun literalCandidate(readings: List<SyllableReading>): Candidate? {
        if (readings.isEmpty()) return null
        val text = buildString {
            for (syllable in readings) {
                append(syllable.body)
                append(syllable.tone.mark)
            }
        }
        if (text.isEmpty()) return null
        return Candidate(
            text = text,
            engineIndex = LINGXI_CANDIDATE,
            source = CandidateSource.SYNTHETIC,
            span = readings.size,
            reading = text,
            literal = true,
        )
    }

    /** The row on screen, and how many syllables of it Enter must cover. */
    private fun noteShown(rows: List<Candidate>) {
        shownCandidates = rows
        openSyllables = openSyllableCount()
    }

    private fun finishKeyClock() {
        val started = keyStartedNs
        if (started == 0L || !servedByLingxi) return
        keyStartedNs = 0L
        servedByLingxi = false
        val ms = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(0L)
        val reorder = when (lastReorder) {
            "ok", "miss" -> lastReorder
            else -> "skip"
        }
        try {
            host.lingxiLine("lingxi key-to-row ms=$ms reorder=$reorder")
        } catch (_: RuntimeException) {
            // The unit-test Android jar throws from Log. A log failure must not drop the key.
        }
    }

    /** A phrase the user assembled sits after a prediction chip and ahead of the homophones. */
    private fun withCreated(reading: String, candidates: List<Candidate>): List<Candidate> {
        val extra = host.createdMatching(reading)
        if (extra.isEmpty()) return candidates
        val known = HashSet<String>(candidates.size)
        for (candidate in candidates) known += candidate.reading + "\t" + candidate.text
        val added = ArrayList<Candidate>()
        for (candidate in extra) {
            val id = candidate.reading + "\t" + candidate.text
            if (!known.add(id)) continue
            added += candidate.copy(span = syllableCount(candidate.reading))
        }
        if (added.isEmpty()) return candidates
        val completion = ArrayList<Candidate>()
        val rest = ArrayList<Candidate>()
        for (candidate in candidates) {
            if (candidate.completion) completion += candidate else rest += candidate
        }
        return completion + added + rest
    }

    private fun syllableCount(reading: String): Int {
        val parsed = BopomofoChain()
        for (ch in reading) parsed.append(ch)
        return parsed.size.coerceAtLeast(1)
    }

    /** Several picks joined into one phrase. One lexicon row is not a created word. */
    private fun rememberAssembledPhrase() {
        if (chosenSpans.size < 2) return
        val text = chosenPrefix.toString()
        if (text.isEmpty()) return
        val reading = buildString {
            for (span in chosenSpans) {
                for (syllable in span.reading) {
                    append(syllable.body)
                    append(syllable.tone.mark)
                }
            }
        }
        if (reading.isEmpty()) return
        host.rememberPhrase(
            Candidate(
                text = text,
                engineIndex = -1,
                source = CandidateSource.SYNTHETIC,
                span = chosenSpans.sumOf { it.reading.size }.coerceAtLeast(1),
                reading = reading,
            ),
        )
        host.savePreference()
    }

    /** A syllable is still open, so space marks 平聲 instead of committing. */
    private fun openZhuyinSyllable(): Boolean {
        return holdingSentence || !chain.isEmpty || pendingTone.isNotEmpty() || frame.active
    }

    /**
     * Appends [candidate] to the chosen Hanzi and drops the syllables it covers.
     * Bopomofo still open stays in this composition, ahead of the field.
     * When the preview is only Hanzi, those characters are written immediately.
     */
    private fun acceptPartial(candidate: Candidate) {
        val span = candidate.span.coerceAtLeast(1)
        val covered = when {
            !chain.isEmpty -> chain.readings().take(span.coerceAtMost(chain.size))
            else -> pendingTone.take(span.coerceAtMost(pendingTone.size))
        }
        if (!holdingSentence) {
            // enginePrefixKeys are already in the engine and are not part of mirrored.
            // Leaving them makes the next frame show that spelling again.
            repeat(mirrored + enginePrefixKeys) { frame = engine.handleKey(8) }
            mirrored = 0
            enginePrefix.clear()
            enginePrefixKeys = 0
            clearPendingTone()
            if (frame.reading.isEmpty()) frame = ChiaKeyFrame.parse("")
        }
        val fromChain = !chain.isEmpty
        chain.dropFront(span.coerceAtMost(chain.size))
        if (fromChain) dropTouches(covered)
        if (chain.isEmpty) {
            holdingSentence = false
            if (frame.reading.isEmpty()) frame = ChiaKeyFrame.parse("")
        }
        if (candidate.engineIndex == LINGXI_CANDIDATE && candidate.reading.isNotEmpty()) {
            remembered[candidate.reading] = candidate.text
        }
        if (covered.isNotEmpty()) chosenSpans.add(ChosenSpan(candidate.text, covered))
        chosenPrefix.append(candidate.text)
        if (candidate.source != CandidateSource.CLIPBOARD) {
            host.promote(candidate)
            host.savePreference()
        }
        if (chain.isEmpty && pendingTone.isEmpty() && frame.reading.isEmpty()) {
            commitChosenPrefix()
            return
        }
        // Replacing the underline can report an empty composing span. Ignore that
        // report so the syllables still open are not dropped.
        host.edit {
            if (!chain.isEmpty) showHeld() else presentComposition("", emptyList())
        }
    }

    /** Writes the chosen Hanzi. [trailing] is the space after a finished choice. */
    private fun commitChosenPrefix(trailing: String = "") {
        val text = chosenPrefix.toString()
        if (text.isEmpty() && trailing.isEmpty()) return
        rememberAssembledPhrase()
        host.edit {
            clearChain()
            frame = ChiaKeyFrame.parse("")
            engine.reset()
            if (text.isNotEmpty()) host.commit(text)
            if (trailing.isNotEmpty()) host.commit(trailing)
            host.apply(EngineSnapshot())
        }
    }

    /** Puts the latest picked Hanzi back into the bopomofo that produced it. */
    private fun restoreLatestChosen() {
        val span = chosenSpans.removeAt(chosenSpans.lastIndex)
        val prefix = chosenPrefix.toString()
        if (span.text.isNotEmpty() && prefix.endsWith(span.text)) {
            chosenPrefix.setLength(prefix.length - span.text.length)
        } else if (chosenPrefix.isNotEmpty()) {
            chosenPrefix.deleteCharAt(chosenPrefix.lastIndex)
        }
        if (span.reading.isNotEmpty()) {
            chain.insertFront(span.reading)
            insertTouches(span.reading)
        }
        holdingSentence = true
        host.edit { showHeld() }
    }

    private fun clearPendingTone() {
        pendingTone = emptyList()
        pendingToneText = ""
    }

    /**
     * Backspaces every engine key in this composition and leaves the reading on [chain].
     * [tailKeys] are the open chain's keys. [enginePrefixKeys] are the toned syllables
     * already in the engine. [showHeld] is only valid after this, when the chain is whole.
     */
    private fun holdComposition(tailKeys: Int) {
        if (enginePrefix.isNotEmpty()) {
            chain.insertFront(enginePrefix)
            insertTouches(enginePrefix)
        }
        val keys = enginePrefixKeys + tailKeys
        enginePrefix.clear()
        enginePrefixKeys = 0
        repeat(keys) { frame = engine.handleKey(8) }
        mirrored = 0
        holdingSentence = true
    }

    private fun clearChain() {
        chain.clear()
        touches.clear()
        holdingSentence = false
        mirrored = 0
        enginePrefix.clear()
        enginePrefixKeys = 0
        clearPendingTone()
        chosenPrefix.setLength(0)
        chosenSpans.clear()
        previewComposing = ""
        previewReading = ""
        previewHint = ""
        offeredHint = ""
        shownCandidates = emptyList()
        openSyllables = 0
    }

    /**
     * One neighbouring key, starting from the latest touch. A complete typed word stays.
     * Initials may swap with medials, so ㄗ can stand in for ㄧ. Splitting ㄚ into a second ㄧ is not a slip.
     * A prefix such as ㄋㄏ still counts. A centre touch is probability 0 and does not lead it.
     * Only a finished word from a key the touch actually reached can lead. The preedit stays what was typed.
     */
    private fun correctedReading(
        readings: List<SyllableReading>,
        syllables: List<String>,
        openTone: BooleanArray,
        remembered: String?,
        deadline: Long,
    ): Slip? {
        val engine = lingxi?.engine ?: return null
        val glyphs = typedGlyphs(readings) ?: return null
        val typedRows = readingRows(syllables, openTone)
        if (typedRows.any { it.extra == 0 }) return null
        val typedScore = typedRows.maxOfOrNull { lexiconScore(it) } ?: Double.NEGATIVE_INFINITY
        var tried = 0
        val guesses = ArrayList<SlipGuess>(CORRECTION_TRIES)
        for (index in glyphs.indices.reversed()) {
            if (tried >= CORRECTION_TRIES) break
            for (alt in touches[index].others) {
                if (tried >= CORRECTION_TRIES) break
                if (deadline != Long.MAX_VALUE && System.nanoTime() > deadline) break
                if (!slipClassOk(glyphs[index], alt.glyph)) continue
                val next = glyphs.copyOf()
                next[index] = alt.glyph
                val parsed = parseGlyphs(next) ?: continue
                if (sameReading(parsed, readings)) continue
                // Joining ㄋㄗ into ㄋㄧ is the slip. Splitting ㄚ into a second ㄧ is not.
                if (parsed.size > readings.size) continue
                val altSyllables = parsed.map { it.body + it.tone.mark }
                val altOpen = BooleanArray(parsed.size) { !parsed[it].tone.chosen }
                val hits = readingRows(altSyllables, altOpen)
                if (hits.isEmpty()) continue
                tried++
                val bestHit = hits.maxBy { lexiconScore(it) }
                val score = lexiconScore(bestHit)
                // Probability 0 is the geometric list. It must rank behind a key the finger reached.
                val distance = if (alt.probability > 0f) alt.probability.toDouble() else -LINGXI_EXTRA_PENALTY
                guesses += SlipGuess(
                    altSyllables,
                    altOpen,
                    score + distance,
                    score,
                    bestHit.surface,
                    bestHit.extra,
                    alt.probability,
                )
            }
        }
        if (guesses.isEmpty()) return null
        val chosen = guesses.maxBy { it.rank }
        if (chosen.syllables.size > readings.size) return null
        val decoded = ArrayList<Pair<SlipGuess, com.fskeyboard.ime.lingxi.LingxiResult>>(SLIP_DECODE)
        val seenReading = HashSet<List<String>>(SLIP_DECODE)
        for (guess in guesses) {
            if (decoded.size >= SLIP_DECODE) break
            if (guess.syllables.size > readings.size) continue
            if (deadline != Long.MAX_VALUE && System.nanoTime() > deadline) break
            if (!seenReading.add(guess.syllables)) continue
            val selected = decodeSlip(engine, guess, remembered, deadline) ?: continue
            if (selected.candidates.isEmpty()) continue
            decoded += guess to selected
        }
        if (chosen.syllables.size <= readings.size &&
            chosen.syllables !in seenReading &&
            (deadline == Long.MAX_VALUE || System.nanoTime() <= deadline)
        ) {
            val selected = decodeSlip(engine, chosen, remembered, deadline)
            if (selected != null && selected.candidates.isNotEmpty()) decoded += chosen to selected
        }
        if (decoded.isEmpty()) return null
        val winner = decoded.firstOrNull { it.first.syllables == chosen.syllables }?.second
        val seen = HashSet<String>()
        val rows = ArrayList<String>()
        if (winner != null) {
            for (text in winner.candidates) if (seen.add(text)) rows += text
        } else if (seen.add(chosen.surface)) {
            rows += chosen.surface
        }
        for ((guess, selected) in decoded) {
            if (guess.syllables == chosen.syllables) continue
            for (text in selected.candidates) if (seen.add(text)) rows += text
        }
        val shell = winner ?: decoded.first().second
        val leads = chosen.extra == 0 && chosen.probability > 0f && chosen.score > typedScore
        return Slip(shell.copy(candidates = rows), leads)
    }

    private fun decodeSlip(
        engine: com.fskeyboard.ime.lingxi.LingxiEngine,
        guess: SlipGuess,
        remembered: String?,
        deadline: Long,
    ): com.fskeyboard.ime.lingxi.LingxiResult? {
        return try {
            engine.select(
                guess.syllables,
                host.committedContext(),
                remembered,
                deadline,
                useModel = false,
                openTone = guess.openTone,
            )
        } catch (_: Exception) {
            null
        }
    }

    /** A tone only swaps with a tone. ㄗ and ㄧ are different classes and still swap. */
    private fun slipClassOk(typed: Char, alt: Char): Boolean {
        val left = ZhuyinPhonology.classify(typed) ?: return false
        val right = ZhuyinPhonology.classify(alt) ?: return false
        return (left == ZhuyinClass.TONE) == (right == ZhuyinClass.TONE)
    }

    private fun lexiconScore(hit: com.fskeyboard.ime.lingxi.LexHit): Double {
        val prior = if (hit.prw > 0f) hit.prw.toDouble() else 1e-300
        return ln(prior) - hit.extra * LINGXI_EXTRA_PENALTY
    }

    private fun mergeSlip(
        first: com.fskeyboard.ime.lingxi.LingxiResult,
        second: com.fskeyboard.ime.lingxi.LingxiResult,
        typed: com.fskeyboard.ime.lingxi.LingxiResult,
    ): com.fskeyboard.ime.lingxi.LingxiResult {
        val seen = HashSet<String>(first.candidates.size + second.candidates.size)
        val rows = ArrayList<String>(first.candidates.size + second.candidates.size)
        for (text in first.candidates) if (seen.add(text)) rows += text
        for (text in second.candidates) if (seen.add(text)) rows += text
        return com.fskeyboard.ime.lingxi.LingxiResult(rows, typed.reorder, typed.completion)
    }

    /** Lexicon rows for the whole reading. Empty when the syllables are only stitched characters. */
    private fun readingRows(
        syllables: List<String>,
        openTone: BooleanArray,
    ): List<com.fskeyboard.ime.lingxi.LexHit> {
        val engine = lingxi?.engine ?: return emptyList()
        return try {
            engine.exactHits(syllables, openTone, includeSupplements = false)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun noteTouch(action: KeyAction.Bopomofo) {
        val glyph = action.label.firstOrNull() ?: return
        val others = ArrayList<KeyAlt>()
        val seen = HashSet<Char>()
        val ordered = action.nearby.sortedByDescending { it.probability }
        for (near in ordered) {
            val ch = near.label.firstOrNull() ?: continue
            if (ch == glyph || !seen.add(ch)) continue
            others += KeyAlt(ch, near.probability)
        }
        val touch = KeyTouch(glyph, others)
        val tone = ZhuyinPhonology.classify(glyph) == ZhuyinClass.TONE
        val previousTone = touches.lastOrNull()?.let {
            ZhuyinPhonology.classify(it.glyph) == ZhuyinClass.TONE
        } == true
        if (tone && previousTone) touches[touches.lastIndex] = touch else touches += touch
    }

    private fun backspaceChain() {
        val before = chain.text.length
        chain.backspace()
        if (chain.isEmpty) {
            touches.clear()
            return
        }
        if (chain.text.length < before && touches.isNotEmpty()) touches.removeAt(touches.lastIndex)
    }

    private fun dropTouches(readings: List<SyllableReading>) {
        var count = 0
        for (reading in readings) {
            count += reading.body.length
            if (reading.tone.mark.isNotEmpty()) count += 1
        }
        repeat(count.coerceAtMost(touches.size)) { touches.removeAt(0) }
    }

    private fun insertTouches(readings: List<SyllableReading>) {
        val added = ArrayList<KeyTouch>()
        for (reading in readings) {
            for (ch in reading.body) added += KeyTouch(ch, emptyList())
            val mark = reading.tone.mark
            if (mark.isNotEmpty()) added += KeyTouch(mark[0], emptyList())
        }
        if (added.isNotEmpty()) touches.addAll(0, added)
    }

    private fun typedGlyphs(readings: List<SyllableReading>): CharArray? {
        val chars = ArrayList<Char>()
        for (reading in readings) {
            for (ch in reading.body) chars += ch
            val mark = reading.tone.mark
            if (mark.isNotEmpty()) chars += mark[0]
        }
        if (chars.size != touches.size) return null
        for (index in chars.indices) {
            if (chars[index] != touches[index].glyph) return null
        }
        return chars.toCharArray()
    }

    private fun parseGlyphs(chars: CharArray): List<SyllableReading>? {
        val parsed = BopomofoChain()
        for (ch in chars) if (!parsed.append(ch)) return null
        if (parsed.isEmpty) return null
        return parsed.readings()
    }

    private fun sameReading(left: List<SyllableReading>, right: List<SyllableReading>): Boolean {
        if (left.size != right.size) return false
        for (index in left.indices) {
            if (left[index] != right[index]) return false
        }
        return true
    }

    /** Hanzi the user picked, and the syllables they had typed for it. */
    private class ChosenSpan(val text: String, val reading: List<SyllableReading>)

    /** [others] are neighbouring keys, highest touch probability first. Probability 0 is geometry only. */
    private class KeyTouch(val glyph: Char, val others: List<KeyAlt>)

    private class KeyAlt(val glyph: Char, val probability: Float)

    /** One substituted reading. [rank] lowers a probability-0 neighbour. [score] is the lexicon row. */
    private class SlipGuess(
        val syllables: List<String>,
        val openTone: BooleanArray,
        val rank: Double,
        val score: Double,
        val surface: String,
        val extra: Int,
        val probability: Float,
    )

    /** [leads] puts a finished word from a touch that reached that key ahead of the typed rows. */
    private class Slip(
        val result: com.fskeyboard.ime.lingxi.LingxiResult,
        val leads: Boolean,
    )
}
