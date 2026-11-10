package com.fskeyboard.ime.lingxi

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.ByteBuffer
import java.nio.LongBuffer

internal class LingxiManifest(
    val weight: Double,
    val textWeight: Double,
    val neutralExpand: Boolean,
    val neutralBases: Set<String>,
    val neutralExact: Set<String>,
    val warmContext: String,
    val warmBase: String,
    val warmCandidates: List<String>,
)

internal interface LingxiScorer {
    val manifest: LingxiManifest
    fun score(context: String, reading: List<String>, candidates: List<String>, textOnly: Boolean): DoubleArray
    fun warm()
}

/**
 * On-device `0c-sequence-d512`. Scores only the candidates it is given.
 * One syllable with committed context uses the text head (no reading tokens).
 */
internal class LingxiSequence private constructor(
    override val manifest: LingxiManifest,
    private val session: OrtSession,
    private val charIds: Map<String, Int>,
    private val baseIds: Map<String, Int>,
) : LingxiScorer {
    override fun warm() {
        score(
            manifest.warmContext,
            listOf(manifest.warmBase, manifest.warmBase),
            manifest.warmCandidates,
            textOnly = false,
        )
    }

    override fun score(
        context: String,
        reading: List<String>,
        candidates: List<String>,
        textOnly: Boolean,
    ): DoubleArray {
        val prepared = prepare(context, reading, candidates, textOnly)
        val env = OrtEnvironment.getEnvironment()
        val feeds = LinkedHashMap<String, OnnxTensor>(9)
        try {
            val length = prepared.length.toLong()
            feeds["ids"] = longs(env, prepared.ids, longArrayOf(1, length))
            feeds["bases"] = longs(env, prepared.bases, longArrayOf(1, length))
            feeds["tones"] = longs(env, prepared.tones, longArrayOf(1, length))
            feeds["types"] = longs(env, prepared.types, longArrayOf(1, length))
            feeds["positions"] = longs(env, prepared.positions, longArrayOf(1, length))
            feeds["mask"] = bools(env, prepared.mask, longArrayOf(1, 1, length, length))
            feeds["proj_pos"] = longs(env, prepared.proj, longArrayOf(prepared.proj.size.toLong()))
            feeds["target_node"] = longs(
                env,
                prepared.targetNode,
                longArrayOf(prepared.rows.toLong(), prepared.syllables.toLong()),
            )
            feeds["target_ids"] = longs(
                env,
                prepared.targetIds,
                longArrayOf(prepared.rows.toLong(), prepared.syllables.toLong()),
            )
            session.run(feeds).use { result ->
                val named = result.get("scores")
                if (!named.isPresent) error("missing scores")
                val flat = flattenScores(named.get().value)
                val count = candidates.size
                if (flat.isEmpty() || flat.size % count != 0) error("score width ${flat.size}")
                val branches = flat.size / count
                val best = DoubleArray(count) { Double.NEGATIVE_INFINITY }
                for (branch in 0 until branches) {
                    for (index in 0 until count) {
                        val value = flat[branch * count + index]
                        if (!value.isFinite()) error("nonfinite score")
                        if (value > best[index]) best[index] = value
                    }
                }
                return best
            }
        } finally {
            feeds.values.forEach { runCatching { it.close() } }
        }
    }

    private fun prepare(
        context: String,
        reading: List<String>,
        candidates: List<String>,
        textOnly: Boolean,
    ): Prepared {
        val syllables = reading.size
        val encoded = candidates.map { text -> encodeChars(text, syllables) }
        val sounds = if (textOnly) emptyList() else reading.map { syllable(it) }
        val branches = ArrayList<List<Pair<Int, Int>>>()
        if (textOnly) {
            branches += listOf(0 to 0)
        } else {
            val last = sounds.last()
            branches += listOf(last, 0 to 0)
            if (neutralBranch(reading, textOnly) && last.second != 5) {
                branches += listOf(last.first to 5, 0 to 0)
            }
        }
        val ids = ArrayList<Int>()
        val bases = ArrayList<Int>()
        val tones = ArrayList<Int>()
        val types = ArrayList<Int>()
        val positions = ArrayList<Int>()
        fun push(id: Int, base: Int, tone: Int, type: Int, position: Int) {
            ids += id
            bases += base
            tones += tone
            types += type
            positions += position
        }
        push(1, 0, 0, 0, 0)
        val ctx = lastRunes(context, LINGXI_CONTEXT_RUNES)
        var offset = 0
        while (offset < ctx.length) {
            val point = ctx.codePointAt(offset)
            val token = String(Character.toChars(point))
            push(charIds[token] ?: UNK, 0, 0, 0, ids.size)
            offset += Character.charCount(point)
        }
        push(2, 0, 0, 0, ids.size)
        val shared = if (sounds.size <= 1) emptyList() else sounds.dropLast(1)
        for (sound in shared) push(0, sound.first, sound.second, 1, ids.size)
        val head = ids.size
        val parent = HashMap<Int, Int>()
        val nodes = ArrayList<HashMap<String, Int>>()
        for (branch in branches) {
            var prev: Int? = null
            for (index in branch.indices) {
                val (base, tone) = branch[index]
                val at = ids.size
                if (prev != null) parent[at] = prev
                prev = at
                val end = index == branch.lastIndex
                push(if (end) 3 else 0, base, tone, if (end) 0 else 1, head + index)
            }
            val origin = head + branch.size
            val node = HashMap<String, Int>()
            node[""] = prev ?: error("empty branch")
            for (chars in encoded) {
                for (taken in 1 until syllables) {
                    val key = chars.subList(0, taken).joinToString(",")
                    if (key in node) continue
                    val parentKey = if (taken == 1) "" else chars.subList(0, taken - 1).joinToString(",")
                    val at = ids.size
                    parent[at] = node[parentKey] ?: error("missing prefix")
                    node[key] = at
                    push(chars[taken - 1], 0, 0, 2, origin + taken - 1)
                }
            }
            nodes += node
        }
        val length = ids.size
        val mask = ByteArray(length * length)
        for (row in 0 until head) {
            for (column in 0..row) mask[row * length + column] = 1
        }
        for (row in head until length) {
            for (column in 0 until head) mask[row * length + column] = 1
            var cursor: Int? = row
            while (cursor != null) {
                mask[row * length + cursor] = 1
                cursor = parent[cursor]
            }
        }
        val proj = nodes.flatMap { it.values }.distinct().sorted()
        val indexOf = HashMap<Int, Int>(proj.size)
        proj.forEachIndexed { index, node -> indexOf[node] = index }
        val rows = nodes.size * encoded.size
        val targetNode = LongArray(rows * syllables)
        val targetIds = LongArray(rows * syllables)
        var row = 0
        for (node in nodes) {
            for (chars in encoded) {
                for (taken in 0 until syllables) {
                    val key = if (taken == 0) "" else chars.subList(0, taken).joinToString(",")
                    targetNode[row * syllables + taken] = (indexOf[node[key]] ?: error("target")).toLong()
                    targetIds[row * syllables + taken] = (chars[taken] - 6).toLong()
                }
                row++
            }
        }
        return Prepared(
            ids = ids.map { it.toLong() }.toLongArray(),
            bases = bases.map { it.toLong() }.toLongArray(),
            tones = tones.map { it.toLong() }.toLongArray(),
            types = types.map { it.toLong() }.toLongArray(),
            positions = positions.map { it.toLong() }.toLongArray(),
            mask = mask,
            length = length,
            proj = proj.map { it.toLong() }.toLongArray(),
            targetNode = targetNode,
            targetIds = targetIds,
            rows = rows,
            syllables = syllables,
        )
    }

    private fun encodeChars(text: String, syllables: Int): List<Int> {
        val ids = ArrayList<Int>(syllables)
        var offset = 0
        while (offset < text.length) {
            val point = text.codePointAt(offset)
            val token = String(Character.toChars(point))
            ids += charIds[token] ?: throw IllegalArgumentException("unknown_character")
            offset += Character.charCount(point)
        }
        if (ids.size != syllables) throw IllegalArgumentException("alignment")
        return ids
    }

    private fun syllable(reading: String): Pair<Int, Int> {
        val id = baseIds[baseOf(reading)] ?: throw IllegalArgumentException("unknown_base")
        return id to toneOf(reading)
    }

    private fun neutralBranch(reading: List<String>, textOnly: Boolean): Boolean {
        if (textOnly || reading.isEmpty()) return false
        val last = reading.last()
        if (!manifest.neutralExpand) return last in manifest.neutralExact
        if (last.endsWith("˙")) return false
        return baseOf(last) in manifest.neutralBases
    }

    companion object {
        private const val UNK = 4

        fun open(manifestFile: File, vocabFile: File, model: File): LingxiSequence {
            val manifestText = manifestFile.readText()
            val vocabText = vocabFile.readText()
            val dictionary = jsonString(manifestText, "dictionary_sha256")
            require(dictionary == jsonString(vocabText, "dictionary_sha256")) { "lingxi vocab dictionary" }
            val characters = jsonStringArray(vocabText, "characters")
            val bases = jsonStringArray(vocabText, "bases")
            val charIds = HashMap<String, Int>(characters.size)
            characters.forEachIndexed { index, token -> charIds[token] = index + 8 }
            val baseIds = HashMap<String, Int>(bases.size)
            bases.forEachIndexed { index, token -> baseIds[token] = index + 2 }
            val tolerant = jsonStringArray(manifestText, "neutral_tolerant_last").toSet()
            val manifest = LingxiManifest(
                weight = jsonNumber(manifestText, "weight"),
                textWeight = jsonNumber(manifestText, "text_weight"),
                neutralExpand = jsonNumber(manifestText, "neutral_expand_penalty") > 0.0,
                neutralBases = tolerant.map { baseOf(it) }.toSet(),
                neutralExact = tolerant,
                warmContext = characters.subList(8, 12).joinToString(""),
                warmBase = bases[0],
                warmCandidates = listOf(characters[8] + characters[9], characters[10] + characters[11]),
            )
            val options = OrtSession.SessionOptions()
            try {
                options.setIntraOpNumThreads(1)
                options.setInterOpNumThreads(1)
                options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
                options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                val session = OrtEnvironment.getEnvironment().createSession(model.absolutePath, options)
                return LingxiSequence(manifest, session, charIds, baseIds)
            } finally {
                options.close()
            }
        }
    }
}

