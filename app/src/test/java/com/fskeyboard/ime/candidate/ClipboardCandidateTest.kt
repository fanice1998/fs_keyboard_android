package com.fskeyboard.ime.candidate

import android.content.Context
import android.content.ContextWrapper
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.fskeyboard.ime.engine.ZhuyinLexicon
import com.fskeyboard.ime.keyboard.KeyAction
import com.fskeyboard.ime.keyboard.KeyboardMode
import com.fskeyboard.ime.rerank.CandidatePipeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

class ClipboardCandidateTest {
    @Test
    fun aLongClipboardBecomesOneShortLineAndKeepsThePaste() {
        val raw = "你好\n世界\t" + "字".repeat(40)
        val offer = clipOffer(raw)
        assertEquals(raw, offer!!.text)
        assertFalse(offer.label.contains('\n'))
        assertFalse(offer.label.contains('\t'))
        assertTrue(offer.label.endsWith("…"))
        assertEquals(CLIP_LABEL_LIMIT + 1, offer.label.codePointCount(0, offer.label.length))
        assertEquals("a b", clipOffer("  a\n\nb\t ")!!.label)
        assertEquals("  a\n\nb\t ", clipOffer("  a\n\nb\t ")!!.text)
        assertNull(clipOffer(" \n\t"))
        val emoji = "a".repeat(CLIP_LABEL_LIMIT) + "\uD83D\uDE00"
        assertEquals("a".repeat(CLIP_LABEL_LIMIT) + "…", clipLabel(emoji))
    }

    @Test
    fun textWinsOverAnImageAndHtmlFallsBackToCoercedText() {
        val both = clipOffer("hi", imageUri = "content://x", imageMime = "image/png")
        assertEquals("hi", both!!.text)
        assertNull(both.imageUri)
        val image = clipOffer(
            text = " \n",
            imageUri = "content://clipboard/picture",
            imageMime = "image/png",
            coerced = "content://clipboard/picture",
        )
        assertEquals(IMAGE_LABEL, image!!.label)
        assertNull(image.text)
        assertEquals("content://clipboard/picture", image.imageUri)
        assertEquals("from html", clipOffer(text = null, coerced = "from html")!!.text)
    }

    @Test
    fun anImageIsAcceptedOnlyWhenTheFieldListsThatType() {
        assertFalse(mimeAccepted(null, "image/png"))
        assertFalse(mimeAccepted(emptyArray(), "image/png"))
        assertFalse(mimeAccepted(arrayOf("text/plain"), "image/png"))
        assertTrue(mimeAccepted(arrayOf("image/*"), "image/png"))
        assertFalse(mimeAccepted(arrayOf("image/png"), "image/jpeg"))
        assertTrue(mimeAccepted(arrayOf("image/png"), "image/png"))
        assertTrue(mimeAccepted(arrayOf("*/*"), "image/jpeg"))
    }

    @Test
    fun openingShowsTheShortLabelAndATapPastesTheWholeClip() {
        val raw = "第一行\n第二行" + "文".repeat(30)
        val handler = RecordingConnection()
        val states = mutableListOf<ImeUiState>()
        val controller = controller(
            handler = handler,
            states = states,
            clipboard = { clipOffer(raw) },
        )

        controller.onStartInput(restarting = false)

        val chip = states.last().candidates.candidates.single()
        assertEquals(CandidateSource.CLIPBOARD, chip.source)
        assertEquals(clipLabel(raw), chip.text)
        assertTrue(chip.text.endsWith("…"))
        assertTrue(chip.text.length < raw.length)

        controller.onCandidate(chip)

        assertEquals(raw, handler.committed.single())
        assertEquals(CandidateSource.CLIPBOARD, states.last().candidates.candidates.single().source)
    }

