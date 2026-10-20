package com.fskeyboard.ime.keyboard

import kotlin.math.exp

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
}
