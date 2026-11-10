package com.fskeyboard.ime.lingxi

import java.io.File
import java.util.zip.CRC32
import kotlin.math.pow

/** Counts that the public pack must have. A zero refuses the 靈犀 path. */
internal data class LingxiCensus(
    val characters: Int,
    val words: Int,
    val unigrams: Int,
    val multiCharBigrams: Int,
    val singleCharBigrams: Int,
    val englishWords: Int,
) {
    fun requireFull() {
        require(characters > 0) { "lingxi character entries empty" }
        require(words > 0) { "lingxi word entries empty" }
        require(unigrams > 0) { "lingxi unigrams empty" }
        require(multiCharBigrams > 0) { "lingxi multi-character bigrams empty" }
        require(singleCharBigrams > 0) { "lingxi single-character bigrams empty" }
        require(englishWords > 0) { "lingxi english subpack empty" }
    }
}

internal class LexHit(
    val surface: String,
    val prw: Float,
    val rare: Boolean,
)

/**
 * Compact lexicon (`ZUINLEX`). Entries stay in the file layout.
 * [exact] binary-searches the reading-id sequence.
 */
internal class LingxiLexicon(
    private val syllableIds: HashMap<String, Int>,
    private val rd: ShortArray,
    private val rdOff: IntArray,
    private val sfOff: IntArray,
    private val sfLen: IntArray,
    private val rdLen: ByteArray,
    private val flags: ByteArray,
    private val prw: FloatArray,
    private val strs: ByteArray,
    private val byText: IntArray,
) {
    val entryCount: Int get() = rdOff.size

    fun exact(syllables: List<String>): List<LexHit> {
        if (syllables.isEmpty()) return emptyList()
        val query = IntArray(syllables.size)
        for (index in syllables.indices) {
            query[index] = syllableIds[syllables[index]] ?: return emptyList()
        }
        var lo = 0
        var hi = entryCount
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (compareReading(mid, query) < 0) lo = mid + 1 else hi = mid
        }
        val out = ArrayList<LexHit>()
        while (lo < entryCount && compareReading(lo, query) == 0) {
            out += hit(lo)
            lo++
        }
        return out
    }

    fun hasSurface(surface: String): Boolean {
        val key = surface.toByteArray(Charsets.UTF_8)
        var lo = 0
        var hi = entryCount
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (compareSurface(byText[mid], key) < 0) lo = mid + 1 else hi = mid
        }
        return lo < entryCount && compareSurface(byText[lo], key) == 0
    }

    fun census(): Pair<Int, Int> {
        var characters = 0
        var words = 0
        for (index in 0 until entryCount) {
            val runes = utf8Runes(strs, sfOff[index], sfLen[index])
            if (runes == 1) characters++ else if (runes > 1) words++
        }
        return characters to words
    }

    private fun hit(index: Int): LexHit {
        val flag = flags[index].toInt() and 0xFF
        return LexHit(
            surface = String(strs, sfOff[index], sfLen[index], Charsets.UTF_8),
            prw = prw[index],
            rare = flag and 2 != 0,
        )
    }

    private fun compareReading(index: Int, query: IntArray): Int {
        val off = rdOff[index]
        val len = rdLen[index].toInt() and 0xFF
        val shared = minOf(len, query.size)
        for (step in 0 until shared) {
            val left = rd[off + step].toInt() and 0xFFFF
            val right = query[step]
            if (left != right) return left - right
        }
        return len - query.size
    }

    private fun compareSurface(index: Int, key: ByteArray): Int {
        return compareBytes(strs, sfOff[index], sfLen[index], key)
    }
}

