package com.fskeyboard.ime.engine

import com.fskeyboard.ime.bridge.RimeResponse
import com.fskeyboard.ime.candidate.Candidate

internal fun RimeResponse.toSnapshot(): EngineSnapshot {
    val parsed = ArrayList<Candidate>(candidates.size)
    for (i in candidates.indices) {
        val text = candidates[i]
        if (text.isEmpty()) continue
        parsed += Candidate(
            text = text,
            comment = comments.getOrNull(i).orEmpty(),
            engineIndex = indices.getOrElse(i) { i },
        )
    }
    return EngineSnapshot(
        consumed = consumed,
        commit = commit.takeIf { it.isNotEmpty() },
        preedit = preedit,
        composingPreview = preview,
        candidates = parsed,
        highlighted = highlighted,
    )
}
