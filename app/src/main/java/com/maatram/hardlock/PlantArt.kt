package com.maatram.hardlock

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import java.util.Calendar
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Draws the sakura: a seed that grows into a blooming tree as the lock runs,
 * under a sky that follows the real time of day. Plain android.graphics so the
 * same art draws in the app (Compose nativeCanvas) and in the home-screen widget (Bitmap).
 * Original art, built from a fixed-seed branching tree.
 */
object PlantArt {
    private class Seg(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val depth: Int)
    private class Tip(val x: Float, val y: Float, val i: Int, val depth: Int, val px: Float, val py: Float)

    private val segs = ArrayList<Seg>()
    private val tips = ArrayList<Tip>()
    private var minX = 0f; private var maxX = 0f; private var minY = 0f
    private const val DEPTH = 5

    init {
        val r = java.util.Random(11)
        fun grow(x: Float, y: Float, ang: Double, len: Float, d: Int) {
            val ex = x + (cos(ang) * len).toFloat()
            val ey = y + (sin(ang) * len).toFloat()
            segs += Seg(x, y, ex, ey, d)
            if (d >= 2) tips += Tip(ex, ey, tips.size, d, x, y)
            if (d == DEPTH) return
            val n = if (d == 0) 2 else if (r.nextFloat() < 0.35f) 3 else 2
            for (k in 0 until n) {
                val spread = if (n == 2) (if (k == 0) -0.48 else 0.48) else (k - 1) * 0.55
                grow(ex, ey, ang + spread + (r.nextFloat() - 0.5) * 0.35, len * (0.7f + r.nextFloat() * 0.1f), d + 1)
            }
        }
        grow(0f, 0f, -PI / 2, 1f, 0)
        minX = segs.minOf { min(it.x0, it.x1) }; maxX = segs.maxOf { max(it.x0, it.x1) }
        minY = segs.minOf { min(it.y0, it.y1) }
    }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val path = Path()
    private val oval = RectF()

    // Sky keyframes: hour -> (top, bottom)
    private val SKY = listOf(
        0f to (0xFF0A0E22.toInt() to 0xFF1A2140.toInt()),
        5f to (0xFF0F1630.toInt() to 0xFF2A2F55.toInt()),
        6.5f to (0xFF34406E.toInt() to 0xFFE89A7C.toInt()),
        8f to (0xFF2F5F86.toInt() to 0xFF9CC7DA.toInt()),
        16f to (0xFF2C5A80.toInt() to 0xFF9CC7DA.toInt()),
        18f to (0xFF3A2C5C.toInt() to 0xFFE5876C.toInt()),
        19.5f to (0xFF161A38.toInt() to 0xFF4A355F.toInt()),
        24f to (0xFF0A0E22.toInt() to 0xFF1A2140.toInt()),
    )

    private fun lerpC(a: Int, b: Int, t: Float): Int {
        fun ch(s: Int) = ((a shr s and 255) + ((b shr s and 255) - (a shr s and 255)) * t).toInt() shl s
        return (0xFF shl 24) or ch(16) or ch(8) or ch(0)
    }

    private fun clamp(v: Float) = v.coerceIn(0f, 1f)

    fun hourNow(): Float = Calendar.getInstance().run { get(Calendar.HOUR_OF_DAY) + get(Calendar.MINUTE) / 60f }