/** Compact unigram and bigram model (`ZUINLM`). Bigram miss is 0. Unigram miss is 1e-8. */
internal class LingxiLm(
    private val strs: ByteArray,
    private val off: IntArray,
    private val uni: FloatArray,
    private val leftStart: IntArray,
    private val right: ByteArray,
    private val q: ByteArray,
) {
    val unigrams: Int get() = uni.size
    val bigrams: Int get() = q.size

    fun uni(word: String): Double {
        val index = find(word)
        return if (index >= 0) uni[index].toDouble() else UNSEEN
    }

    fun has(word: String): Boolean = find(word) >= 0

    fun bi(left: String, word: String): Double {
        if (q.isEmpty()) return 0.0
        val leftId = find(left)
        if (leftId < 0 || leftStart[leftId] == leftStart[leftId + 1]) return 0.0
        val rightId = find(word)
        if (rightId < 0) return 0.0
        var lo = leftStart[leftId]
        val hiLimit = leftStart[leftId + 1]
        var hi = hiLimit
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (rightAt(mid) < rightId) lo = mid + 1 else hi = mid
        }
        return if (lo < hiLimit && rightAt(lo) == rightId) Q_TABLE[q[lo].toInt() and 0xFF] else 0.0
    }

    /** Both sides one rune, and both sides longer than one rune. Mixed pairs are neither. */
    fun bigramClasses(): Pair<Int, Int> {
        val runes = IntArray(uni.size)
        for (index in uni.indices) {
            runes[index] = utf8Runes(strs, off[index], off[index + 1] - off[index])
        }
        var single = 0
        var multi = 0
        for (left in uni.indices) {
            val from = leftStart[left]
            val to = leftStart[left + 1]
            val leftRunes = runes[left]
            for (slot in from until to) {
                val rightRunes = runes[rightAt(slot)]
                if (leftRunes == 1 && rightRunes == 1) single++
                else if (leftRunes > 1 && rightRunes > 1) multi++
            }
        }
        return single to multi
    }

    private fun find(word: String): Int {
        val key = word.toByteArray(Charsets.UTF_8)
        var lo = 0
        var hi = uni.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            val length = off[mid + 1] - off[mid]
            if (compareBytes(strs, off[mid], length, key) < 0) lo = mid + 1 else hi = mid
        }
        if (lo >= uni.size) return -1
        val length = off[lo + 1] - off[lo]
        return if (compareBytes(strs, off[lo], length, key) == 0) lo else -1
    }

    private fun rightAt(index: Int): Int {
        val at = index * 3
        return (right[at].toInt() and 0xFF) or
            ((right[at + 1].toInt() and 0xFF) shl 8) or
            ((right[at + 2].toInt() and 0xFF) shl 16)
    }

    private companion object {
        const val UNSEEN = 1e-8
        val Q_TABLE = DoubleArray(256) { byte -> 10.0.pow(-byte / 255.0 * 8.0) }
    }
}

internal class LingxiTables(
    val lexicon: LingxiLexicon,
    val lm: LingxiLm,
    val census: LingxiCensus,
)

internal fun loadLingxiTables(root: File): LingxiTables {
    val english = lingxiFile(root, "data/english/words.bin", "english/words.bin")
    require(english.isFile && english.length() > 0L) { "lingxi english subpack missing" }
    val englishWords = countEnglish(english.readBytes())
    require(englishWords > 0) { "lingxi english subpack empty" }
    val lexicon = decodeLexicon(lingxiFile(root, "data/lexicon.bin", "lexicon.bin").readBytes())
    val lm = decodeLm(lingxiFile(root, "data/lm.bin", "lm.bin").readBytes())
    require(lm.bigrams > 0) { "lingxi bigrams empty" }
    val (characters, words) = lexicon.census()
    val (single, multi) = lm.bigramClasses()
    val census = LingxiCensus(characters, words, lm.unigrams, multi, single, englishWords)
    census.requireFull()
    return LingxiTables(lexicon, lm, census)
}

internal fun lingxiFile(root: File, nested: String, flat: String): File {
    val primary = File(root, nested)
    return if (primary.isFile) primary else File(root, flat)
}

