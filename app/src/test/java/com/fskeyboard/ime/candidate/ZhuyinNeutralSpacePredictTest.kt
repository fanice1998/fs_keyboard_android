package com.fskeyboard.ime.candidate

import android.content.ContextWrapper
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.fskeyboard.ime.engine.ChiaKeyFrame
import com.fskeyboard.ime.engine.ZhuyinEngine
import com.fskeyboard.ime.keyboard.KeyAction
import com.fskeyboard.ime.keyboard.ZhuyinSpelling
import com.fskeyboard.ime.keyboard.ZhuyinTone
import com.fskeyboard.ime.lingxi.LingxiCensus
import com.fskeyboard.ime.lingxi.LingxiEngine
import com.fskeyboard.ime.lingxi.LingxiLm
import com.fskeyboard.ime.lingxi.LingxiSlot
import com.fskeyboard.ime.lingxi.LingxiTables
import com.fskeyboard.ime.lingxi.LexiconRow
import com.fskeyboard.ime.lingxi.decodeLexicon
import com.fskeyboard.ime.lingxi.encodeLexicon
import com.fskeyboard.ime.rerank.CandidatePipeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 空白鍵把未標調的音節設成無聲調（LEVEL），不是輕聲（NEUTRAL）；
 * 組字中也依 committedContext 去掉未完注音後，用 following 的下一詞做灰色預測片。
 * 夾具全部自製：FSLX 小表、PartialLexicon、encodeLexicon 的小 LingxiLexicon。
 * 這一輪斷言應通過。
 */
class ZhuyinNeutralSpacePredictTest {

    // ---- BopomofoChain：空白的輕聲契約 ----

    @Test
    fun `markNeutral把最後一個未標調音節設成輕聲˙並回傳true`() {
        val chain = BopomofoChain()
        "ㄚㄧㄢ".forEach { chain.append(it) }
        assertTrue(chain.markNeutral())
        val last = chain.readings().last()
        assertEquals(ZhuyinTone.NEUTRAL, last.tone)
        assertEquals("ㄚㄧㄢ˙", chain.text)
        assertTrue("輕聲˙是已選定的聲調", last.tone.chosen)
        assertFalse("openTone 是 !chosen", !last.tone.chosen)
    }

    @Test
    fun `markNeutral在已有聲調或沒有音節時回傳false`() {
        val empty = BopomofoChain()
        assertFalse(empty.markNeutral())
        val toned = BopomofoChain()
        "ㄚㄧㄢˊ".forEach { toned.append(it) }
        assertFalse(toned.markNeutral())
        assertEquals("ㄚㄧㄢˊ", toned.text)
        assertEquals(ZhuyinTone.SECOND, toned.readings().last().tone)
    }

    @Test
    fun `markLevel仍把NONE設成LEVEL且不加符號`() {
        val chain = BopomofoChain()
        "ㄚㄧㄢ".forEach { chain.append(it) }
        assertTrue(chain.markLevel())
        assertEquals(ZhuyinTone.LEVEL, chain.readings().last().tone)
        assertEquals("ㄚㄧㄢ", chain.text)
    }

    @Test
    fun `鍵盤的˙仍走append`() {
        val chain = BopomofoChain()
        "ㄚㄧㄢ".forEach { chain.append(it) }
        assertTrue(chain.append('˙'))
        assertEquals(ZhuyinTone.NEUTRAL, chain.readings().last().tone)
        assertEquals("ㄚㄧㄢ˙", chain.text)
    }

    @Test
    fun `退格先清輕聲˙再刪符號`() {
        val chain = BopomofoChain()
        "ㄚㄧㄢ˙".forEach { chain.append(it) }
        chain.backspace()
        assertEquals("ㄚㄧㄢ", chain.text)
        chain.backspace()
        assertEquals("ㄚㄧ", chain.text)
    }

    // ---- 空白鍵經過控制器 ----

