package com.fskeyboard.ime.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.fskeyboard.ime.R
import com.fskeyboard.ime.keyboard.KeyboardView
import com.fskeyboard.ime.settings.KeyboardPreferences
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicBoolean

internal fun appearanceDir(context: Context): File = File(context.filesDir, "appearance")

internal fun loadAppearance(context: Context): AppearanceOverrides {
    val preferences = KeyboardPreferences(context)
    val overrides = preferences.appearance()
    val name = overrides.imageName ?: return overrides
    val file = File(appearanceDir(context), name)
    if (file.isFile) return overrides
    preferences.setBackgroundImageName(null)
    return overrides.copy(imageName = null)
}

/**
 * One published RGB_565 for the preview keyboard and the IME keyboard.
 * The editor is not a holder. Recycle happens on the main thread, one frame
 * after every holder has dropped the old reference.
 */
internal object AppearanceImageCache {
    private class Holder(val view: KeyboardView, var width: Int, var height: Int)

    private val holders = ArrayList<Holder>()
    private var current: Bitmap? = null
    private var currentMtime = Long.MIN_VALUE
    private var source: File? = null
    /** Long edge of the last file decode, before it was fitted to a view. */
    private var sourceLongEdge = 0

    @Volatile
    private var fitToken = 0
    private var fittedEdge = 0

    fun attach(view: KeyboardView) {
        val existing = holders.firstOrNull { it.view === view }
        if (existing == null) {
            holders += Holder(view, view.width, view.height)
        } else {
            existing.width = view.width
            existing.height = view.height
        }
        view.setBackgroundBitmap(current, currentMtime)
        refitIfNeeded()
    }

    fun detach(view: KeyboardView) {
        holders.removeAll { it.view === view }
        view.setBackgroundBitmap(null, Long.MIN_VALUE)
        refitIfNeeded()
    }

    fun holds(file: File, mtime: Long): Boolean {
        val bitmap = current
        return bitmap != null &&
            !bitmap.isRecycled &&
            currentMtime == mtime &&
            source?.absolutePath == file.absolutePath
    }

    fun publish(
        bitmap: Bitmap,
        file: File,
        mtime: Long,
        sourceLongEdge: Int = maxOf(bitmap.width, bitmap.height),
        fittedEdge: Int? = null,
    ) {
        val old = current
        current = bitmap
        currentMtime = mtime
        source = file
        this.sourceLongEdge = sourceLongEdge
        for (holder in holders) holder.view.setBackgroundBitmap(bitmap, mtime)
        if (old != null && old !== bitmap) recycleLater(old)
        val edge = displayEdge()
        // Same requested edge means this result is the fit we just asked for.
        if (fittedEdge != edge && !covers(edge)) AppearancePipeline.rescale(file, edge, mtime)
    }

    fun clear() {
        fitToken += 1
        val old = current
        current = null
        currentMtime = Long.MIN_VALUE
        source = null
        sourceLongEdge = 0
        for (holder in holders) holder.view.setBackgroundBitmap(null, Long.MIN_VALUE)
        if (old != null) recycleLater(old)
    }

    /** The on-screen bitmap may already be smaller than the file, so growth decodes again. */
    fun noteSize(view: KeyboardView, width: Int, height: Int) {
        val holder = holders.firstOrNull { it.view === view } ?: return
        if (holder.width == width && holder.height == height) return
        holder.width = width
        holder.height = height
        refitIfNeeded()
    }

    fun displayEdge(): Int {
        var edge = 0
        for (holder in holders) edge = maxOf(edge, holder.width, holder.height)
        if (edge <= 0) return APPEARANCE_MAX_EDGE
        return edge.coerceAtMost(APPEARANCE_MAX_EDGE)
    }

    /** True when the cache is neither larger than [edge] nor a shrink of a bigger file. */
    fun covers(edge: Int): Boolean {
        val bitmap = current ?: return false
        if (bitmap.isRecycled) return false
        val shown = maxOf(bitmap.width, bitmap.height)
        if (shown > edge) return false
        if (sourceLongEdge > shown && shown < edge) return false
        return true
    }

    fun beginFit(edge: Int): Int {
        fittedEdge = edge
        fitToken += 1
        return fitToken
    }

    fun fitStillCurrent(token: Int, edge: Int): Boolean = token == fitToken && edge == fittedEdge