private const val LEX_MAGIC = "ZUINLEX\u0000"
private const val LM_MAGIC = "ZUINLM\u0000\u0000"
private val ENGLISH_MAGIC = byteArrayOf(0x30, 0x63, 0x45, 0x4E, 0x00, 0x01)

private class BinReader(private val data: ByteArray, private val limit: Int) {
    var pos = 0
    fun u32(): Int {
        val raw = take(4)
        return (raw[0].toInt() and 0xFF) or
            ((raw[1].toInt() and 0xFF) shl 8) or
            ((raw[2].toInt() and 0xFF) shl 16) or
            ((raw[3].toInt() and 0xFF) shl 24)
    }

    fun u16s(): ShortArray {
        val count = count(2)
        val out = ShortArray(count)
        for (index in 0 until count) {
            val at = pos + index * 2
            out[index] = ((data[at].toInt() and 0xFF) or ((data[at + 1].toInt() and 0xFF) shl 8)).toShort()
        }
        pos += count * 2
        return out
    }

    fun u32s(): IntArray {
        val count = count(4)
        val out = IntArray(count)
        for (index in 0 until count) out[index] = u32()
        return out
    }

    fun f32s(): FloatArray {
        val bits = u32s()
        return FloatArray(bits.size) { java.lang.Float.intBitsToFloat(bits[it]) }
    }

    fun bytes8(): ByteArray {
        val count = count(1)
        val out = data.copyOfRange(pos, pos + count)
        pos += count
        return out
    }

    fun done() {
        require(pos == limit) { "lingxi trailing bytes ${limit - pos}" }
    }

    private fun count(size: Int): Int {
        val n = u32()
        require(n >= 0) { "lingxi count overflow" }
        require(n.toLong() * size <= (limit - pos).toLong()) { "lingxi truncated" }
        return n
    }

    private fun take(n: Int): ByteArray {
        require(pos + n <= limit) { "lingxi truncated" }
        val out = data.copyOfRange(pos, pos + n)
        pos += n
        return out
    }
}

private fun openPack(bytes: ByteArray, magic: String, version: Int): BinReader {
    require(bytes.size >= magic.length + 8) { "lingxi short file" }
    require(String(bytes, 0, magic.length, Charsets.ISO_8859_1) == magic) { "lingxi magic" }
    val crc = CRC32()
    crc.update(bytes, 0, bytes.size - 4)
    val end = bytes.size
    val stored = (bytes[end - 4].toInt() and 0xFF) or
        ((bytes[end - 3].toInt() and 0xFF) shl 8) or
        ((bytes[end - 2].toInt() and 0xFF) shl 16) or
        ((bytes[end - 1].toInt() and 0xFF) shl 24)
    require(crc.value.toInt() == stored) { "lingxi crc" }
    val reader = BinReader(bytes, bytes.size - 4)
    reader.pos = magic.length
    val found = reader.u32()
    require(found == version) { "lingxi version $found" }
    return reader
}

private fun decodeLexicon(bytes: ByteArray): LingxiLexicon {
    val reader = openPack(bytes, LEX_MAGIC, 1)
    val syllableBytes = reader.bytes8()
    val rd = reader.u16s()
    val rdOff = reader.u32s()
    val sfOff = reader.u32s()
    val sfLenShort = reader.u16s()
    val rdLen = reader.bytes8()
    val flags = reader.bytes8()
    val prw = reader.f32s()
    val strs = reader.bytes8()
    val byText = reader.u32s()
    reader.done()
    val n = rdOff.size
    require(sfOff.size == n && sfLenShort.size == n && rdLen.size == n && flags.size == n && prw.size == n && byText.size == n) {
        "lingxi lexicon columns"
    }
    val sfLen = IntArray(n) { sfLenShort[it].toInt() and 0xFFFF }
    for (index in 0 until n) {
        val readingEnd = rdOff[index].toLong() + (rdLen[index].toInt() and 0xFF)
        require(readingEnd <= rd.size.toLong()) { "lingxi reading offset" }
        require(sfOff[index].toLong() + sfLen[index] <= strs.size.toLong()) { "lingxi surface offset" }
    }
    val syllables = String(syllableBytes, Charsets.UTF_8).split(' ').filter { it.isNotEmpty() }
    val ids = HashMap<String, Int>(syllables.size)
    syllables.forEachIndexed { index, syllable -> ids[syllable] = index }
    return LingxiLexicon(ids, rd, rdOff, sfOff, sfLen, rdLen, flags, prw, strs, byText)
}