    @Test
    fun `未標調的ㄚㄧㄢ按空白預覽是ㄚㄧㄢ且聲調是LEVEL漢字只剩晏`() {
        val rig = rig()
        rig.controller.onStartInput(false)
        type(rig.controller, "ㄚㄧㄢ")
        val before = hanziOf(rig.states).map { it.text }
        assertTrue("未標調時平聲晏與輕聲呀都應出現: $before", before.contains("晏") && before.contains("呀"))
        rig.controller.onKey(KeyAction.Space)
        assertEquals("空白標無聲調所以預覽不帶˙", "ㄚㄧㄢ", rig.states.last().preview)
        val after = hanziOf(rig.states)
        assertEquals(listOf("晏"), after.map { it.text })
        assertEquals("候選 reading 是ㄚㄧㄢ不含˙", "ㄚㄧㄢ", after.single().reading)
        assertFalse(after.single().reading.contains("˙"))
        assertFalse("空白不進 handleAscii", rig.engine.asciiLog.toString().contains(' '))
        assertTrue("空白不選第一個候選", rig.engine.selects.isEmpty())
        assertEquals("", rig.field.toString())
    }

    @Test
    fun `鍵盤˙鍵仍是ㄚㄧㄢ˙且只剩呀`() {
        val rig = rig()
        rig.controller.onStartInput(false)
        type(rig.controller, "ㄚㄧㄢ")
        type(rig.controller, "˙")
        assertEquals("ㄚㄧㄢ˙", rig.states.last().preview)
        assertEquals(listOf("呀"), hanziOf(rig.states).map { it.text })
    }

    @Test
    fun `已標調的ㄚㄧㄢˊ再按空白聲調不變也不進引擎`() {
        val rig = rig()
        rig.controller.onStartInput(false)
        type(rig.controller, "ㄚㄧㄢˊ")
        rig.controller.onKey(KeyAction.Space)
        assertEquals("ㄚㄧㄢˊ", rig.states.last().preview)
        assertTrue(rig.engine.selects.isEmpty())
        assertFalse(rig.engine.asciiLog.toString().contains(' '))
    }

    @Test
    fun `沒有組字時按空白欄位輸入一個空白`() {
        val rig = rig()
        rig.controller.onStartInput(false)
        rig.controller.onKey(KeyAction.Space)
        assertEquals(" ", rig.field.toString())
        assertEquals("", rig.states.last().preview)
    }

    @Test
    fun `選滿音節的漢字後按空白送出漢字加一個空白`() {
        val rig = rig()
        rig.controller.onStartInput(false)
        type(rig.controller, "ㄚㄧㄢ")
        rig.controller.onKey(KeyAction.Space)
        val yan = hanziOf(rig.states).firstOrNull { it.text == "晏" }
        assertTrue("空白後平聲字晏要可點: ${hanziOf(rig.states).map { it.text }}", yan != null)
        rig.controller.onCandidate(yan!!)
        assertEquals("晏", rig.field.toString())
        rig.controller.onKey(KeyAction.Space)
        assertEquals("晏 ", rig.field.toString())
        assertFalse("不得送出呀", rig.field.toString().contains("呀"))
    }

    @Test
    fun `空白後第一次退格聲調回到未選第二次才刪符號`() {
        val rig = rig()
        rig.controller.onStartInput(false)
        type(rig.controller, "ㄚㄧㄢ")
        rig.controller.onKey(KeyAction.Space)
        assertEquals("空白後預覽仍是ㄚㄧㄢ", "ㄚㄧㄢ", rig.states.last().preview)
        rig.controller.onKey(KeyAction.Backspace)
        assertEquals("第一次退格只清聲調預覽不變", "ㄚㄧㄢ", rig.states.last().preview)
        val afterFirst = hanziOf(rig.states).map { it.text }
        assertTrue("聲調回到未選後晏與呀都再出現: $afterFirst", afterFirst.contains("晏") && afterFirst.contains("呀"))
        rig.controller.onKey(KeyAction.Backspace)
        assertEquals("第二次退格才刪符號", "ㄚㄧ", rig.states.last().preview)
        assertEquals("", rig.field.toString())
    }

    // ---- 組字中的下一詞預測 ----

    @Test
    fun `今天之後不完整的ㄚ仍出現夾具漢字`() {
        val rig = rig(initialField = "今天")
        rig.controller.onStartInput(false)
        type(rig.controller, "ㄚ")
        assertEquals("ㄚ", rig.states.last().preview)
        val state = rig.states.last()
        val chips = state.candidates.candidates.filter { it.completion }
        val texts = chips.map { it.text }
        assertTrue("組字中要有下一詞片: ${state.candidates.candidates.map { it.text }}", chips.isNotEmpty())
        assertTrue("ㄚ對不上任何讀音時曙仍在: $texts", "曙" in texts)
        assertTrue("ㄚ對不上任何讀音時瀾仍在: $texts", "瀾" in texts)
        assertFalse("誤用蓋住候選啊當上下文會出現錯", "錯" in texts)
        assertTrue("灰色片不是蓋住全部音節的候選", chips.all { !it.coversOpenSyllables(state.candidates.openSyllables) })
    }

