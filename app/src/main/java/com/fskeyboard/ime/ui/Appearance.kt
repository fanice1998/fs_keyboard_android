package com.fskeyboard.ime.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import java.io.File
import kotlin.math.max
import kotlin.math.min

internal enum class KeyAnimation {
    NONE, POP, RIPPLE, FLASH, SINK;

    val durationMs: Int
        get() = when (this) {
            NONE -> 0
            POP -> 120
            RIPPLE -> 160
            FLASH -> 80
            SINK -> 100
        }

    fun stored(): String = when (this) {
        NONE -> "none"
        POP -> "pop"
        RIPPLE -> "ripple"
        FLASH -> "flash"
        SINK -> "sink"
    }

    companion object {
        fun of(stored: String?): KeyAnimation = when (stored) {
            "pop" -> POP
            "ripple" -> RIPPLE
            "flash" -> FLASH
            "sink" -> SINK
            else -> NONE
        }
    }
}

/** Absent is null. null is not black, and it is not 0. */
internal data class AppearanceOverrides(
    val backgroundColor: Int?,
    val keyFill: Int?,
    val keyBorderEnabled: Boolean?,
    val keyBorderColor: Int?,
    val keyLabelColor: Int?,
    val alphaPercent: Int?,
    val animation: KeyAnimation?,
    val imageName: String?,
    val candidateBar: Int?,
    val candidateSelected: Int?,
    val candidateUnselected: Int?,
    val candidateText: Int?,
) {
    companion object {
        val NONE = AppearanceOverrides(
            backgroundColor = null,
            keyFill = null,
            keyBorderEnabled = null,
            keyBorderColor = null,
            keyLabelColor = null,
            alphaPercent = null,
            animation = null,
            imageName = null,
            candidateBar = null,
            candidateSelected = null,
            candidateUnselected = null,
            candidateText = null,
        )
    }
}

internal data class ResolvedKeyboard(
    val palette: KeyboardPalette,
    val drawBorder: Boolean,
    val borderDp: Int,
    val borderColor: Int,
    val labelColor: Int,
    /** False keeps the space label on the theme hint. A stored label uses [labelColor] there too. */
    val labelOverridden: Boolean,
    val alphaPercent: Int,
    val animation: KeyAnimation,
    val imageFile: File?,
)

internal data class CandidateChrome(
    val bar: Int,
    val selected: Int,
    val unselected: Int,
    val text: Int,
    /** Derived from [bar] and [unselected]. Not a preference. */
    val prediction: Int,
    /** Finger-down fill on the strip. The expanded panel does not use it. */
    val pressed: Int,
    val hint: Int,
) {
    companion object {
        fun resolve(night: Boolean, overrides: AppearanceOverrides): CandidateChrome {
            val base = KeyboardPalette.of(night)
            val bar = opaque(overrides.candidateBar) ?: base.background
            val selected = opaque(overrides.candidateSelected) ?: base.accent
            val unselected = opaque(overrides.candidateUnselected) ?: base.key
            val text = opaque(overrides.candidateText) ?: base.text
            val pressed = if (
                overrides.candidateSelected == null &&
                overrides.candidateUnselected == null &&
                overrides.keyFill == null
            ) {
                base.keyPressed
            } else {
                pressedFill(unselected)
            }
            return CandidateChrome(
                bar = bar,
                selected = selected,
                unselected = unselected,
                text = text,
                prediction = predictionOf(bar, unselected, base),
                pressed = pressed,
                hint = base.hint,
            )
        }
    }
}

internal const val APPEARANCE_IMAGE_FILE = "background.webp"
internal const val APPEARANCE_MAX_EDGE = 1280
internal const val APPEARANCE_MAX_BYTES = 25L * 1024L * 1024L
internal const val APPEARANCE_DECODED_BYTES = 1280L * 1280L * 4L

internal fun opaque(color: Int?): Int? = color?.let { it or 0xFF000000.toInt() }

/** Absent reads as 100. A stored 0 stays 0. Out of range is clamped, not rewritten to 100. */
internal fun readStoredAlpha(present: Boolean, raw: Int): Int? {
    if (!present) return null
    return raw.coerceIn(0, 100)
}

internal fun acceptedImageName(name: String?): String? {
    if (name == null || name.isEmpty()) return null
    if (name != APPEARANCE_IMAGE_FILE) return null
    return name
}