    private fun refitIfNeeded() {
        val file = source ?: return
        if (current == null) return
        val edge = displayEdge()
        if (covers(edge)) return
        AppearancePipeline.rescale(file, edge, currentMtime)
    }

    private fun recycleLater(old: Bitmap) {
        val host = holders.firstOrNull()?.view
        val recycle = Runnable { if (!old.isRecycled) old.recycle() }
        if (host != null) {
            host.post { host.post(recycle) }
        } else {
            main.post { main.post(recycle) }
        }
    }

    private val main = Handler(Looper.getMainLooper())
}

/**
 * One process-wide handler thread owns recovery, decode, and rename.
 * Only the main thread mutates [activeGens]. The copy thread never removes a generation.
 */
internal object AppearancePipeline {
    private val activeGens = LinkedHashSet<Long>()
    var onActive: (() -> Unit)? = null
    var onIdle: (() -> Unit)? = null
    var pendingOfficialDelete: Boolean = false

    private val main = Handler(Looper.getMainLooper())
    private val worker: Handler by lazy {
        val thread = HandlerThread("fskeyboard-appearance")
        thread.start()
        Handler(thread.looper)
    }
    private val loggedDecode = HashSet<Long>()

    fun isIdle(): Boolean = activeGens.isEmpty()

    fun beginGeneration(gen: Long) {
        checkMain()
        activeGens.add(gen)
        onActive?.invoke()
    }

    fun scheduleRecovery(context: Context) {
        val app = context.applicationContext
        val dir = appearanceDir(app)
        worker.post {
            val names = dir.list().orEmpty()
            val reports = ArrayList<Triple<Long, Boolean, Boolean>>()
            val seen = HashSet<Long>()
            for (name in names) {
                val gen = appearanceGeneration(name) ?: continue
                if (!seen.add(gen)) continue
                reports += Triple(
                    gen,
                    File(dir, "abandoned-$gen").exists(),
                    File(dir, "incoming-$gen.ok").exists(),
                )
            }
            main.post { adopt(app, dir, reports) }
        }
    }

    fun ensureLoaded(file: File) {
        if (!file.isFile) return
        val mtime = file.lastModified()
        val edge = AppearanceImageCache.displayEdge()
        if (AppearanceImageCache.holds(file, mtime) && AppearanceImageCache.covers(edge)) return
        loadForEdge(file, mtime, edge)
    }

    fun rescale(file: File, edge: Int, mtime: Long) {
        loadForEdge(file, mtime, edge)
    }

    private fun loadForEdge(file: File, mtime: Long, edge: Int) {
        val token = AppearanceImageCache.beginFit(edge)
        worker.post {
            if (!AppearanceImageCache.fitStillCurrent(token, edge)) return@post
            if (!file.isFile || file.lastModified() != mtime) return@post
            val decoded = decodePrepared(file) ?: return@post
            if (!AppearanceImageCache.fitStillCurrent(token, edge)) {
                decoded.recycle()
                return@post
            }
            val available = maxOf(decoded.width, decoded.height)
            val fitted = fitEdge(decoded, edge)
            main.post {
                // A newer noteSize wins. Publishing this edge would put the old fit back on top.
                if (!AppearanceImageCache.fitStillCurrent(token, edge) ||
                    !file.isFile ||
                    file.lastModified() != mtime
                ) {
                    if (!fitted.isRecycled) fitted.recycle()
                    return@post
                }
                AppearanceImageCache.publish(fitted, file, mtime, available, edge)
            }
        }
    }

    fun finish(gen: Long, context: Context) {
        val app = context.applicationContext
        worker.post { encodeMove(gen, appearanceDir(app), app) }
    }

    fun requestOfficialDelete(dir: File) {
        checkMain()
        val gens = HashSet<Long>()
        gens.addAll(activeGens)
        for (name in dir.list().orEmpty()) {
            appearanceGeneration(name)?.let { gens.add(it) }
        }
        for (gen in gens) {
            val marker = File(dir, "abandoned-$gen")
            marker.parentFile?.mkdirs()
            if (!marker.exists()) marker.createNewFile()
        }
        if (activeGens.isEmpty()) {
            pendingOfficialDelete = false
            File(dir, APPEARANCE_IMAGE_FILE).delete()
            AppearanceImageCache.clear()
        } else {
            pendingOfficialDelete = true
        }
    }

