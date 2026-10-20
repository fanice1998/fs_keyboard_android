package com.fskeyboard.ime.candidate

/**
 * Sentence-final particles that fit the text just typed, best first, and the punctuation
 * that fits once a particle ends it.
 * A question word asks for 啊 呀 呢 咧, never 吧 or 囉. A reply such as 好 or 對
 * takes 啊 喔 啦; thanks or an apology takes 啦 喔 耶. Anything else gets the
 * statement particles. Nothing is offered after punctuation, Latin text, or a particle.
 */
internal object Particles {
    private val questionWords = listOf("什麼", "甚麼", "怎麼", "怎樣", "為什麼", "為何", "多少", "哪", "誰", "幾")
    private val question = listOf("啊", "呀", "呢", "咧")
    private val thanksWords = listOf("謝謝", "感謝", "多謝", "辛苦了", "抱歉", "不好意思", "對不起")
    private val thanks = listOf("啦", "喔", "耶", "哈", "囉")
    private val replyWords = listOf("好", "對", "是", "行", "可以", "沒問題", "嗯")
    private val reply = listOf("啊", "喔", "啦", "吧", "耶", "呀", "囉")
    private val statement = listOf("吧", "啊", "啦", "喔", "嗎", "呢", "耶", "哈", "欸", "囉", "嘛", "呀")

    /** Every particle any rule offers. */
    val all: Set<String> = (question + thanks + reply + statement).toSet()

    /** How far back a question word still counts. */
    private const val WINDOW = 8

    fun suggest(before: String): List<String> {
        val tail = before.takeLast(WINDOW)
        val last = tail.lastOrNull() ?: return emptyList()
        // Below CJK: Latin, digits, spaces and ASCII punctuation.
        if (last.code < 0x2E80 || last in cjkPunctuation) return emptyList()
        if (last.toString() in all) return emptyList()
        return when {
            questionWords.any { it in tail } -> question
            thanksWords.any { tail.endsWith(it) } -> thanks
            replyWords.any { tail.endsWith(it) } -> reply
            else -> statement
        }
    }

    private val asking = setOf("嗎", "呢", "咧")
    private val exclaiming = setOf("啊", "呀", "耶", "哈", "喔", "啦", "欸", "囉", "嘛", "唷")

    /**
     * Punctuation to offer when [before] ends with a particle, best first; empty otherwise.
     * 嗎 呢 咧, or any particle after a question word, put ？ first; 吧 puts 。 first;
     * an exclamation such as 啊 or 啦 puts ！ first.
     */
    fun punctuationAfter(before: String): List<String> {
        val tail = before.takeLast(WINDOW)
        val last = tail.lastOrNull()?.toString() ?: return emptyList()
        if (last !in all && last !in exclaiming) return emptyList()
        return when {
            last in asking || questionWords.any { it in tail.dropLast(1) } ->
                listOf("？", "，", "。", "！", "……", "～", "、")
            last == "吧" -> listOf("。", "？", "！", "，", "……", "～", "、")
            else -> listOf("！", "～", "。", "，", "……", "？", "、")
        }
    }

    /** [candidates] with the particles that fit [before] moved to the front, in [suggest] order. */
    fun promote(candidates: List<Candidate>, before: String): List<Candidate> {
        val fitting = suggest(before)
        if (fitting.isEmpty()) return candidates
        val (particles, rest) = candidates.partition { it.text in fitting }
        if (particles.isEmpty()) return candidates
        return particles.sortedBy { fitting.indexOf(it.text) } + rest
    }

    private val cjkPunctuation = setOf('。', '，', '、', '；', '：', '？', '！', '…', '」', '』', '）', '》', '〉', '】')
}