    @Test
    fun typingHidesTheClipboardUntilTheKeyboardOpensAgain() {
        val handler = RecordingConnection()
        val states = mutableListOf<ImeUiState>()
        val controller = controller(
            handler = handler,
            states = states,
            clipboard = { clipOffer("複製的句子") },
        )

        controller.onStartInput(restarting = false)
        assertEquals("複製的句子", states.last().candidates.candidates.single().text)

        controller.onKey(KeyAction.Shift)
        controller.onKey(KeyAction.Backspace)
        assertEquals(CandidateSource.CLIPBOARD, states.last().candidates.candidates.single().source)

        controller.onKey(KeyAction.SetMode(KeyboardMode.SYMBOLS))
        assertTrue(states.last().candidates.candidates.isEmpty())
        assertEquals(KeyboardMode.SYMBOLS, states.last().mode)

        controller.onKey(KeyAction.SetMode(KeyboardMode.ZHUYIN))
        assertEquals(CandidateSource.CLIPBOARD, states.last().candidates.candidates.single().source)

        controller.onKey(KeyAction.Punctuation("。"))
        assertTrue(states.last().candidates.candidates.none { it.source == CandidateSource.CLIPBOARD })
        assertEquals("。", handler.committed.last())

        controller.onKey(KeyAction.Backspace)
        assertTrue(states.last().candidates.candidates.none { it.source == CandidateSource.CLIPBOARD })

        controller.onStartInput(restarting = true)
        assertEquals(CandidateSource.CLIPBOARD, states.last().candidates.candidates.single().source)
    }

    @Test
    fun anImageClipShowsAPictureChipAndDoesNotInsertText() {
        val handler = RecordingConnection()
        val states = mutableListOf<ImeUiState>()
        val controller = controller(
            handler = handler,
            states = states,
            clipboard = {
                clipOffer(text = null, imageUri = "content://clipboard/picture", imageMime = "image/png")
            },
        )

        controller.onStartInput(restarting = false)
        val chip = states.last().candidates.candidates.single()
        assertEquals(IMAGE_LABEL, chip.text)
        assertEquals(CandidateSource.CLIPBOARD, chip.source)

        controller.onCandidate(chip)

        assertTrue(handler.committed.isEmpty())
        assertEquals(IMAGE_LABEL, states.last().candidates.candidates.single().text)
    }

    @Test
    fun clearingTheClipboardRemovesTheChip() {
        val states = mutableListOf<ImeUiState>()
        val controller = controller(
            states = states,
            clipboard = { clipOffer("複製的句子") },
        )

        controller.onStartInput(restarting = false)
        assertEquals(CandidateSource.CLIPBOARD, states.last().candidates.candidates.single().source)

        controller.dismissClipboard()

        assertTrue(states.last().candidates.candidates.isEmpty())
        assertFalse(clearSystemClipboard(object : ContextWrapper(null) {}))
        assertFalse(primaryClipHasContent(null, hasUri = false))
        assertFalse(primaryClipHasContent("  \n", hasUri = false))
        assertTrue(primaryClipHasContent("還在", hasUri = false))
        assertTrue(primaryClipHasContent(null, hasUri = true))
    }

    @Test
    fun theClipboardRowStaysAStripWhenTheWindowOffersTheWholeScreen() {
        val screen = 2400
        val chip = 72
        assertEquals(chip, stripMeasuredHeight(View.MeasureSpec.AT_MOST, screen, chip))
        assertEquals(chip, stripMeasuredHeight(View.MeasureSpec.UNSPECIFIED, screen, chip))
        assertEquals(40, stripMeasuredHeight(View.MeasureSpec.AT_MOST, 40, chip))
        assertEquals(120, stripMeasuredHeight(View.MeasureSpec.EXACTLY, 120, chip))
    }

