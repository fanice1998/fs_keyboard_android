package com.fskeyboard.ime.candidate

import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
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
        view.text = labelOf(candidate)
        view.contentDescription = candidate.text
        view.setTextColor(palette.text)
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
        (view.background as? GradientDrawable)?.setColor(
            if (emphasized) palette.accent else palette.key,
        )
    }

    fun labelOf(candidate: Candidate): String {
        return if (candidate.comment.isEmpty()) candidate.text else "${candidate.text} ${candidate.comment}"
    }

    fun rounded(host: View, color: Int): GradientDrawable {
        return GradientDrawable().apply {
            cornerRadius = host.dp(8).toFloat()
            setColor(color)
        }
    }
}