internal fun resolveKeyboard(
    theme: KeyboardTheme,
    night: Boolean,
    overrides: AppearanceOverrides,
    appearanceDir: File?,
): ResolvedKeyboard {
    val base = theme.palette(night)
    val fill = opaque(overrides.keyFill)
    val key = fill ?: base.key
    val palette = base.copy(
        background = opaque(overrides.backgroundColor) ?: base.background,
        key = key,
        keyAction = if (fill != null) key else base.keyAction,
        keyPressed = if (fill != null) pressedFill(key) else base.keyPressed,
    )
    val drawBorder = overrides.keyBorderEnabled ?: (theme.borderDp > 0)
    val borderDp = when {
        !drawBorder -> 0
        theme.borderDp > 0 -> theme.borderDp
        else -> 1
    }
    val name = acceptedImageName(overrides.imageName)
    val image = if (name != null && appearanceDir != null) File(appearanceDir, name) else null
    return ResolvedKeyboard(
        palette = palette,
        drawBorder = drawBorder,
        borderDp = borderDp,
        borderColor = opaque(overrides.keyBorderColor) ?: base.hint,
        labelColor = opaque(overrides.keyLabelColor) ?: base.text,
        labelOverridden = overrides.keyLabelColor != null,
        alphaPercent = overrides.alphaPercent ?: 100,
        animation = overrides.animation ?: KeyAnimation.NONE,
        imageFile = image,
    )
}

/**
 * The computer-key editor stays on paper until a key face, border, label, or animation
 * key exists. Keyboard background, alpha, and the photo are not applied there.
 */
internal fun editorResolved(overrides: AppearanceOverrides, night: Boolean): ResolvedKeyboard? {
    val touched = overrides.keyFill != null ||
        overrides.keyBorderEnabled != null ||
        overrides.keyBorderColor != null ||
        overrides.keyLabelColor != null ||
        overrides.animation != null
    if (!touched) return null
    val paper = overrides.copy(
        backgroundColor = null,
        alphaPercent = null,
        imageName = null,
        candidateBar = null,
        candidateSelected = null,
        candidateUnselected = null,
        candidateText = null,
    )
    return resolveKeyboard(KeyboardTheme.paper(), night, paper, appearanceDir = null)
}

internal fun predictionOf(bar: Int, unselected: Int, nightBase: KeyboardPalette): Int {
    return nightBase.copy(background = bar, key = unselected).predictionFill()
}

/** One fill formula for the strip and the expanded panel. Pressed is the strip's own case. */
internal fun candidateFaceFill(
    chrome: CandidateChrome,
    completion: Boolean,
    clipboard: Boolean,
    emphasized: Boolean,
): Int = when {
    completion -> chrome.prediction
    clipboard -> chrome.bar
    emphasized -> chrome.selected
    else -> chrome.unselected
}

internal fun luminance(color: Int): Double {
    fun lin(channel: Int): Double {
        val s = channel / 255.0
        return if (s <= 0.04045) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
    }
    val r = lin((color ushr 16) and 0xFF)
    val g = lin((color ushr 8) and 0xFF)
    val b = lin(color and 0xFF)
    return 0.2126 * r + 0.7152 * g + 0.0722 * b
}

internal fun contrastRatio(a: Int, b: Int): Double {
    val hi = max(luminance(a), luminance(b))
    val lo = min(luminance(a), luminance(b))
    return (hi + 0.05) / (lo + 0.05)
}

/** Same or nearly the same. Not WCAG AA, and not a reason to refuse the stored color. */
internal fun colorsMatch(a: Int, b: Int): Boolean {
    val dr = kotlin.math.abs(((a ushr 16) and 0xFF) - ((b ushr 16) and 0xFF))
    val dg = kotlin.math.abs(((a ushr 8) and 0xFF) - ((b ushr 8) and 0xFF))
    val db = kotlin.math.abs((a and 0xFF) - (b and 0xFF))
    return maxOf(dr, dg, db) <= 16 || contrastRatio(a, b) < 1.4
}

internal fun haloColor(behind: Int): Int {
    return if (luminance(behind) >= 0.5) 0xFF1C1C1E.toInt() else 0xFFF2F2F7.toInt()
}

internal fun mix(from: Int, to: Int, fraction: Float): Int {
    fun channel(shift: Int): Int {
        val start = (from ushr shift) and 0xFF
        val end = (to ushr shift) and 0xFF
        return (start + (end - start) * fraction).toInt().coerceIn(0, 255)
    }
    return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
}

internal fun pressedFill(fill: Int): Int {
    val toward = if (luminance(fill) >= 0.5) 0xFF1C1C1E.toInt() else 0xFFF2F2F7.toInt()
    return mix(fill, toward, 0.24f) or 0xFF000000.toInt()
}

internal fun colorWithAlpha(color: Int, alphaPercent: Int): Int {
    val alpha = (alphaPercent.coerceIn(0, 100) * 255) / 100
    return (color and 0x00FFFFFF) or (alpha shl 24)
}

internal fun smoothstep(t: Float): Float {
    val x = t.coerceIn(0f, 1f)
    return x * x * (3f - 2f * x)
}

/** 0 at both ends and 1 at t = 0.5. Both halves are smoothstep. */
internal fun pulse(t: Float): Float {
    val x = t.coerceIn(0f, 1f)
    return if (x <= 0.5f) smoothstep(x / 0.5f) else smoothstep((1f - x) / 0.5f)
}

