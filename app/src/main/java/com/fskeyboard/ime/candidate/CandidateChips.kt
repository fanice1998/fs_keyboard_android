package com.fskeyboard.ime.candidate

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.TextView
import com.fskeyboard.ime.ui.CandidateChrome
import com.fskeyboard.ime.ui.candidateFaceFill
import com.fskeyboard.ime.ui.colorsMatch
import com.fskeyboard.ime.ui.dp
import com.fskeyboard.ime.ui.haloColor

/** The candidate button shared by the bar and the expanded panel. */
internal object CandidateChips {
    fun create(host: View): TextView {
        return HaloChip(host.context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(host.dp(8), 0, host.dp(8), 0)
            background = rounded(host, 0)
        }
    }

    fun bind(
        view: TextView,
        candidate: Candidate,
        emphasized: Boolean,
        chrome: CandidateChrome,
        onClick: (Candidate) -> Unit,
        onLongClick: ((Candidate) -> Unit)? = null,
    ) {
        val fill = candidateFaceFill(
            chrome,
            completion = candidate.completion,
            clipboard = candidate.source == CandidateSource.CLIPBOARD,
            emphasized = emphasized,
        )
        view.setTextColor(chrome.text)
        if (view is HaloChip) view.chipFill = fill
        view.text = labelOf(candidate)
        view.contentDescription = candidate.text
        view.setOnClickListener { onClick(candidate) }
        if (onLongClick == null || candidate.source == CandidateSource.CLIPBOARD) {
            view.setOnLongClickListener(null)
            view.isLongClickable = false
        } else {
            view.isLongClickable = true
            view.setOnLongClickListener {
                onLongClick(candidate)
                true
            }
        }
        (view.background as? GradientDrawable)?.setColor(fill)
    }

    fun labelOf(candidate: Candidate): String = candidate.visibleLabel()

    fun rounded(host: View, color: Int): GradientDrawable {
        return GradientDrawable().apply {
            cornerRadius = host.dp(8).toFloat()
            setColor(color)
        }
    }
}

/** Strokes the label, then fills it, when the chip face and the text are too close. */
private class HaloChip(context: Context) : TextView(context) {
    var chipFill: Int = 0

    override fun onDraw(canvas: Canvas) {
        val user = currentTextColor
        val textLayout = layout
        if (textLayout == null || !colorsMatch(user, chipFill)) {
            super.onDraw(canvas)
            return
        }
        // TextView.onDraw paints with the current text color, so the halo has to be drawn here.
        val paint = paint
        val savedStyle = paint.style
        val savedWidth = paint.strokeWidth
        val savedJoin = paint.strokeJoin
        val savedColor = paint.color
        drawLabel(canvas, textLayout, haloColor(chipFill), stroke = true)
        drawLabel(canvas, textLayout, user, stroke = false)
        paint.style = savedStyle
        paint.strokeWidth = savedWidth
        paint.strokeJoin = savedJoin
        paint.color = savedColor
    }

    private fun drawLabel(canvas: Canvas, textLayout: android.text.Layout, color: Int, stroke: Boolean) {
        val paint = paint
        paint.color = color
        if (stroke) {
            paint.style = Paint.Style.STROKE
            paint.strokeJoin = Paint.Join.ROUND
            paint.strokeWidth = 3f * resources.displayMetrics.density
        } else {
            paint.style = Paint.Style.FILL
            paint.strokeWidth = 0f
        }
        val inner = height - extendedPaddingTop - extendedPaddingBottom
        val dy = extendedPaddingTop + (inner - textLayout.height) / 2f
        canvas.save()
        canvas.translate(compoundPaddingLeft.toFloat(), dy)
        textLayout.draw(canvas)
        canvas.restore()
    }
}
