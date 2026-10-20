package com.fskeyboard.ime

import android.os.Trace

/**
 * A named slice in Perfetto/systrace. Near free when nobody is recording.
 * Sections: ime.dispatch, ime.lookup, ime.render, ime.setComposing, ime.contextBefore.
 */
internal inline fun <T> traced(name: String, block: () -> T): T {
    Trace.beginSection(name)
    try {
        return block()
    } finally {
        Trace.endSection()
    }
}