    /**
     * @param progress 0 = seed, 1 = full bloom
     * @param leavesLost leaves dropped by temptation (0..Garden.MAX_LEAVES)
     * @param hour 0..24 for the sky
     * @param done lock finished: petals drift down
     */
    fun draw(c: Canvas, w: Float, h: Float, progress: Float, leavesLost: Int, hour: Float = hourNow(), done: Boolean = false) {
        val p = clamp(progress)
        drawSky(c, w, h, hour)
        val groundY = h * 0.84f
        // ground
        fill.shader = LinearGradient(0f, groundY, 0f, h, 0xFF22352A.toInt(), 0xFF101A14.toInt(), Shader.TileMode.CLAMP)
        path.reset(); path.moveTo(0f, groundY + h * 0.03f)
        path.quadTo(w * 0.5f, groundY - h * 0.05f, w, groundY + h * 0.03f)
        path.lineTo(w, h); path.lineTo(0f, h); path.close()
        c.drawPath(path, fill); fill.shader = null

        val cx = w * 0.5f
        val baseY = groundY - h * 0.01f
        // Tree box: fits width and ~66% of height
        val treeH = h * 0.66f
        val scale = min(treeH / (-minY), (w * 0.9f) / (maxX - minX))
        val g = clamp((p - 0.05f) / 0.95f)     // growth after the seed stage

        if (p < 0.05f || g <= 0f) { drawSeed(c, cx, baseY, h, p / 0.05f); drawFallen(c, w, h, groundY, leavesLost); return }

        // branches
        for (s in segs) {
            val local = clamp((g - s.depth * 0.13f) / 0.22f)
            if (local <= 0f) continue
            val x0 = cx + s.x0 * scale; val y0 = baseY + s.y0 * scale
            val x1 = cx + (s.x0 + (s.x1 - s.x0) * local) * scale
            val y1 = baseY + (s.y0 + (s.y1 - s.y0) * local) * scale
            line.color = if (s.depth == 0 && g < 0.25f) 0xFF7FB08A.toInt() else 0xFF6A4A3A.toInt()
            line.strokeWidth = max(1.2f, scale * 0.09f * Math.pow(0.68, s.depth.toDouble()).toFloat() * (0.35f + 0.65f * g))
            c.drawLine(x0, y0, x1, y1, line)
        }
        // sprout leaves on the young stem
        if (g < 0.3f) {
            val top = segs[0]; val local = clamp(g / 0.22f)
            val tx = cx + top.x1 * scale * local; val ty = baseY + top.y1 * scale * local
            val s = h * 0.035f * (0.4f + local)
            fill.color = 0xFF8CC79A.toInt()
            leaf(c, tx - s * 0.6f, ty, s, -0.6f); leaf(c, tx + s * 0.6f, ty, s, 0.6f)
        }
        // canopy: green leaves, then pink blossoms. Lost leaves are hidden in 1/12 slices.
        val lost = leavesLost.coerceIn(0, Garden.MAX_LEAVES)
        val r = h * 0.03f
        for (t in tips) {
            if ((t.i * 7) % Garden.MAX_LEAVES < lost) continue
            val tl = clamp((g - t.depth * 0.13f) / 0.22f)   // leaves ride the tip of the growing twig
            if (g <= 0.3f + (t.i % 7) * 0.03f || tl < 0.35f) continue
            val x = cx + (t.px + (t.x - t.px) * tl) * scale; val y = baseY + (t.py + (t.y - t.py) * tl) * scale
            fill.color = 0xFF6FA57F.toInt()
            leaf(c, x, y, r * 1.1f, ((t.i % 5) - 2) * 0.5f)
            val bloomAt = 0.6f + ((t.i * 37) % 100) / 100f * 0.38f
            if (p >= bloomAt || p >= 1f) {
                val br = r * (0.75f + ((t.i * 13) % 10) / 25f)
                fill.color = if (t.i % 3 == 0) 0xFFF19BB5.toInt() else 0xFFF6BCCD.toInt()
                c.drawCircle(x + br * 0.3f, y - br * 0.2f, br, fill)
                fill.color = 0xFFFFE6EE.toInt()
                c.drawCircle(x + br * 0.3f, y - br * 0.2f, br * 0.32f, fill)
            }
        }
        drawFallen(c, w, h, groundY, lost)
        if (done) {
            fill.color = 0xCCF6BCCD.toInt()
            for (k in 0 until 7) {
                val px = w * (0.15f + ((k * 29) % 70) / 100f); val py = h * (0.25f + ((k * 17) % 50) / 100f)
                oval.set(px - r * 0.5f, py - r * 0.3f, px + r * 0.5f, py + r * 0.3f)
                c.save(); c.rotate(k * 40f, px, py); c.drawOval(oval, fill); c.restore()
            }
        }
    }