private fun decodeLm(bytes: ByteArray): LingxiLm {
    val reader = openPack(bytes, LM_MAGIC, 2)
    val strs = reader.bytes8()
    val off = reader.u32s()
    val uni = reader.f32s()
    val leftStart = reader.u32s()
    val right = reader.bytes8()
    val q = reader.bytes8()
    reader.done()
    val n = uni.size
    require(off.size == n + 1 && leftStart.size == n + 1) { "lingxi lm columns" }
    require(right.size == 3 * q.size) { "lingxi bigram width" }
    require(leftStart[n] == q.size) { "lingxi bigram index" }
    return LingxiLm(strs, off, uni, leftStart, right, q)
}

private fun countEnglish(bytes: ByteArray): Int {
    require(bytes.size in 1..(16 shl 20)) { "lingxi english size" }
    require(bytes.size >= ENGLISH_MAGIC.size + 4) { "lingxi english header" }
    for (index in ENGLISH_MAGIC.indices) require(bytes[index] == ENGLISH_MAGIC[index]) { "lingxi english magic" }
    var pos = ENGLISH_MAGIC.size
    val count = (bytes[pos].toInt() and 0xFF) or
        ((bytes[pos + 1].toInt() and 0xFF) shl 8) or
        ((bytes[pos + 2].toInt() and 0xFF) shl 16) or
        ((bytes[pos + 3].toInt() and 0xFF) shl 24)
    pos += 4
    require(count in 1..200_000) { "lingxi english count" }
    var previous = ByteArray(0)
    repeat(count) {
        require(pos < bytes.size) { "lingxi english truncated" }
        val length = bytes[pos].toInt() and 0xFF
        pos++
        require(length in 1..255 && pos + length + 9 <= bytes.size) { "lingxi english word" }
        val text = bytes.copyOfRange(pos, pos + length)
        pos += length
        var bits = 0L
        repeat(8) { shift ->
            bits = bits or ((bytes[pos].toLong() and 0xFF) shl (shift * 8))
            pos++
        }
        val weight = Double.fromBits(bits)
        require(weight > 0.0 && weight.isFinite()) { "lingxi english weight" }
        val flag = bytes[pos].toInt() and 0xFF
        pos++
        require(flag <= 1) { "lingxi english flag" }
        require(compareBytes(previous, 0, previous.size, text) < 0) { "lingxi english order" }
        previous = text
    }
    require(pos == bytes.size) { "lingxi english trailing" }
    return count
}

private fun compareBytes(data: ByteArray, start: Int, length: Int, key: ByteArray): Int {
    val shared = minOf(length, key.size)
    for (index in 0 until shared) {
        val left = data[start + index].toInt() and 0xFF
        val right = key[index].toInt() and 0xFF
        if (left != right) return left - right
    }
    return length - key.size
}

internal fun utf8Runes(data: ByteArray, start: Int, length: Int): Int {
    var count = 0
    var index = start
    val end = start + length
    while (index < end) {
        val lead = data[index].toInt() and 0xFF
        index += when {
            lead < 0x80 -> 1
            lead and 0xE0 == 0xC0 -> 2
            lead and 0xF0 == 0xE0 -> 3
            else -> 4
        }
        count++
    }
    return count
}