private class Prepared(
    val ids: LongArray,
    val bases: LongArray,
    val tones: LongArray,
    val types: LongArray,
    val positions: LongArray,
    val mask: ByteArray,
    val length: Int,
    val proj: LongArray,
    val targetNode: LongArray,
    val targetIds: LongArray,
    val rows: Int,
    val syllables: Int,
)

private fun longs(env: OrtEnvironment, values: LongArray, shape: LongArray): OnnxTensor {
    return OnnxTensor.createTensor(env, LongBuffer.wrap(values), shape)
}

private fun bools(env: OrtEnvironment, values: ByteArray, shape: LongArray): OnnxTensor {
    val buffer = ByteBuffer.allocateDirect(values.size)
    buffer.put(values)
    buffer.flip()
    return OnnxTensor.createTensor(env, buffer, shape, OnnxJavaType.BOOL)
}

private fun flattenScores(value: Any?): DoubleArray {
    val out = ArrayList<Double>()
    fun walk(node: Any?) {
        when (node) {
            is FloatArray -> for (item in node) out += item.toDouble()
            is DoubleArray -> for (item in node) out += item
            is Array<*> -> for (item in node) walk(item)
            else -> error("score type ${node?.javaClass?.name}")
        }
    }
    walk(value)
    return out.toDoubleArray()
}

