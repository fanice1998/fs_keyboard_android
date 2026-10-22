package com.fskeyboard.ime.candidate

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo

/**
 * What the candidate row can offer from the clipboard.
 * [label] is the short chip. [text] is the original paste, including newlines.
 * An image clip has no [text]; [label] is [IMAGE_LABEL].
 */
internal data class ClipOffer(
    val label: String,
    val text: String? = null,
    val imageUri: String? = null,
    val imageMime: String? = null,
)

/** Code points kept on the chip before the ellipsis. The paste is never cut. */
internal const val CLIP_LABEL_LIMIT = 18

internal const val IMAGE_LABEL = "圖片"

/**
 * Blank text is not a paste. Explicit text wins over an image so a content URI
 * is never shown as the chip. [coerced] is only for a clip that has neither.
 */
internal fun clipOffer(
    text: String?,
    imageUri: String? = null,
    imageMime: String? = null,
    coerced: String? = null,
): ClipOffer? {
    if (!text.isNullOrBlank()) {
        return ClipOffer(label = clipLabel(text), text = text)
    }
    if (!imageUri.isNullOrBlank() && !imageMime.isNullOrBlank()) {
        return ClipOffer(label = IMAGE_LABEL, imageUri = imageUri, imageMime = imageMime)
    }
    if (!coerced.isNullOrBlank()) {
        return ClipOffer(label = clipLabel(coerced), text = coerced)
    }
    return null
}

/** One line, trimmed, with whitespace collapsed. Longer text ends with an ellipsis. */
internal fun clipLabel(raw: String): String {
    val flat = buildString(raw.length) {
        var pendingSpace = false
        for (ch in raw.trim()) {
            if (ch.isWhitespace()) {
                pendingSpace = isNotEmpty()
            } else {
                if (pendingSpace) append(' ')
                pendingSpace = false
                append(ch)
            }
        }
    }
    if (flat.codePointCount(0, flat.length) <= CLIP_LABEL_LIMIT) return flat
    val end = flat.offsetByCodePoints(0, CLIP_LABEL_LIMIT)
    return flat.substring(0, end) + "…"
}

/**
 * Reads the current clip once. Failures, including a context with no clipboard
 * service, return null so opening a field still works.
 * Android 12 and later can show the system paste notice because this calls
 * [ClipboardManager.getPrimaryClip]. [ClipboardManager.hasPrimaryClip] does not.
 */
internal fun readClipboard(context: Context): ClipOffer? {
    return try {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return null
        if (!manager.hasPrimaryClip()) return null
        val clip = manager.primaryClip ?: return null
        if (clip.itemCount <= 0) return null
        val item = clip.getItemAt(0) ?: return null
        val image = imageMime(clip.description)
        val uri = if (image != null) item.uri?.toString() else null
        val coerced = if (item.text.isNullOrBlank() && image == null) {
            item.coerceToText(context)?.toString()
        } else {
            null
        }
        clipOffer(
            text = item.text?.toString(),
            imageUri = uri,
            imageMime = image,
            coerced = coerced,
        )
    } catch (_: Exception) {
        null
    }
}

internal fun mimeAccepted(accepted: Array<String>?, mime: String): Boolean {
    if (accepted.isNullOrEmpty()) return false
    val wanted = mime.lowercase()
    return accepted.any { pattern ->
        val rule = pattern.lowercase()
        rule == "*/*" || rule == wanted ||
            (rule.endsWith("/*") && wanted.startsWith(rule.removeSuffix("*")))
    }
}

/**
 * Inserts [offer] at the caret. Text is committed whole.
 * An image uses [InputConnection.commitContent] only when the field lists that type.
 */
internal fun pasteClip(connection: InputConnection, offer: ClipOffer, editor: EditorInfo?): Boolean {
    val text = offer.text
    if (text != null) {
        return try {
            connection.commitText(text, 1)
            true
        } catch (_: Exception) {
            false
        }
    }
    val uriText = offer.imageUri ?: return false
    val mime = offer.imageMime ?: return false
    if (!mimeAccepted(editor?.contentMimeTypes, mime)) return false
    return try {
        val info = InputContentInfo(
            Uri.parse(uriText),
            ClipDescription("clipboard", arrayOf(mime)),
        )
        connection.commitContent(
            info,
            InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION,
            null,
        )
    } catch (_: Exception) {
        false
    }
}

private fun imageMime(description: ClipDescription?): String? {
    if (description == null) return null
    for (index in 0 until description.mimeTypeCount) {
        val mime = description.getMimeType(index)
        if (mime.startsWith("image/", ignoreCase = true)) return mime
    }
    return null
}