    /** Main thread. Removes [gen], then deletes that generation's temps. Not background.webp. */
    fun dropGeneration(gen: Long, dir: File) {
        checkMain()
        activeGens.remove(gen)
        deleteAppearanceTemps(dir, gen)
        if (pendingOfficialDelete && activeGens.isEmpty()) {
            File(dir, APPEARANCE_IMAGE_FILE).delete()
            AppearanceImageCache.clear()
            pendingOfficialDelete = false
        }
        if (activeGens.isEmpty()) onIdle?.invoke()
    }

    private fun adopt(app: Context, dir: File, reports: List<Triple<Long, Boolean, Boolean>>) {
        val busy = activeGens.isNotEmpty()
        for ((gen, abandoned, ok) in reports) {
            var action = recoveryAction(gen in activeGens, abandoned, ok)
            // A leftover .ok must not start a second move onto background.webp.
            if (busy && action == RecoveryAction.REENCODE) action = RecoveryAction.IGNORE
            when (action) {
                RecoveryAction.IGNORE -> Unit
                RecoveryAction.DELETE -> worker.post { deleteAppearanceTemps(dir, gen) }
                RecoveryAction.REENCODE -> {
                    activeGens.add(gen)
                    onActive?.invoke()
                    worker.post { encodeMove(gen, dir, app) }
                }
            }
        }
    }

    private fun encodeMove(gen: Long, dir: File, app: Context) {
        if (abandoned(dir, gen)) {
            discard(gen, dir, app, toast = false)
            return
        }
        val incoming = File(dir, "incoming-$gen")
        val ok = File(dir, "incoming-$gen.ok")
        if (!ok.isFile || !incoming.isFile) {
            main.post { dropGeneration(gen, dir) }
            return
        }
        var bitmap: Bitmap? = null
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(incoming.absolutePath, bounds)
            val width = bounds.outWidth
            val height = bounds.outHeight
            if (width <= 0 || height <= 0) {
                discard(gen, dir, app, toast = false)
                return
            }
            val sample = appearanceSampleSize(width, height)
            if (sampledBufferTooLarge(width, height, sample)) {
                discard(gen, dir, app, toast = true)
                return
            }
            if (abandoned(dir, gen)) {
                discard(gen, dir, app, toast = false)
                return
            }
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            val decoded = BitmapFactory.decodeFile(incoming.absolutePath, options)
            if (decoded == null) {
                discard(gen, dir, app, toast = false)
                return
            }
            bitmap = fitEdge(ensure565(decoded), APPEARANCE_MAX_EDGE)
            if (abandoned(dir, gen)) {
                bitmap?.recycle()
                discard(gen, dir, app, toast = false)
                return
            }
            val encoded = File(dir, "encoded-$gen.webp")
            writeWebp(bitmap, encoded)
            if (abandoned(dir, gen)) {
                bitmap?.recycle()
                discard(gen, dir, app, toast = false)
                return
            }
            val dest = File(dir, APPEARANCE_IMAGE_FILE)
            if (!moveReplace(encoded, dest)) {
                bitmap?.recycle()
                bitmap = null
                // A failed move stays on this handler. Re-posting immediately would spin
                // while the rename keeps failing; the pick button stays disabled until it lands.
                worker.postDelayed({ encodeMove(gen, dir, app) }, MOVE_RETRY_MS)
                return
            }
            if (abandoned(dir, gen)) {
                bitmap?.recycle()
                discard(gen, dir, app, toast = false)
                return
            }
            KeyboardPreferences(app).setBackgroundImageName(APPEARANCE_IMAGE_FILE, flush = true)
            incoming.delete()
            ok.delete()
            encoded.delete()
            val published = bitmap
            bitmap = null
            val mtime = dest.lastModified()
            Log.i(TAG, "appearance incoming recovered")
            main.post {
                if (pendingOfficialDelete || abandoned(dir, gen)) {
                    if (published != null && !published.isRecycled) published.recycle()
                    KeyboardPreferences(app).setBackgroundImageName(null, flush = true)
                    dropGeneration(gen, dir)
                } else if (published != null) {
                    AppearanceImageCache.publish(published, dest, mtime)
                    completeGeneration(gen)
                }
            }
        } catch (error: Exception) {
            bitmap?.let { if (!it.isRecycled) it.recycle() }
            logDecode(incoming.lastModified(), error)
            discard(gen, dir, app, toast = false)
        }
    }

    private fun completeGeneration(gen: Long) {
        checkMain()
        activeGens.remove(gen)
        if (activeGens.isEmpty()) onIdle?.invoke()
    }

    private fun discard(gen: Long, dir: File, app: Context, toast: Boolean) {
        File(dir, "incoming-$gen").delete()
        File(dir, "abandoned-$gen").apply {
            parentFile?.mkdirs()
            createNewFile()
        }
        Log.i(TAG, "appearance incoming discarded")
        main.post {
            if (toast) {
                Toast.makeText(app, R.string.appearance_image_too_large, Toast.LENGTH_SHORT).show()
            }
            dropGeneration(gen, dir)
        }
    }

    private fun abandoned(dir: File, gen: Long): Boolean = File(dir, "abandoned-$gen").exists()

    private fun decodePrepared(file: File): Bitmap? {
        return try {
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            val decoded = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null
            ensure565(decoded)
        } catch (error: Exception) {
            logDecode(file.lastModified(), error)
            null
        }
    }

    private fun logDecode(mtime: Long, error: Exception) {
        if (!loggedDecode.add(mtime)) return
        Log.i(TAG, error.javaClass.simpleName)
    }

    private fun checkMain() {
        check(Looper.myLooper() == Looper.getMainLooper())
    }

    private const val TAG = "FsKeyboard"
    private const val MOVE_RETRY_MS = 200L
}