private fun baseOf(reading: String): String {
    val last = reading.lastOrNull() ?: return reading
    return if (last == 'ˊ' || last == 'ˇ' || last == 'ˋ' || last == '˙') reading.dropLast(1) else reading
}

private fun toneOf(reading: String): Int {
    return when (reading.lastOrNull()) {
        'ˊ' -> 2
        'ˇ' -> 3
        'ˋ' -> 4
        '˙' -> 5
        else -> 1
    }
}

private fun lastRunes(text: String, limit: Int): String {
    val count = text.codePointCount(0, text.length)
    if (count <= limit) return text
    return text.substring(text.offsetByCodePoints(0, count - limit))
}

private class JsonScan(val text: String, var index: Int) {
    fun skip() {
        while (index < text.length && text[index].isWhitespace()) index++
    }

    fun string(): String {
        skip()
        require(text[index] == '"')
        index++
        val out = StringBuilder()
        while (index < text.length) {
            val char = text[index++]
            if (char == '"') return out.toString()
            if (char != '\\') {
                out.append(char)
                continue
            }
            when (val escaped = text[index++]) {
                '"', '\\', '/' -> out.append(escaped)
                'b' -> out.append('\b')
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                't' -> out.append('\t')
                'u' -> {
                    out.append(text.substring(index, index + 4).toInt(16).toChar())
                    index += 4
                }
                else -> error("bad json escape")
            }
        }
        error("unterminated json string")
    }
}

private fun fieldAt(json: String, key: String): Int {
    val marker = "\"$key\""
    val at = json.indexOf(marker)
    require(at >= 0) { "missing $key" }
    var index = at + marker.length
    while (index < json.length && (json[index].isWhitespace() || json[index] == ':')) index++
    return index
}

private fun jsonString(json: String, key: String): String = JsonScan(json, fieldAt(json, key)).string()

private fun jsonNumber(json: String, key: String): Double {
    var index = fieldAt(json, key)
    val start = index
    while (index < json.length && (json[index].isDigit() || json[index] == '.' || json[index] == '-' || json[index] == 'e' || json[index] == 'E' || json[index] == '+')) {
        index++
    }
    return json.substring(start, index).toDouble()
}

private fun jsonStringArray(json: String, key: String): List<String> {
    var index = fieldAt(json, key)
    require(json[index] == '[')
    index++
    val scan = JsonScan(json, index)
    val out = ArrayList<String>()
    while (scan.index < json.length) {
        scan.skip()
        if (json[scan.index] == ']') break
        if (json[scan.index] == ',') {
            scan.index++
            continue
        }
        out += scan.string()
    }
    return out
}