    private fun leaf(c: Canvas, x: Float, y: Float, s: Float, rot: Float) {
        c.save(); c.rotate(Math.toDegrees(rot.toDouble()).toFloat(), x, y)
        oval.set(x - s * 0.45f, y - s, x + s * 0.45f, y + s * 0.15f)
        c.drawOval(oval, fill); c.restore()
    }

    private fun drawSeed(c: Canvas, cx: Float, baseY: Float, h: Float, t: Float) {
        fill.color = 0xFF3A2A20.toInt()
        oval.set(cx - h * 0.09f, baseY - h * 0.025f, cx + h * 0.09f, baseY + h * 0.03f); c.drawOval(oval, fill)
        fill.color = 0xFF9A6B4A.toInt()
        oval.set(cx - h * 0.028f, baseY - h * 0.05f, cx + h * 0.028f, baseY); c.drawOval(oval, fill)
        if (t > 0.5f) { line.color = 0xFF8CC79A.toInt(); line.strokeWidth = h * 0.008f; c.drawLine(cx, baseY - h * 0.045f, cx, baseY - h * 0.045f - h * 0.04f * (t - 0.5f) * 2, line) }
    }

    /** Leaves dropped by temptation lie on the ground, yellowed. */
    private fun drawFallen(c: Canvas, w: Float, h: Float, groundY: Float, n: Int) {
        fill.color = 0xFFC9A55A.toInt()
        val s = h * 0.028f
        for (k in 0 until n.coerceAtMost(Garden.MAX_LEAVES)) {
            val x = w * (0.2f + ((k * 41) % 60) / 100f); val y = groundY + h * (0.03f + ((k * 23) % 9) / 100f)
            leaf(c, x, y, s, 1.4f + (k % 3) * 0.4f)
        }
    }

    private fun drawSky(c: Canvas, w: Float, h: Float, hour: Float) {
        val hr = ((hour % 24f) + 24f) % 24f
        var i = 0
        while (i < SKY.size - 2 && SKY[i + 1].first <= hr) i++
        val (h0, c0) = SKY[i]; val (h1, c1) = SKY[i + 1]
        val t = clamp((hr - h0) / (h1 - h0))
        fill.shader = LinearGradient(0f, 0f, 0f, h, lerpC(c0.first, c1.first, t), lerpC(c0.second, c1.second, t), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, fill); fill.shader = null
        val night = when { hr < 5f || hr > 20f -> 1f; hr < 6.5f -> (6.5f - hr) / 1.5f; hr > 18.5f -> (hr - 18.5f) / 1.5f; else -> 0f }
        if (night > 0f) {
            fill.color = 0xFFFFFFFF.toInt()
            for (k in 0 until 18) {
                fill.alpha = (night * (120 + (k * 53) % 120)).toInt().coerceIn(0, 255)
                c.drawCircle(w * (((k * 61) % 97) / 97f), h * (((k * 37) % 50) / 100f), h * 0.004f + (k % 3) * h * 0.002f, fill)
            }
            fill.alpha = 255
        }
        // sun 6..18, moon otherwise, travelling an arc
        val isDay = hr in 6f..18f
        val tt = if (isDay) (hr - 6f) / 12f else ((hr + 6f) % 24f) / 12f
        val sx = w * (0.1f + 0.8f * tt); val sy = h * (0.5f - 0.36f * sin(PI * tt).toFloat())
        val rad = h * 0.05f
        if (isDay) {
            fill.color = 0x33FFE7A8; c.drawCircle(sx, sy, rad * 1.9f, fill)
            fill.color = 0xFFFFE19A.toInt(); c.drawCircle(sx, sy, rad, fill)
        } else {
            fill.color = 0xFFEDEBDD.toInt(); c.drawCircle(sx, sy, rad * 0.85f, fill)
            fill.color = lerpC(c0.first, c1.first, t); c.drawCircle(sx + rad * 0.35f, sy - rad * 0.2f, rad * 0.75f, fill)
        }
    }
}
