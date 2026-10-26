package com.trailguidegps.app.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import org.maplibre.android.maps.Style

/**
 * Map icons drawn in code, so the app needs no image files: a colored disc
 * with a white symbol. Water is always a drop; seasonal water gets a paler
 * fill and a dashed ring.
 */
object MapIcons {
    private const val SIZE_DP = 26f

    fun addAll(context: Context, style: Style) {
        val d = context.resources.displayMetrics.density
        fun put(name: String, bg: Int, dashed: Boolean = false, glyph: (Canvas, Float, Paint) -> Unit) {
            style.addImage(name, icon(d, bg, dashed, glyph))
        }
        put("water-spring", 0xFF2E7DBF.toInt()) { c, s, p -> drop(c, s, p) }
        put("water-tap", 0xFF0D47A1.toInt()) { c, s, p -> drop(c, s, p); tapBar(c, s, p) }
        put("water-point", 0xFF00838F.toInt()) { c, s, p -> drop(c, s, p) }
        put("water-well", 0xFF455A64.toInt()) { c, s, p -> drop(c, s, p) }
        put("water-seasonal", 0xFF7FB3DE.toInt(), dashed = true) { c, s, p -> drop(c, s, p) }
        put("poi-campsite", 0xFFB4491A.toInt()) { c, s, p -> tent(c, s, p) }
        put("poi-trailhead", 0xFF2E7D4F.toInt()) { c, s, p -> letters(c, s, p, "TH") }
        put("poi-toilets", 0xFF6B716A.toInt()) { c, s, p -> letters(c, s, p, "WC") }
        put("poi-shelter", 0xFF6D4C41.toInt()) { c, s, p -> house(c, s, p) }
        put("poi-viewpoint", 0xFF5E35B1.toInt()) { c, s, p -> star(c, s, p) }
        put("poi-waterfall", 0xFF1565C0.toInt()) { c, s, p -> waves(c, s, p) }
        put("poi-hot_spring", 0xFFC2185B.toInt()) { c, s, p -> waves(c, s, p) }
        put("poi-pass", 0xFF5A4636.toInt()) { c, s, p -> letters(c, s, p, ")(") }
        put("poi-saddle", 0xFF5A4636.toInt()) { c, s, p -> letters(c, s, p, ")(") }
        put("poi-other", 0xFF6B716A.toInt()) { c, s, p -> c.drawCircle(s / 2, s / 2, s * 0.12f, p) }
    }

    private fun icon(density: Float, bg: Int, dashed: Boolean, glyph: (Canvas, Float, Paint) -> Unit): Bitmap {
        val s = SIZE_DP * density
        val bmp = Bitmap.createBitmap(s.toInt(), s.toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bg; style = Paint.Style.FILL }
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = s * 0.09f
            if (dashed) pathEffect = DashPathEffect(floatArrayOf(s * 0.12f, s * 0.08f), 0f)
        }
        val r = s / 2 - ring.strokeWidth / 2
        c.drawCircle(s / 2, s / 2, r, fill)
        c.drawCircle(s / 2, s / 2, r, ring)
        val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); style = Paint.Style.FILL }
        glyph(c, s, white)
        return bmp
    }

    private fun drop(c: Canvas, s: Float, p: Paint) {
        val path = Path().apply {
            moveTo(s * 0.5f, s * 0.22f)
            cubicTo(s * 0.62f, s * 0.40f, s * 0.70f, s * 0.50f, s * 0.70f, s * 0.60f)
            cubicTo(s * 0.70f, s * 0.72f, s * 0.61f, s * 0.80f, s * 0.5f, s * 0.80f)
            cubicTo(s * 0.39f, s * 0.80f, s * 0.30f, s * 0.72f, s * 0.30f, s * 0.60f)
            cubicTo(s * 0.30f, s * 0.50f, s * 0.38f, s * 0.40f, s * 0.5f, s * 0.22f)
            close()
        }
        c.drawPath(path, p)
    }

    private fun tapBar(c: Canvas, s: Float, p: Paint) {
        c.drawRect(s * 0.30f, s * 0.84f, s * 0.70f, s * 0.88f, p)
    }

    private fun tent(c: Canvas, s: Float, p: Paint) {
        val path = Path().apply {
            moveTo(s * 0.5f, s * 0.24f); lineTo(s * 0.78f, s * 0.74f); lineTo(s * 0.22f, s * 0.74f); close()
        }
        c.drawPath(path, p)
    }

    private fun house(c: Canvas, s: Float, p: Paint) {
        val path = Path().apply {
            moveTo(s * 0.5f, s * 0.24f); lineTo(s * 0.78f, s * 0.48f); lineTo(s * 0.70f, s * 0.48f)
            lineTo(s * 0.70f, s * 0.74f); lineTo(s * 0.30f, s * 0.74f); lineTo(s * 0.30f, s * 0.48f)
            lineTo(s * 0.22f, s * 0.48f); close()
        }
        c.drawPath(path, p)
    }

    private fun star(c: Canvas, s: Float, p: Paint) {
        val path = Path()
        for (i in 0 until 10) {
            val r = if (i % 2 == 0) s * 0.28f else s * 0.12f
            val a = Math.toRadians(-90.0 + i * 36.0)
            val x = s / 2 + (r * Math.cos(a)).toFloat()
            val y = s / 2 + (r * Math.sin(a)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        c.drawPath(path, p)
    }

    private fun waves(c: Canvas, s: Float, p: Paint) {
        val stroke = Paint(p).apply { style = Paint.Style.STROKE; strokeWidth = s * 0.07f; strokeCap = Paint.Cap.ROUND }
        for (k in 0..2) {
            val y = s * (0.36f + k * 0.14f)
            val path = Path().apply {
                moveTo(s * 0.28f, y)
                quadTo(s * 0.39f, y - s * 0.07f, s * 0.5f, y)
                quadTo(s * 0.61f, y + s * 0.07f, s * 0.72f, y)
            }
            c.drawPath(path, stroke)
        }
    }

    private fun letters(c: Canvas, s: Float, p: Paint, text: String) {
        val t = Paint(p).apply { textSize = s * 0.36f; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
        c.drawText(text, s / 2, s / 2 - (t.descent() + t.ascent()) / 2, t)
    }
}