internal fun appearanceSampleSize(width: Int, height: Int, maxEdge: Int = APPEARANCE_MAX_EDGE): Int {
    if (width <= 0 || height <= 0 || maxEdge <= 0) return 1
    var sample = 1
    while (width / sample > maxEdge || height / sample > maxEdge) {
        if (sample > Int.MAX_VALUE / 2) return sample
        sample *= 2
    }
    return sample
}

/** True when the sampled buffer would still exceed 1280×1280×4, or the product overflows. */
internal fun sampledBufferTooLarge(width: Int, height: Int, sample: Int): Boolean {
    if (width <= 0 || height <= 0 || sample <= 0) return true
    val w = width.toLong() / sample
    val h = height.toLong() / sample
    if (w <= 0L || h <= 0L) return false
    if (w > Long.MAX_VALUE / h) return true
    val pixels = w * h
    if (pixels > Long.MAX_VALUE / 4L) return true
    return pixels * 4L > APPEARANCE_DECODED_BYTES
}

internal fun parseHexColor(raw: String): Int? {
    val text = raw.trim().removePrefix("#")
    if (text.length != 6) return null
    return text.toIntOrNull(16)?.or(0xFF000000.toInt())
}

internal fun appearanceSwatches(): List<Int> {
    val fromThemes = LinkedHashSet<Int>()
    for (theme in listOf(KeyboardTheme.paper(), KeyboardTheme.ink())) {
        for (palette in listOf(theme.light, theme.dark)) {
            fromThemes += palette.background
            fromThemes += palette.key
            fromThemes += palette.keyPressed
            fromThemes += palette.keyAction
            fromThemes += palette.text
            fromThemes += palette.hint
            fromThemes += palette.accent
            fromThemes += palette.keyPreview
        }
    }
    val extra = intArrayOf(
        0xFFB42318.toInt(),
        0xFFB45309.toInt(),
        0xFF0F766E.toInt(),
        0xFF1D4ED8.toInt(),
        0xFF6D28D9.toInt(),
        0xFF111827.toInt(),
        0xFFF9FAFB.toInt(),
        0xFFF59E0B.toInt(),
    )
    return fromThemes.toList() + extra.toList()
}

internal fun drawHaloText(
    canvas: Canvas,
    text: String,
    x: Float,
    y: Float,
    paint: Paint,
    color: Int,
    behind: Int,
    density: Float,
) {
    if (!colorsMatch(color, behind)) {
        paint.color = color
        paint.style = Paint.Style.FILL
        canvas.drawText(text, x, y, paint)
        return
    }
    val savedStyle = paint.style
    val savedWidth = paint.strokeWidth
    val savedJoin = paint.strokeJoin
    paint.style = Paint.Style.STROKE
    paint.strokeJoin = Paint.Join.ROUND
    paint.strokeWidth = 3f * density
    paint.color = haloColor(behind)
    canvas.drawText(text, x, y, paint)
    paint.style = Paint.Style.FILL
    paint.strokeWidth = 0f
    paint.color = color
    canvas.drawText(text, x, y, paint)
    paint.style = savedStyle
    paint.strokeWidth = savedWidth
    paint.strokeJoin = savedJoin
}

/** Fills only the root's left and right padding. The content rect stays empty. */
internal class InsetBandDrawable : Drawable() {
    var color: Int = Color.TRANSPARENT
    var padLeft: Int = 0
    var padRight: Int = 0
    private val paint = Paint()

    override fun draw(canvas: Canvas) {
        paint.color = color
        val b = bounds
        if (padLeft > 0) {
            canvas.drawRect(
                b.left.toFloat(),
                b.top.toFloat(),
                (b.left + padLeft).toFloat(),
                b.bottom.toFloat(),
                paint,
            )
        }
        if (padRight > 0) {
            canvas.drawRect(
                (b.right - padRight).toFloat(),
                b.top.toFloat(),
                b.right.toFloat(),
                b.bottom.toFloat(),
                paint,
            )
        }
    }

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

internal enum class RecoveryAction { IGNORE, DELETE, REENCODE }

internal fun recoveryAction(inActive: Boolean, abandoned: Boolean, ok: Boolean): RecoveryAction {
    if (inActive) return RecoveryAction.IGNORE
    if (abandoned || !ok) return RecoveryAction.DELETE
    return RecoveryAction.REENCODE
}

internal fun appearanceGeneration(name: String): Long? {
    val body = when {
        name.startsWith("incoming-") -> name.removePrefix("incoming-").removeSuffix(".ok")
        name.startsWith("encoded-") -> name.removePrefix("encoded-").removeSuffix(".webp")
        name.startsWith("abandoned-") -> name.removePrefix("abandoned-")
        else -> return null
    }
    if (body.isEmpty() || body.any { it !in '0'..'9' }) return null
    return body.toLongOrNull()?.takeIf { it > 0L }
}
