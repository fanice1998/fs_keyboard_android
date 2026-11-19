package com.fskeyboard.ime.keyboard

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot

/** A zhuyin key the finger may have meant, with its share of the touch probability. */
internal data class NearbyKey(val label: String, val probability: Float)

/** Where a zhuyin key sits on the keyboard, for [TouchModel]. */
internal data class KeyArea(
    val label: String,
    val centerX: Float,
    val centerY: Float,
    val width: Float,
    val height: Float,
)

/**
 * Which zhuyin keys a touch may have meant. Each key scores a 2D Gaussian around its
 * center, spread over [SPREAD] of its width and height, and the scores are normalised.
 * A touch near a key's center gives that key almost all of the probability; a touch on
 * the border between two keys splits it between them.
 */
internal object TouchModel {
    const val SPREAD = 1f / 3f
    const val MIN_PROBABILITY = 0.05f
    const val MAX_KEYS = 3

    /**
     * A neighbour may sit two rows away, the way ㄠ sits under ㄚ, but not two keys
     * to the side. Distances are in the touched key's own width and height.
     */
    const val NEIGHBOR_PITCH_X = 1.75f
    const val NEIGHBOR_PITCH_Y = 2.05f

    /** How far past a cell, in that cell's width and height, a touch can still land. */
    const val SENSE_GROW = 0.45f

    /** A cell keeps its touch until another centre is closer than this fraction of its width. */
    const val SENSE_BIAS = 0.08f

    fun nearby(x: Float, y: Float, keys: List<KeyArea>): List<NearbyKey> {
        val scored = keys.map { key ->
            val dx = (x - key.centerX) / (key.width * SPREAD)
            val dy = (y - key.centerY) / (key.height * SPREAD)
            key.label to exp(-(dx * dx + dy * dy) / 2.0)
        }
        val total = scored.sumOf { it.second }
        if (total <= 0.0) return emptyList()
        return scored
            .map { (label, score) -> NearbyKey(label, (score / total).toFloat()) }
            .filter { it.probability >= MIN_PROBABILITY }
            .sortedByDescending { it.probability }
            .take(MAX_KEYS)
    }

    /**
     * Keys one step away from [label], closest first.
     * A touch on a key's centre keeps only that key in [nearby]; this list is the rest.
     */
    fun adjacent(label: String, keys: List<KeyArea>): List<String> {
        val key = keys.firstOrNull { it.label == label } ?: return emptyList()
        if (key.width <= 0f || key.height <= 0f) return emptyList()
        val found = ArrayList<Pair<String, Float>>()
        for (other in keys) {
            if (other.label.isEmpty() || other.label == label) continue
            val dx = abs(other.centerX - key.centerX) / key.width
            val dy = abs(other.centerY - key.centerY) / key.height
            if (dx > NEIGHBOR_PITCH_X || dy > NEIGHBOR_PITCH_Y) continue
            found += other.label to hypot(dx.toDouble(), dy.toDouble()).toFloat()
        }
        found.sortBy { it.second }
        return found.map { it.first }
    }

    /**
     * [likely] stays the touch model. Keys it dropped, because the finger was on one key,
     * are appended at probability 0 so a failed reading can try them without looking like a border split.
     */
    fun withAdjacent(likely: List<NearbyKey>, label: String, keys: List<KeyArea>): List<NearbyKey> {
        if (label.isEmpty()) return likely
        val seen = HashSet<String>(likely.size + 8)
        val out = ArrayList<NearbyKey>(likely.size + 8)
        for (near in likely) {
            if (seen.add(near.label)) out += near
        }
        for (name in adjacent(label, keys)) {
            if (seen.add(name)) out += NearbyKey(name, 0f)
        }
        return out
    }

    /**
     * The bopomofo a finger lands on. The target grows past the cell by [SENSE_GROW],
     * and a cell shrinks when another centre is closer than [SENSE_BIAS].
     * [owner] is the rectangular hit, kept when nothing else is clearly closer,
     * including a staggered blank that the rectangle already claimed.
     */
    fun sense(x: Float, y: Float, keys: List<KeyArea>, owner: String? = null): String? {
        var best: KeyArea? = null
        var bestDist = Float.MAX_VALUE
        var ownerArea: KeyArea? = null
        var ownerDist = Float.MAX_VALUE
        for (key in keys) {
            if (key.label.isEmpty() || key.width <= 0f || key.height <= 0f) continue
            val dx = abs(x - key.centerX)
            val dy = abs(y - key.centerY)
            val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
            if (key.label == owner) {
                ownerArea = key
                ownerDist = dist
            }
            val grownW = key.width * (0.5f + SENSE_GROW)
            val grownH = key.height * (0.5f + SENSE_GROW)
            if (dx > grownW || dy > grownH) continue
            if (dist < bestDist) {
                bestDist = dist
                best = key
            }
        }
        val found = best ?: ownerArea ?: return null
        val held = ownerArea
        if (held != null && found.label != held.label && ownerDist <= bestDist + held.width * SENSE_BIAS) {
            return held.label
        }
        return found.label
    }
}
