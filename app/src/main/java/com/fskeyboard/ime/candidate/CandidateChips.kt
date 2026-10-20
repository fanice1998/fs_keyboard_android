package com.fskeyboard.ime.candidate

import android.graphics.drawable.GradientDrawable
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
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity = Gravity.CENTER
            setPadding(host.dp(12), host.dp(4), host.dp(12), host.dp(4))
            background = rounded(host, 0)
        }
    }

    fun bind(
        view: TextView,
        candidate: Candidate,
        emphasized: Boolean,
        palette: KeyboardPalette,
        onClick: (Candidate) -> Unit,
    ) {
        view.text = if (candidate.comment.isEmpty()) {
            candidate.text
        } else {
            "${candidate.text} ${candidate.comment}"
        }
        view.contentDescription = candidate.text
        view.setTextColor(palette.text)
        view.setOnClickListener { onClick(candidate) }
        (view.background as? GradientDrawable)?.setColor(
            if (emphasized) palette.accent else palette.key,
        )
    }

    fun rounded(host: View, color: Int): GradientDrawable {
        return GradientDrawable().apply {
            cornerRadius = host.dp(8).toFloat()
            setColor(color)
        }
    }
}
