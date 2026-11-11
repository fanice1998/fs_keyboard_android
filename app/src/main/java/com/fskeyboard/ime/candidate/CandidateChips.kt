package com.fskeyboard.ime.candidate

import android.graphics.drawable.GradientDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.TextView
import com.fskeyboard.ime.ui.KeyboardPalette
import com.fskeyboard.ime.ui.dp

/** The candidate button shared by the bar and the expanded panel. */
internal object CandidateChips {
    fun create(host: View): TextView {
        return TextView(host.context).apply {
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
        palette: KeyboardPalette,
        onClick: (Candidate) -> Unit,
        onLongClick: ((Candidate) -> Unit)? = null,
    ) {
        view.setTextColor(palette.text)
        view.text = styledLabel(candidate, palette)
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
        val fill = when {
            candidate.completion -> palette.background
            emphasized -> palette.accent
            else -> palette.key
        }
        (view.background as? GradientDrawable)?.setColor(fill)
    }

    fun labelOf(candidate: Candidate): String = candidate.visibleLabel()

    /** Reading stays in the chip color. The predicted tail uses [KeyboardPalette.hint]. */
    private fun styledLabel(candidate: Candidate, palette: KeyboardPalette): CharSequence {
        val reading = candidate.labelReading
        if (reading.isEmpty()) return labelOf(candidate)
        val label = reading + candidate.labelTail
        if (candidate.labelTail.isEmpty()) return label
        return SpannableString(label).apply {
            setSpan(
                ForegroundColorSpan(palette.hint),
                reading.length,
                label.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
    }

    fun rounded(host: View, color: Int): GradientDrawable {
        return GradientDrawable().apply {
            cornerRadius = host.dp(8).toFloat()
            setColor(color)
        }
    }
}