    @Test
    fun `你之後不完整的ㄏ仍有往後的漢字（ㄏ對上翰，只留翰）`() {
        val rig = rig(initialField = "你")
        rig.controller.onStartInput(false)
        type(rig.controller, "ㄏ")
        val texts = chipsOf(rig.states)
        assertTrue("ㄏ對上翰的讀音ㄏㄢˋ: $texts", "翰" in texts)
        assertFalse("ㄏ對不上澄ㄔㄥˊ", "澄" in texts)
        assertFalse("ㄏ對不上沐ㄇㄨˋ", "沐" in texts)
    }

    @Test
    fun `部分注音對得上只留對得上的，對不上留未過濾的`() {
        val matched = rig(initialField = "今天")
        matched.controller.onStartInput(false)
        type(matched.controller, "ㄕ")
        val matchedTexts = chipsOf(matched.states)
        assertTrue("ㄕ是曙ㄕㄨˋ的前綴: $matchedTexts", "曙" in matchedTexts)
        assertFalse("ㄕ不是瀾ㄌㄢˊ的前綴", "瀾" in matchedTexts)

        val unmatched = rig(initialField = "今天")
        unmatched.controller.onStartInput(false)
        type(unmatched.controller, "ㄚ")
        val unmatchedTexts = chipsOf(unmatched.states)
        assertTrue("完全對不上時曙留著: $unmatchedTexts", "曙" in unmatchedTexts)
        assertTrue("完全對不上時瀾留著: $unmatchedTexts", "瀾" in unmatchedTexts)
    }

    @Test
    fun `沒有組字時空閒建議仍在`() {
        val rig = rig(initialField = "我們")
        rig.controller.onStartInput(false)
        val row = rig.states.last().candidates.candidates
        assertEquals(listOf("說", "一個"), row.map { it.text })
        assertTrue(row.all { it.source == CandidateSource.SUGGESTION })
    }

    @Test
    fun `Enter 不送出灰色預測片`() {
        val rig = rig(initialField = "今天")
        rig.controller.onStartInput(false)
        type(rig.controller, "ㄚ")
        val chips = rig.states.last().candidates.candidates.filter { it.completion }
        assertTrue("組字中要先有灰色下一詞片: ${rig.states.last().candidates.candidates.map { it.text }}", chips.isNotEmpty())
        rig.controller.onKey(KeyAction.Enter)
        assertEquals("Enter 送已打的注音ㄚ而不是灰色片", "今天ㄚ", rig.field.toString())
        assertFalse(rig.field.toString().contains("曙"))
        assertFalse(rig.field.toString().contains("瀾"))
        assertEquals("", rig.states.last().preview)
    }

    @Test
    fun `三個開放聲調的韻母不讓下一詞消失`() {
        val rig = rig(initialField = "今天")
        rig.controller.onStartInput(false)
        type(rig.controller, "ㄚㄞㄠ")
        assertEquals("ㄚㄞㄠ", rig.states.last().preview)
        val texts = chipsOf(rig.states)
        assertTrue("open tone 超過 2 預測仍在: $texts", "曙" in texts)
        assertTrue("open tone 超過 2 預測仍在: $texts", "瀾" in texts)
    }

    @Test
    fun `詞典裡查不到讀音的下一詞不會被藏起來`() {
        val rig = rig(initialField = "我們")
        rig.controller.onStartInput(false)
        type(rig.controller, "ㄒ")
        val texts = chipsOf(rig.states)
        assertTrue("說不在詞典讀音裡也要在: $texts", "說" in texts)
        assertTrue("一個不在詞典讀音裡也要在: $texts", "一個" in texts)
    }

    @Test
    fun `點下一詞灰色片送出整段漢字並結束組字`() {
        val rig = rig(initialField = "今天")
        rig.controller.onStartInput(false)
        type(rig.controller, "ㄚ")
        val chip = rig.states.last().candidates.candidates.firstOrNull { it.completion && it.text == "曙" }
        assertTrue("要有曙的灰色下一詞片: ${rig.states.last().candidates.candidates.map { it.text }}", chip != null)
        rig.controller.onCandidate(chip!!)
        assertEquals("今天曙", rig.field.toString())
        assertFalse("未完注音不能留在欄位", rig.field.toString().contains("ㄚ"))
        assertEquals("", rig.states.last().preview)
        assertEquals("", rig.composing.toString())
    }

