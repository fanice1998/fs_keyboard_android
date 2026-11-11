package com.fskeyboard.ime.compose

import com.fskeyboard.ime.candidate.BopomofoChain
import com.fskeyboard.ime.candidate.Candidate
import com.fskeyboard.ime.candidate.coversOpenSyllables
import com.fskeyboard.ime.lingxi.LINGXI_CANDIDATE
import com.fskeyboard.ime.lingxi.LINGXI_KEY_BUDGET_NS
import com.fskeyboard.ime.lingxi.LingxiSlot
import com.fskeyboard.ime.candidate.CandidateSource
import com.fskeyboard.ime.candidate.SyllableReading
import com.fskeyboard.ime.candidate.ZhuyinComposition
import com.fskeyboard.ime.engine.ChiaKeyFrame
import com.fskeyboard.ime.engine.EngineSnapshot
import com.fskeyboard.ime.engine.ZhuyinEngine
import com.fskeyboard.ime.keyboard.KeyAction
import com.fskeyboard.ime.keyboard.KeyboardMode
import com.fskeyboard.ime.latency.KeyStages
import com.fskeyboard.ime.lexicon.Lexicon

private const val ZHUYIN_CHAR_LIMIT = 48
private const val ZHUYIN_PREFIX_LIMIT = 8

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
        if (held) holdingSentence = true
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
            chain.backspace()
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
            } else {
                chain.backspace()
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
     * Open bopomofo becomes the first Hanzi that covers every typed syllable.
     * The gray prediction stays on its own chip.
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
     * A covering Hanzi candidate replaces the bopomofo. Anything else keeps the typed letters.
     * [acceptPartial] edits on its own, so this does not wrap that call in [ZhuyinHost.edit].
     */
    private fun commitComposition() {
        val reading = if (!chain.isEmpty) chain.text else pendingToneText
        if (reading.isNotEmpty()) {
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
        val rows = withCreated(reading, candidates)
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
            return withLiteral(readings, lexicon.partialCandidates(readings))
        }
        val syllables = readings.map { it.body + it.tone.mark }
        val openTone = BooleanArray(readings.size) { !readings[it].tone.chosen }
        val readingKey = buildString { for (syllable in syllables) append(syllable) }
        val started = keyStartedNs
        val deadline = if (started == 0L) System.nanoTime() + LINGXI_KEY_BUDGET_NS else started + LINGXI_KEY_BUDGET_NS
        val result = try {
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
        servedByLingxi = true
        lastReorder = result.reorder
        if (result.candidates.isEmpty()) return emptyList()
        val rows = result.candidates.map { text ->
            Candidate(
                text = text,
                engineIndex = LINGXI_CANDIDATE,
                source = CandidateSource.SYNTHETIC,
                span = readings.size,
                reading = readingKey,
            )
        }
        val phrases = result.completion?.let { hint ->
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
        return withChoices(readings, syllables, openTone, phrases)
    }

    /** Full phrases, then the typed bopomofo, then shorter words and first-syllable characters. */
    private fun withChoices(
        readings: List<SyllableReading>,
        syllables: List<String>,
        openTone: BooleanArray,
        phrases: List<Candidate>,
    ): List<Candidate> {
        val rows = ArrayList<Candidate>(phrases.size + 16)
        rows += phrases
        val literal = literalCandidate(readings)
        if (literal != null) rows += literal
        val engine = lingxi?.engine
        if (engine == null || readings.size < 2) return rows
        val seen = HashSet<String>(rows.size)
        for (row in rows) seen += "${row.span}\t${row.text}"
        for (length in readings.size - 1 downTo 1) {
            val hits = try {
                engine.exactHits(syllables.subList(0, length), openTone.copyOfRange(0, length))
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
        host.lingxiLine("lingxi key-to-row ms=$ms reorder=$reorder")
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
        chain.dropFront(span.coerceAtMost(chain.size))
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
        if (span.reading.isNotEmpty()) chain.insertFront(span.reading)
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
        if (enginePrefix.isNotEmpty()) chain.insertFront(enginePrefix)
        val keys = enginePrefixKeys + tailKeys
        enginePrefix.clear()
        enginePrefixKeys = 0
        repeat(keys) { frame = engine.handleKey(8) }
        mirrored = 0
        holdingSentence = true
    }

    private fun clearChain() {
        chain.clear()
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

    /** Hanzi the user picked, and the syllables they had typed for it. */
    private class ChosenSpan(val text: String, val reading: List<SyllableReading>)
}
