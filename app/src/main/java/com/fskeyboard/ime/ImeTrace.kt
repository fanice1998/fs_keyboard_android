package com.fskeyboard.ime

import android.os.Trace

/**
 * A named slice in Perfetto/systrace. Near free when nobody is recording.
 * Sections: ime.dispatch, ime.lookup, ime.render, ime.setComposing, ime.contextBefore.
 */
internal inline fun <T> traced(name: String, block: () -> T): T {
    // The unit-test Android jar throws from Trace. A trace failure must not drop the key.
    val opened = try {
        Trace.beginSection(name)
        true
    } catch (_: RuntimeException) {
        false
    }
    try {
        return block()
    } finally {
        if (opened) {
            try {
                Trace.endSection()
            } catch (_: RuntimeException) {
                // The section was opened and then the process lost the tracer.
            }
        }
    }
}
