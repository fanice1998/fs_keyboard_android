package com.fskeyboard.ime.candidate

import android.content.Context
import android.content.ContextWrapper
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.fskeyboard.ime.engine.ZhuyinLexicon
import com.fskeyboard.ime.keyboard.KeyAction
import com.fskeyboard.ime.keyboard.KeyboardMode
import com.fskeyboard.ime.rerank.CandidatePipeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

class WordSuggestionTest {
    @Test
    fun hanBeforeTheCaretOffersTheNextWordsAndHidesTheClipboard() {
        val field = StringBuilder("我們")
        val states = mutableListOf<ImeUiState>()
        val controller = controller(field, states, continuations())
        controller.onStartInput(restarting = false)
        assertEquals(listOf("說", "一個"), states.last().candidates.candidates.map { it.text })
        assertTrue(states.last().candidates.candidates.all { it.source == CandidateSource.SUGGESTION })

        controller.onCandidate(states.last().candidates.candidates.first())
        assertEquals(listOf("了"), states.last().candidates.candidates.map { it.text })
        assertEquals("我們說", field.toString())
    }

    @Test
    fun aSpaceOrASelectionLeavesNextWordsOff() {
        val spaced = mutableListOf<ImeUiState>()
        controller(StringBuilder("我們 "), spaced, continuations(), clipboard = { clip() }).onStartInput(false)
        assertEquals(listOf(CandidateSource.CLIPBOARD), spaced.last().candidates.candidates.map { it.source })

        val selected = EditorInfo().apply {
            initialSelStart = 0
            initialSelEnd = 2
        }
        val range = mutableListOf<ImeUiState>()
        controller(StringBuilder("我們"), range, continuations(), editorInfo = { selected }, clipboard = { clip() })
            .onStartInput(false)
        assertEquals(listOf(CandidateSource.CLIPBOARD), range.last().candidates.candidates.map { it.source })
    }

    @Test
    fun aSymbolStillFollowsTheWordWhenTheCaretIsOnPunctuation() {
        val states = mutableListOf<ImeUiState>()
        val controller = controller(StringBuilder("我們"), states, continuations())
        controller.onStartInput(restarting = false)
        controller.onKey(KeyAction.Punctuation("？"))
        assertEquals(listOf("？"), states.last().candidates.candidates.map { it.text })
    }

    @Test
    fun theLatinKeyboardOffersTheSameNextWords() {
        val states = mutableListOf<ImeUiState>()
        val controller = controller(
            StringBuilder("我們"),
            states,
            continuations(),
            letterMode = { KeyboardMode.LATIN },
        )
        controller.onStartInput(restarting = false)
        assertEquals(KeyboardMode.LATIN, states.last().mode)
        assertEquals(listOf("說", "一個"), states.last().candidates.candidates.map { it.text })
    }
}

private fun continuations(): WordContinuations {
    val bytes = compiledPairs(
        Triple("我們", "說", -0.2f),
        Triple("我們", "一個", -1.4f),
        Triple("說", "了", -0.3f),
    )
    return WordContinuations.open(bytes)!!
}

private fun clip(): ClipOffer = ClipOffer(label = "剪過", text = "剪過")

private fun controller(
    field: StringBuilder,
    states: MutableList<ImeUiState>,
    continuations: WordContinuations,
    editorInfo: () -> EditorInfo? = { EditorInfo() },
    clipboard: () -> ClipOffer? = { null },
    letterMode: () -> KeyboardMode = { KeyboardMode.ZHUYIN },
): CandidateController {
    val handler = InvocationHandler { _: Any?, method: Method, args: Array<out Any>? ->
        when (method.name) {
            "commitText" -> {
                field.append(args?.get(0)?.toString().orEmpty())
                true
            }
            "getTextBeforeCursor" -> field.toString()
            "deleteSurroundingText" -> true
            "finishComposingText" -> true
            "setComposingText" -> true
            "getSelectedText" -> null
            "toString" -> "field"
            "hashCode" -> 1
            "equals" -> false
            else -> null
        }
    }
    val connection = Proxy.newProxyInstance(
        InputConnection::class.java.classLoader,
        arrayOf(InputConnection::class.java),
        handler,
    ) as InputConnection
    val lexicon = object : ZhuyinLexicon {
        override val status = com.fskeyboard.ime.engine.EngineStatus.READY
        override val statusDetail = ""
        override val isReady = true
        override fun start(context: Context, onReady: () -> Unit) = Unit
        override fun close() = Unit
        override fun lookup(queries: List<String>, before: String) = emptyList<Candidate>()
        override fun learn(phrases: List<Pair<String, String>>) = Unit
    }
    return CandidateController(
        context = object : ContextWrapper(null) {},
        lexicon = lexicon,
        pipeline = CandidatePipeline(),
        connection = { connection },
        editorInfo = editorInfo,
        onUi = { states.add(it) },
        post = { it.run() },
        letterMode = letterMode,
        clipboard = clipboard,
        continuations = continuations,
    )
}

private fun compiledPairs(vararg pairs: Triple<String, String, Float>): ByteArray {
    val names = pairs.flatMap { listOf(it.first, it.second) }.distinct().sorted()
    val idOf = names.withIndex().associate { it.value to it.index }
    val runs = names.map { mutableListOf<Pair<Int, Float>>() }
    for ((left, right, probability) in pairs) {
        runs[idOf.getValue(left)].add(idOf.getValue(right) to probability)
    }
    for (run in runs) run.sortBy { it.first }
    val strings = java.io.ByteArrayOutputStream()
    val spans = names.map { name ->
        val bytes = name.toByteArray(Charsets.UTF_8)
        val offset = strings.size()
        strings.write(bytes)
        offset to bytes.size
    }
    val stringBytes = strings.toByteArray()
    val buffer = java.nio.ByteBuffer.allocate(48 + names.size * 16 + pairs.size * 8 + stringBytes.size)
        .order(java.nio.ByteOrder.LITTLE_ENDIAN)
    buffer.put("FSLX".toByteArray(Charsets.US_ASCII))
    buffer.putInt(2)
    repeat(4) { buffer.putInt(0) }
    buffer.putInt(names.size)
    buffer.putInt(pairs.size)
    buffer.putInt(0)
    buffer.putInt(stringBytes.size)
    buffer.putInt(0)
    buffer.putInt(0)
    var pairAt = 0
    val pairBytes = java.nio.ByteBuffer.allocate(pairs.size * 8).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    for (index in names.indices) {
        val (offset, length) = spans[index]
        buffer.putInt(offset)
        buffer.putInt(length)
        buffer.putInt(pairAt)
        buffer.putInt(runs[index].size)
        for ((next, probability) in runs[index]) {
            pairBytes.putInt(next)
            pairBytes.putFloat(probability)
            pairAt += 1
        }
    }
    buffer.put(pairBytes.array())
    buffer.put(stringBytes)
    return buffer.array()
}