    @Test
    fun theLastLetterKeyboardOpensAgainAndAnEmailFieldDoesNotReplaceIt() {
        val saved = mutableListOf<KeyboardMode>()
        val states = mutableListOf<ImeUiState>()
        var remembered = KeyboardMode.LATIN
        val controller = controller(
            states = states,
            letterMode = { remembered },
            clipboard = { null },
            onLetterMode = { saved.add(it) },
        )

        controller.start()
        assertEquals(KeyboardMode.LATIN, states.last().mode)
        assertEquals(KeyboardMode.LATIN, states.last().base)
        assertTrue(saved.isEmpty())

        controller.onStartInput(restarting = false)
        assertEquals(KeyboardMode.LATIN, states.last().mode)
        controller.onKey(KeyAction.SetMode(KeyboardMode.SYMBOLS))
        assertTrue(saved.isEmpty())
        controller.onStartInput(restarting = true)
        assertEquals(KeyboardMode.LATIN, states.last().mode)
        assertEquals(KeyboardMode.LATIN, states.last().base)
        assertTrue(saved.isEmpty())

        val fromZhuyin = mutableListOf<KeyboardMode>()
        val zhuyin = controller(
            letterMode = { KeyboardMode.ZHUYIN },
            clipboard = { null },
            onLetterMode = { fromZhuyin.add(it) },
        )
        zhuyin.onStartInput(restarting = false)
        zhuyin.onKey(KeyAction.SetMode(KeyboardMode.LATIN))
        assertEquals(listOf(KeyboardMode.LATIN), fromZhuyin)
        zhuyin.onKey(KeyAction.SetMode(KeyboardMode.SYMBOLS))
        zhuyin.onKey(KeyAction.SetMode(KeyboardMode.LATIN))
        assertEquals(listOf(KeyboardMode.LATIN), fromZhuyin)

        var email = true
        val emailInfo = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        val emailWrites = mutableListOf<KeyboardMode>()
        val emailStates = mutableListOf<ImeUiState>()
        val emailController = controller(
            editorInfo = { if (email) emailInfo else EditorInfo() },
            states = emailStates,
            letterMode = { KeyboardMode.ZHUYIN },
            clipboard = { null },
            onLetterMode = { emailWrites.add(it) },
        )
        emailController.onStartInput(restarting = false)
        assertEquals(KeyboardMode.LATIN, emailStates.last().mode)
        assertEquals(KeyboardMode.LATIN, emailStates.last().base)
        assertTrue(emailWrites.isEmpty())
        email = false
        emailController.onStartInput(restarting = false)
        assertEquals(KeyboardMode.ZHUYIN, emailStates.last().mode)
        assertEquals(KeyboardMode.ZHUYIN, emailStates.last().base)
        assertTrue(emailWrites.isEmpty())
    }

    private class RecordingConnection : InvocationHandler {
        val committed = mutableListOf<String>()

        override fun invoke(proxy: Any?, method: Method, args: Array<out Any>?): Any? {
            return when (method.name) {
                "commitText" -> {
                    committed.add(args?.get(0)?.toString().orEmpty())
                    true
                }
                "deleteSurroundingText" -> true
                "finishComposingText" -> true
                "setComposingText" -> true
                "getSelectedText" -> null
                "toString" -> "RecordingConnection"
                "hashCode" -> 1
                "equals" -> proxy === args?.get(0)
                else -> null
            }
        }
    }

    private fun controller(
        handler: RecordingConnection = RecordingConnection(),
        editorInfo: () -> EditorInfo? = { EditorInfo() },
        states: MutableList<ImeUiState> = mutableListOf(),
        letterMode: () -> KeyboardMode = { KeyboardMode.ZHUYIN },
        clipboard: () -> ClipOffer? = { null },
        onLetterMode: (KeyboardMode) -> Unit = {},
    ): CandidateController {
        val connection = Proxy.newProxyInstance(
            InputConnection::class.java.classLoader,
            arrayOf(InputConnection::class.java),
            handler,
        ) as InputConnection
        val context = object : ContextWrapper(null) {}
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
            context = context,
            lexicon = lexicon,
            pipeline = CandidatePipeline(),
            connection = { connection },
            editorInfo = editorInfo,
            onUi = { states.add(it) },
            post = { it.run() },
            letterMode = letterMode,
            clipboard = clipboard,
        ).also { it.onLetterMode = onLetterMode }
    }
}