internal fun deleteAppearanceTemps(dir: File, gen: Long) {
    File(dir, "incoming-$gen").delete()
    File(dir, "incoming-$gen.ok").delete()
    File(dir, "encoded-$gen.webp").delete()
    File(dir, "abandoned-$gen").delete()
}

/** Copies at most 25MB. Writes .ok only after the incoming bytes are synced. */
internal fun copyAppearanceStream(
    input: java.io.InputStream,
    incoming: File,
    ok: File,
    cancelled: AtomicBoolean,
): CopyResult {
    incoming.parentFile?.mkdirs()
    var total = 0L
    FileOutputStream(incoming).use { out ->
        val buffer = ByteArray(16 * 1024)
        while (true) {
            if (cancelled.get()) return CopyResult.CANCELLED
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > APPEARANCE_MAX_BYTES) return CopyResult.TOO_LARGE
            out.write(buffer, 0, read)
        }
        out.fd.sync()
    }
    if (cancelled.get()) return CopyResult.CANCELLED
    ok.createNewFile()
    return CopyResult.COPIED
}

internal enum class CopyResult { COPIED, CANCELLED, TOO_LARGE }

private fun ensure565(bitmap: Bitmap): Bitmap {
    if (bitmap.config == Bitmap.Config.RGB_565) return bitmap
    val copy = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.RGB_565)
    Canvas(copy).drawBitmap(bitmap, 0f, 0f, null)
    bitmap.recycle()
    return copy
}

private fun fitEdge(bitmap: Bitmap, maxEdge: Int): Bitmap {
    val edge = maxOf(bitmap.width, bitmap.height)
    if (edge <= maxEdge || edge <= 0) return bitmap
    val scale = maxEdge.toFloat() / edge.toFloat()
    val width = (bitmap.width * scale).toInt().coerceAtLeast(1)
    val height = (bitmap.height * scale).toInt().coerceAtLeast(1)
    val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
    if (scaled !== bitmap) bitmap.recycle()
    return if (scaled.config == Bitmap.Config.RGB_565) scaled else ensure565(scaled)
}

private fun writeWebp(bitmap: Bitmap, dest: File) {
    val format = if (android.os.Build.VERSION.SDK_INT >= 30) {
        Bitmap.CompressFormat.WEBP_LOSSY
    } else {
        @Suppress("DEPRECATION")
        Bitmap.CompressFormat.WEBP
    }
    for (quality in intArrayOf(80, 60, 40)) {
        FileOutputStream(dest).use { out ->
            bitmap.compress(format, quality, out)
            out.fd.sync()
        }
        if (dest.length() <= 1_500_000L || quality == 40) return
    }
}

private fun moveReplace(from: File, to: File): Boolean {
    val source = from.toPath()
    val dest = to.toPath()
    return try {
        Files.move(source, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        true
    } catch (_: AtomicMoveNotSupportedException) {
        try {
            Files.move(source, dest, StandardCopyOption.REPLACE_EXISTING)
            true
        } catch (_: Exception) {
            false
        }
    } catch (_: Exception) {
        false
    }
}