    @Test
    fun `打完整ㄕㄨˋ時曙光完成片與下一詞曙同時出現`() {
        val rig = rig(initialField = "今天", withLm = true)
        rig.controller.onStartInput(false)
        type(rig.controller, "ㄕㄨˋ")
        val row = rig.states.last().candidates.candidates
        assertTrue("既有灰色完成片曙光仍在: ${row.map { it.text }}", row.any { it.completion && it.text == "曙光" })
        assertTrue("下一詞曙也要出現: ${row.map { it.text }}", row.any { it.completion && it.text == "曙" })
        assertTrue("一般候選曙仍在", row.any { !it.completion && !it.literal && it.text == "曙" })
    }

    @Test
    fun `預覽還有注音時點一般候選漢字放在剩餘注音前面`() {
        val rig = rig(initialField = "今天", withLm = true)
        rig.controller.onStartInput(false)
        type(rig.controller, "ㄕㄨˋㄍㄨㄤ")
        val shu = rig.states.last().candidates.candidates.firstOrNull {
            !it.completion && !it.literal && it.text == "曙" && it.span == 1
        }
        assertTrue("要有 span 較短的一般候選曙: ${rig.states.last().candidates.candidates.map { it.text }}", shu != null)
        rig.controller.onCandidate(shu!!)
        assertEquals("欄位不先寫入", "今天", rig.field.toString())
        assertEquals("曙ㄍㄨㄤ", rig.states.last().preview)
    }

    // ---- 夾具 ----

    private class Rig(
        val engine: ScriptedEngine,
        val field: StringBuilder,
        val composing: StringBuilder,
        val states: MutableList<ImeUiState>,
        val controller: CandidateController,
    )

    /** 全套夾具：FSLX 接續、PartialLexicon、小 LingxiEngine 掛進 LingxiSlot。 */
    private fun rig(initialField: String = "", withLm: Boolean = false): Rig {
        val engine = ScriptedEngine()
        val field = StringBuilder(initialField)
        val composing = StringBuilder()
        val states = mutableListOf<ImeUiState>()
        val slot = LingxiSlot()
        slot.engine = lingxiEngine(withLm)
        val controller = controller(engine, field, composing, states, partialLexicon(), continuations(), slot)
        return Rig(engine, field, composing, states, controller)
    }

    private fun type(controller: CandidateController, glyphs: String) {
        val spelling = ZhuyinSpelling.keysOf(glyphs)
        for (index in glyphs.indices) {
            controller.onKey(KeyAction.Bopomofo(glyphs[index].toString(), spelling[index]))
        }
    }

    private fun hanziOf(states: List<ImeUiState>): List<Candidate> {
        return states.last().candidates.candidates.filter { !it.literal && !it.completion }
    }

    private fun chipsOf(states: List<ImeUiState>): List<String> {
        return states.last().candidates.candidates.filter { it.completion }.map { it.text }
    }

    /** 接續只給空閒建議與下一詞，讀音只放在 Lingxi 小表裡。 */
    private fun continuations(): WordContinuations {
        return WordContinuations.open(
            compiledPairs(
                Triple("今天", "曙", -0.2f),
                Triple("今天", "瀾", -0.5f),
                Triple("你", "翰", -0.2f),
                Triple("你", "澄", -0.4f),
                Triple("你", "沐", -0.6f),
                Triple("我們", "說", -0.2f),
                Triple("我們", "一個", -1.4f),
                Triple("啊", "錯", -0.2f),
            ),
        )!!
    }

    /** 平聲晏（ㄚㄧㄢ）與輕聲呀（ㄚㄧㄢ˙）；ㄚ 不是詞典的完整音身，FSLX 的「啊→錯」留著抓誤用。 */
    private fun partialLexicon(): PartialLexicon {
        return PartialLexicon.index(
            listOf(
                UnigramRow.of("晏", listOf("ㄚㄧㄢ"), -1.0),
                UnigramRow.of("呀", listOf("ㄚㄧㄢ˙"), -1.1),
            ),
        )
    }

