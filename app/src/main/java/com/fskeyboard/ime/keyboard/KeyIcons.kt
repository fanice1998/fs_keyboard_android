package com.fskeyboard.ime.keyboard

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

internal enum class KeyIcon {
    DELETE,
    RETURN,
    GLOBE,
    SYMBOLS,
}

internal object KeyIcons {
    private val path = Path()
    private val oval = RectF()

    fun draw(canvas: Canvas, icon: KeyIcon, bounds: RectF, color: Int, paint: Paint) {
        val size = minOf(bounds.width(), bounds.height()) * 0.48f
        if (size <= 0f) return
        canvas.save()
        canvas.translate(bounds.centerX() - size / 2f, bounds.centerY() - size / 2f)
        canvas.scale(size / 24f, size / 24f)
        paint.color = color
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.7f
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        when (icon) {
            KeyIcon.DELETE -> drawDelete(canvas, paint)
            KeyIcon.RETURN -> drawReturn(canvas, paint)
            KeyIcon.GLOBE -> drawGlobe(canvas, paint)
            KeyIcon.SYMBOLS -> drawSymbols(canvas, paint)
        }
        canvas.restore()
    }

    private fun drawDelete(canvas: Canvas, paint: Paint) {
        path.reset()
        path.moveTo(8f, 5.5f)
        path.lineTo(20.5f, 5.5f)
        path.lineTo(20.5f, 18.5f)
        path.lineTo(8f, 18.5f)
        path.lineTo(3f, 12f)
        path.close()
        canvas.drawPath(path, paint)
        canvas.drawLine(11.5f, 9f, 17f, 15f, paint)
        canvas.drawLine(17f, 9f, 11.5f, 15f, paint)
    }

    private fun drawReturn(canvas: Canvas, paint: Paint) {
        path.reset()
        path.moveTo(18f, 6f)
        path.lineTo(18f, 15.5f)
        path.lineTo(7f, 15.5f)
        canvas.drawPath(path, paint)
        canvas.drawLine(7f, 15.5f, 11.5f, 11f, paint)
        canvas.drawLine(7f, 15.5f, 11.5f, 20f, paint)
    }

    private fun drawGlobe(canvas: Canvas, paint: Paint) {
        canvas.drawCircle(12f, 12f, 8f, paint)
        canvas.drawLine(12f, 4f, 12f, 20f, paint)
        canvas.drawLine(4.2f, 12f, 19.8f, 12f, paint)
        oval.set(8f, 4f, 16f, 20f)
        canvas.drawOval(oval, paint)
    }

    private fun drawSymbols(canvas: Canvas, paint: Paint) {
        canvas.drawLine(9f, 5f, 9f, 19f, paint)
        canvas.drawLine(15f, 5f, 15f, 19f, paint)
        canvas.drawLine(5f, 9f, 19f, 9f, paint)
        canvas.drawLine(5f, 15f, 19f, 15f, paint)
    }
}