    private fun lingxiEngine(withLm: Boolean): LingxiEngine {
        val rows = listOf(
            row(0, "曙", 0.9f),
            row(1, "瀾", 0.8f),
            row(2, "翰", 0.7f),
            row(3, "澄", 0.6f),
            row(4, "沐", 0.5f),
            row(intArrayOf(0, 5), "曙光", 0.4f),
            row(6, "晏", 0.95f),
            row(7, "呀", 0.85f),
        )
        val lexicon = decodeLexicon(encodeLexicon(SYLLABLES, rows))
        val lm = if (withLm) {
            lmOf(listOf("今天", "曙", "曙光"), listOf("今天" to "曙光"))
        } else {
            bareLm()
        }
        return LingxiEngine(LingxiTables(lexicon, lm, LingxiCensus(1, 1, 1, 1, 1, 1)), null)
    }

    private fun row(syllable: Int, surface: String, prw: Float): LexiconRow {
        return LexiconRow(intArrayOf(syllable), surface, prw, 0)
    }

    private fun row(reading: IntArray, surface: String, prw: Float): LexiconRow {
        return LexiconRow(reading, surface, prw, 0)
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

    /** 只記錄有沒有把鍵送進引擎；組字全走 chain。 */
    private class ScriptedEngine : ZhuyinEngine {
        val asciiLog = StringBuilder()
        val selects = mutableListOf<Int>()
        private val idle = ChiaKeyFrame.parse("")

        override val ready: Boolean = true

        override fun handleAscii(key: Char): ChiaKeyFrame {
            asciiLog.append(key)
            return idle
        }

        override fun handleKey(code: Int): ChiaKeyFrame = idle

        override fun select(index: Int): ChiaKeyFrame {
            selects += index
            return idle
        }

        override fun acknowledge() = Unit

        override fun reset() = Unit
    }

    /**
     * 最小控制器夾具。getTextBeforeCursor 回傳欄位加組字底線，
     * 像真實編輯器一樣 caret 在底線裡，所以 committedContext() 會帶未完注音。
     */
    private fun controller(
        engine: ZhuyinEngine,
        field: StringBuilder,
        composing: StringBuilder,
        states: MutableList<ImeUiState>,
        partial: PartialLexicon,
        continuations: WordContinuations,
        lingxi: LingxiSlot?,
    ): CandidateController {
        val connection = Proxy.newProxyInstance(
            InputConnection::class.java.classLoader,
            arrayOf(InputConnection::class.java),
        ) { _, method, args ->
            when (method.name) {
                "beginBatchEdit", "endBatchEdit", "deleteSurroundingText", "finishComposingText" -> true
                "commitText" -> {
                    field.append(args?.get(0)?.toString().orEmpty())
                    composing.setLength(0)
                    true
                }
                "setComposingText" -> {
                    composing.setLength(0)
                    composing.append(args?.get(0)?.toString().orEmpty())
                    true
                }
                "getTextBeforeCursor" -> field.toString() + composing.toString()
                "getTextAfterCursor" -> ""
                "getSelectedText", "getExtractedText" -> null
                "setSelection", "sendKeyEvent" -> method.name == "setSelection"
                "performEditorAction", "commitContent" -> false
                "toString" -> "field"
                "hashCode" -> 1
                "equals" -> false
                else -> null
            }
        } as InputConnection
        return CandidateController(
            context = ContextWrapper(null),
            pipeline = CandidatePipeline(),
            connection = { connection },
            editorInfo = { EditorInfo() },
            onUi = { states.add(it) },
            post = { it.run() },
            clipboard = { null },
            continuations = continuations,
            zhuyinEngine = engine,
            partialLexicon = partial,
            lingxi = lingxi,
        )
    }

    /** A tiny FSLX version-2 buffer with just the word table and its pairs. */
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
        val buffer = ByteBuffer.allocate(48 + names.size * 16 + pairs.size * 8 + stringBytes.size)
            .order(ByteOrder.LITTLE_ENDIAN)
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
        val pairBytes = ByteBuffer.allocate(pairs.size * 8).order(ByteOrder.LITTLE_ENDIAN)
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

    private companion object {
        // ㄚ 不是任何詞的完整音身，音節表不收單獨的「ㄚ」。
        val SYLLABLES = listOf("ㄕㄨˋ", "ㄌㄢˊ", "ㄏㄢˋ", "ㄔㄥˊ", "ㄇㄨˋ", "ㄍㄨㄤ", "ㄚㄧㄢ", "ㄚㄧㄢ˙")
    }
}
