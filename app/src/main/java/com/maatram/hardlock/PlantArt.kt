package com.maatram.hardlock

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import java.util.Calendar
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Draws the sakura: a seed that grows into a blooming tree as the lock runs, under a
 * sky that follows the real time of day (sun/moon, stars, clouds, misty mountains).
 * Plain android.graphics so the same art draws in the app and in the home-screen widget.
 * Line-for-line the same algorithm and seeded random as maatram.co.in/sakura.js,
 * so the tree on the website and on the phone is the same tree. Original art.
 */
object PlantArt {
    private const val DEPTH = 6

    /** Park-Miller random, identical to sakura.js */
    private class Rng(private var s: Long) {
        fun next(): Double { s = (s * 16807L) % 2147483647L; return (s - 1).toDouble() / 2147483646.0 }
        fun f(): Float = next().toFloat()
    }

    // a limb: start, curve control, end, start/end radius, depth
    private class Seg(val x0: Float, val y0: Float, val qx: Float, val qy: Float, val x1: Float, val y1: Float,
                      val r0: Float, val r1: Float, val d: Int)
    private class Tip(val x: Float, val y: Float, val i: Int, val d: Int, val px: Float, val py: Float, val s: Float)

    private val segs = ArrayList<Seg>()
    private val tips = ArrayList<Tip>()
    private var minX = 0f; private var maxX = 0f; private var minY = 0f

    init {
        val r = Rng(3)
        fun grow(x: Double, y: Double, ang: Double, len: Double, rad: Double, d: Int) {
            val ex = x + cos(ang) * len; val ey = y + sin(ang) * len
            val bend = (r.next() - 0.5) * len * 0.22
            val mx = (x + ex) / 2 - sin(ang) * bend; val my = (y + ey) / 2 + cos(ang) * bend
            segs += Seg(x.toFloat(), y.toFloat(), mx.toFloat(), my.toFloat(), ex.toFloat(), ey.toFloat(), rad.toFloat(), (rad * 0.7).toFloat(), d)
            if (d >= 3) tips += Tip(ex.toFloat(), ey.toFloat(), tips.size, d, x.toFloat(), y.toFloat(), r.f())
            if (d == DEPTH) return
            val n = if (d == 0) 3 else if (r.next() < 0.45) 3 else 2
            for (k in 0 until n) {
                val sp = if (n == 2) (if (k == 0) -1.0 else 1.0) * (0.3 + r.next() * 0.22)
                         else (k - 1) * (if (d == 0) 0.62 + r.next() * 0.14 else 0.42 + r.next() * 0.14)
                var a = ang + sp + (r.next() - 0.5) * 0.28
                a += (-PI / 2 - a) * 0.06                      // limbs reach up and out into a rounded crown
                val l = len * (if (d == 0) 0.78 else 0.74 + r.next() * 0.1)
                grow(ex, ey, a, l, rad * (if (n == 3) 0.62 else 0.7), d + 1)
            }
        }
        grow(0.0, 0.0, -PI / 2 + 0.04, 0.72, 0.16, 0)
        for (s in segs) { minX = min(minX, min(s.x0, s.x1)); maxX = max(maxX, max(s.x0, s.x1)); minY = min(minY, min(s.y0, s.y1)) }
    }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val path = Path()
    private val oval = RectF()

    // ---- colour helpers ----
    private fun c(hex: Int) = hex or (0xFF shl 24)
    private fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(s: Int) = (((a shr s) and 255) + (((b shr s) and 255) - ((a shr s) and 255)) * t).roundToInt().coerceIn(0, 255) shl s
        return (0xFF shl 24) or ch(16) or ch(8) or ch(0)
    }
    private fun alpha(col: Int, al: Float) = ((al.coerceIn(0f, 1f) * 255).roundToInt() shl 24) or (col and 0xFFFFFF)
    private fun clamp(v: Float) = v.coerceIn(0f, 1f)

    fun hourNow(): Float = Calendar.getInstance().run { get(Calendar.HOUR_OF_DAY) + get(Calendar.MINUTE) / 60f }

    // sky keyframes: hour, top, middle, horizon
    private class Key(val h: Float, val top: Int, val mid: Int, val hor: Int)
    private val SKY = listOf(
        Key(0f, c(0x070A1A), c(0x101634), c(0x1E2448)), Key(4.8f, c(0x0A0F26), c(0x161C40), c(0x2A2C55)),
        Key(5.8f, c(0x1E2452), c(0x5A4A7A), c(0xE0907A)), Key(6.8f, c(0x3E6EA8), c(0xE9A88C), c(0xFFD6A0)),
        Key(8f, c(0x3D7BC0), c(0x7FB6E0), c(0xCFE6F2)), Key(15.5f, c(0x2F6FB8), c(0x7AB3E2), c(0xD2E9F4)),
        Key(17.3f, c(0x4A6FA8), c(0xE9A97E), c(0xFFD08A)), Key(18.3f, c(0x3B3770), c(0xC7697A), c(0xFF9E6B)),
        Key(19.3f, c(0x161A40), c(0x3E2F63), c(0x8A4D6E)), Key(20.5f, c(0x090D22), c(0x121838), c(0x232A52)),
        Key(24f, c(0x070A1A), c(0x101634), c(0x1E2448)),
    )

    private class Light(val top: Int, val mid: Int, val hor: Int, val night: Float, val warm: Float, val day: Boolean, val tt: Float)

    private fun light(hour: Float): Light {
        val hr = ((hour % 24f) + 24f) % 24f
        var i = 0
        while (i < SKY.size - 2 && SKY[i + 1].h <= hr) i++
        val a = SKY[i]; val b = SKY[i + 1]; val t = clamp((hr - a.h) / (b.h - a.h))
        val night = when { hr < 5f || hr > 20.3f -> 1f; hr < 6.5f -> (6.5f - hr) / 1.5f; hr > 18.8f -> (hr - 18.8f) / 1.5f; else -> 0f }
        val warm = max(0f, max(1 - abs(hr - 6.6f) / 1.2f, 1 - abs(hr - 18.1f) / 1.3f))
        val day = hr in 5.6f..18.9f
        val tt = if (day) (hr - 5.6f) / 13.3f else ((hr + 5.1f) % 24f) / 10.7f
        return Light(mix(a.top, b.top, t), mix(a.mid, b.mid, t), mix(a.hor, b.hor, t), clamp(night), clamp(warm), day, tt)
    }
    private fun tint(L: Light, col: Int) = mix(mix(col, c(0xFF9F7A), L.warm * 0.18f), c(0x141A36), L.night * 0.6f)
    private fun tintBloom(L: Light, col: Int) = mix(mix(col, c(0xFF9F7A), L.warm * 0.14f), c(0x5B5F9A), L.night * 0.42f)

    // ---- primitives (same shapes as sakura.js) ----
    private fun disc(cv: Canvas, x: Float, y: Float, r: Float, col: Int) { fill.shader = null; fill.color = col; cv.drawCircle(x, y, r, fill) }
    private fun radial(cv: Canvas, x: Float, y: Float, r: Float, colors: IntArray, stops: FloatArray) {
        if (r <= 0.5f) return
        fill.shader = RadialGradient(x, y, r, colors, stops, Shader.TileMode.CLAMP); fill.color = -1
        cv.drawCircle(x, y, r, fill); fill.shader = null
    }
    private fun glow(cv: Canvas, x: Float, y: Float, r: Float, col: Int, al: Float) =
        radial(cv, x, y, r, intArrayOf(alpha(col, al), alpha(col, 0f)), floatArrayOf(0f, 1f))
    private fun puff(cv: Canvas, x: Float, y: Float, r: Float, col: Int, al: Float) =
        radial(cv, x, y, r, intArrayOf(alpha(col, al), alpha(col, al * 0.85f), alpha(col, 0f)), floatArrayOf(0f, 0.6f, 1f))

    private fun ridge(cv: Canvas, w: Float, h: Float, seed: Long, base: Float, amp: Float, col: Int) {
        val r = Rng(seed); val p1 = r.next() * 6; val p2 = r.next() * 6; val p3 = r.next() * 6
        path.reset(); path.moveTo(0f, h)
        for (k in 0..48) {
            val x = k / 48.0
            val y = base - amp * (0.55 * sin(x * 5.1 + p1) + 0.3 * sin(x * 11.3 + p2) + 0.15 * sin(x * 23.7 + p3))
            path.lineTo((x * w).toFloat(), (y * h).toFloat())
        }
        path.lineTo(w, h); path.close()
        fill.shader = null; fill.color = col; cv.drawPath(path, fill)
    }
    private fun band(cv: Canvas, w: Float, h: Float, y0: Float, y1: Float, col: Int, al: Float) {
        fill.shader = LinearGradient(0f, y0 * h, 0f, y1 * h, intArrayOf(alpha(col, 0f), alpha(col, al), alpha(col, 0f)),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        cv.drawRect(0f, y0 * h, w, y1 * h, fill); fill.shader = null
    }
    private fun leafShape(cv: Canvas, x: Float, y: Float, s: Float, rot: Float, col: Int) {
        cv.save(); cv.translate(x, y); cv.rotate(Math.toDegrees(rot.toDouble()).toFloat())
        path.reset(); path.moveTo(0f, 0f); path.quadTo(s * 0.55f, -s * 0.45f, 0f, -s); path.quadTo(-s * 0.55f, -s * 0.45f, 0f, 0f)
        fill.shader = null; fill.color = col; cv.drawPath(path, fill); cv.restore()
    }
    private fun petal(cv: Canvas, x: Float, y: Float, s: Float, rot: Float, col: Int) {
        cv.save(); cv.translate(x, y); cv.rotate(Math.toDegrees(rot.toDouble()).toFloat())
        path.reset(); path.moveTo(0f, s * 0.5f); path.quadTo(s * 0.62f, 0f, s * 0.18f, -s * 0.5f); path.lineTo(0f, -s * 0.36f)
        path.lineTo(-s * 0.18f, -s * 0.5f); path.quadTo(-s * 0.62f, 0f, 0f, s * 0.5f); path.close()
        fill.shader = null; fill.color = col; cv.drawPath(path, fill); cv.restore()
    }
    private fun flower(cv: Canvas, x: Float, y: Float, r: Float, col: Int, rot: Float) {
        fill.shader = null; fill.color = col
        for (k in 0 until 5) {
            val a = rot + k * 1.2566f
            cv.drawCircle(x + cos(a) * r * 0.55f, y + sin(a) * r * 0.55f, r * 0.5f, fill)
        }
        disc(cv, x, y, r * 0.24f, c(0xD9577F)); disc(cv, x, y, r * 0.11f, c(0xFFE6A6))
    }

    private fun sunX(L: Light, w: Float) = w * (0.08f + 0.84f * L.tt)
    private fun sunY(L: Light, h: Float) = h * (0.66f - 0.52f * sin(PI.toFloat() * clamp(L.tt)))

    // ---- sky, sun/moon, clouds, mountains, mist ----
    private fun sky(cv: Canvas, w: Float, h: Float, L: Light) {
        fill.shader = LinearGradient(0f, 0f, 0f, h * 0.8f, intArrayOf(L.top, L.mid, L.hor), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        cv.drawRect(0f, 0f, w, h, fill); fill.shader = null
        if (L.night > 0f) {
            val r = Rng(7)
            for (k in 0 until 70) {
                val x = r.f() * w; val y = r.f() * h * 0.62f; val s = (0.4f + r.f() * 1.1f) * h * 0.0032f
                val al = L.night * (0.35f + r.f() * 0.65f) * (1 - y / (h * 0.7f))
                disc(cv, x, y, s, alpha(-1, al))
                if (s > h * 0.0042f) glow(cv, x, y, s * 5, c(0xBFD4FF), al * 0.25f)
            }
        }
        val sx = sunX(L, w); val sy = sunY(L, h); val r = h * 0.045f
        if (L.day) {
            val near = clamp(1 - sin(PI.toFloat() * clamp(L.tt)) * 1.6f)       // low sun: bigger, warmer
            glow(cv, sx, sy, r * (7 + near * 4), mix(c(0xFFE7B0), c(0xFF9E62), near), 0.42f)
            glow(cv, sx, sy, r * 2.2f, c(0xFFF4D6), 0.55f)
            radial(cv, sx, sy, r, intArrayOf(c(0xFFFDF2), mix(c(0xFFE3A0), c(0xFF9A5A), near)), floatArrayOf(0f, 1f))
        } else {
            glow(cv, sx, sy, r * 6, c(0xAFC4F2), 0.22f)
            fill.shader = RadialGradient(sx - r * 0.3f, sy - r * 0.3f, r * 0.9f, c(0xFFFDF0), c(0xD9D6C8), Shader.TileMode.CLAMP)
            cv.drawCircle(sx, sy, r * 0.9f, fill); fill.shader = null
            disc(cv, sx + r * 0.25f, sy + r * 0.12f, r * 0.18f, 0x59969182)
            disc(cv, sx - r * 0.3f, sy + r * 0.32f, r * 0.11f, 0x4D969182)
        }
        // clouds: soft puffs, lit from the sun's side
        val clouds = arrayOf(floatArrayOf(0.16f, 0.15f, 0.11f), floatArrayOf(0.6f, 0.09f, 0.15f), floatArrayOf(0.88f, 0.25f, 0.08f), floatArrayOf(0.4f, 0.3f, 0.07f))
        val ccol = if (L.night > 0.5f) mix(c(0x3C4470), c(0x262C52), L.night) else mix(c(0xFFFFFF), c(0xFFC2A6), L.warm)
        val cal = if (L.night > 0.5f) 0.45f else 0.7f - L.warm * 0.1f
        for ((j, cl) in clouds.withIndex()) {
            val rr = Rng(31L + j); val cx = cl[0] * w; val cy = cl[1] * h; val s = cl[2] * w
            for (k in 0 until 7) {
                val ox = (rr.f() - 0.5f) * s * 1.6f; val oy = (rr.f() - 0.5f) * s * 0.35f; val pr = s * (0.35f + rr.f() * 0.35f)
                puff(cv, cx + ox, cy + oy + pr * 0.22f, pr * 1.05f, mix(ccol, L.top, 0.4f), cal * 0.55f)
                puff(cv, cx + ox, cy + oy, pr, ccol, cal)
            }
        }
        // layered mountains fading into haze
        ridge(cv, w, h, 101, 0.7f, 0.1f, mix(L.hor, mix(c(0x2E3D63), L.top, 0.4f), 0.38f))
        band(cv, w, h, 0.6f, 0.8f, L.hor, 0.5f)
        ridge(cv, w, h, 202, 0.77f, 0.06f, mix(L.hor, c(0x1F2C3E), 0.62f))
        band(cv, w, h, 0.72f, 0.88f, L.hor, 0.55f)
    }

    // ---- ground: grassy hill, blades, fallen petals and leaves ----
    private fun ground(cv: Canvas, w: Float, h: Float, L: Light, bloom: Float, lost: Int) {
        val gy = h * 0.84f
        fill.shader = LinearGradient(0f, gy - h * 0.04f, 0f, h, intArrayOf(tint(L, c(0x6E9A5C)), tint(L, c(0x3F6B3A)), tint(L, c(0x1B2E1A))),
            floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)
        path.reset(); path.moveTo(0f, gy + h * 0.03f); path.quadTo(w / 2, gy - h * 0.06f, w, gy + h * 0.03f)
        path.lineTo(w, h); path.lineTo(0f, h); path.close(); cv.drawPath(path, fill); fill.shader = null
        val r = Rng(404); val blade = tint(L, c(0x2D4F2A)); val tipc = tint(L, c(0x8FBF6E))
        line.strokeWidth = max(1f, h * 0.0025f)
        for (k in 0 until 140) {
            val x = r.f() * w; val xf = x / w - 0.5f
            val y = gy + h * 0.03f - h * 0.09f * (0.25f - xf * xf) + r.f() * h * 0.12f
            val len = h * (0.012f + r.f() * 0.02f)
            line.color = if (r.next() < 0.3) tipc else blade
            path.reset(); path.moveTo(x, y); path.quadTo(x + len * 0.2f, y - len * 0.6f, x + (r.f() - 0.3f) * len * 0.6f, y - len)
            cv.drawPath(path, line)
        }
        val n = (46 * bloom).roundToInt()
        val pc = intArrayOf(tint(L, c(0xF7C3D3)), tint(L, c(0xF29BB6)), tint(L, c(0xFFE4EC)))
        for (j in 0 until n) {
            val px = w * (0.12f + r.f() * 0.76f); val py = gy + h * (0.015f + r.f() * 0.13f); val ps = h * (0.011f + r.f() * 0.006f)
            petal(cv, px, py, ps, r.f() * 6.28f, pc[j % 3])
        }
        for (m in 0 until min(lost, Garden.MAX_LEAVES))
            leafShape(cv, w * (0.24f + ((m * 41) % 52) / 100f), gy + h * (0.04f + ((m * 23) % 9) / 100f), h * 0.034f,
                1.3f + (m % 3) * 0.55f, tint(L, if (m % 2 == 1) c(0xB98E3E) else c(0x9C6E2E)))
    }

    /** One limb: a curved, tapering shape with a shadow side and a sunlit side, grown to fraction t. */
    private fun limb(cv: Canvas, s: Seg, t: Float, sc: Float, cx: Float, by: Float, gw: Float, col: Int, hi: Int, side: Float) {
        val bx = s.x0 + (s.qx - s.x0) * t; val byy = s.y0 + (s.qy - s.y0) * t        // de Casteljau split at t
        val mx = s.qx + (s.x1 - s.qx) * t; val my = s.qy + (s.y1 - s.qy) * t
        val ex = bx + (mx - bx) * t; val ey = byy + (my - byy) * t
        val r0 = s.r0 * gw * sc; val r1 = (s.r0 + (s.r1 - s.r0) * t) * gw * sc; val rm = (r0 + r1) / 2
        fun nrm(ax: Float, ay: Float, bx2: Float, by2: Float): FloatArray {
            val dx = bx2 - ax; val dy = by2 - ay; val l = sqrt(dx * dx + dy * dy).let { if (it == 0f) 1f else it }
            return floatArrayOf(-dy / l, dx / l)
        }
        val n0 = nrm(s.x0, s.y0, bx, byy); val n1 = nrm(bx, byy, ex, ey); val nm = nrm(s.x0, s.y0, ex, ey)
        val ax = cx + s.x0 * sc; val ay = by + s.y0 * sc; val qx = cx + bx * sc; val qy = by + byy * sc; val px = cx + ex * sc; val py = by + ey * sc
        fun shape(off: Float, w0: Float, wm: Float, w1: Float) {
            path.reset()
            path.moveTo(ax + n0[0] * w0 + off, ay + n0[1] * w0)
            path.quadTo(qx + nm[0] * wm + off, qy + nm[1] * wm, px + n1[0] * w1 + off, py + n1[1] * w1)
            path.lineTo(px - n1[0] * w1 + off, py - n1[1] * w1)
            path.quadTo(qx - nm[0] * wm + off, qy - nm[1] * wm, ax - n0[0] * w0 + off, ay - n0[1] * w0)
            path.close(); cv.drawPath(path, fill)
            cv.drawCircle(px + off, py, w1, fill)
        }
        fill.shader = null; fill.color = col; shape(0f, r0, rm, r1)
        if (r0 > 1.2f) {                                 // round it: shadow side, then the sunlit side
            fill.color = alpha(c(0x1E120E), 0.45f); shape(-side * r0 * 0.5f, r0 * 0.45f, rm * 0.45f, r1 * 0.45f)
            fill.color = alpha(hi, 0.75f); shape(side * r0 * 0.36f, r0 * 0.4f, rm * 0.4f, r1 * 0.4f)
        }
    }

    private fun vignette(cv: Canvas, w: Float, h: Float) {
        val r = max(w, h) * 0.78f
        fill.shader = RadialGradient(w / 2, h * 0.45f, r, intArrayOf(0, 0, 0x61000000), floatArrayOf(0f, min(w, h) * 0.35f / r, 1f), Shader.TileMode.CLAMP)
        cv.drawRect(0f, 0f, w, h, fill); fill.shader = null
    }

    /** How strongly petals drift for this state (0..1). */
    fun petalAmount(progress: Float, done: Boolean): Float = if (done || progress >= 1f) 1f else clamp((progress - 0.6f) / 0.4f)

    /**
     * @param progress 0 = seed, 1 = full bloom
     * @param leavesLost leaves dropped by temptation (0..Garden.MAX_LEAVES)
     * @param hour 0..24 for the sky
     * @param done lock finished: petals drift down
     */
    fun draw(cv: Canvas, w: Float, h: Float, progress: Float, leavesLost: Int, hour: Float = hourNow(), done: Boolean = false) {
        val p = clamp(progress); val lost = leavesLost.coerceIn(0, Garden.MAX_LEAVES)
        val L = light(hour); val bloom = if (done || p >= 1f) 1f else clamp((p - 0.55f) / 0.45f)
        sky(cv, w, h, L)
        ground(cv, w, h, L, bloom, lost)
        val gy = h * 0.84f; val cx = w / 2; val by = gy - h * 0.012f
        val sc = min(h * 0.6f / -minY, w * 0.8f / (maxX - minX)); val g = clamp((p - 0.05f) / 0.95f)
        val side = if (L.tt < 0.5f) -1f else 1f; val bark = tint(L, c(0x3E2A24)); val barkHi = tint(L, c(0x7A5644))
        // soft shadow under the tree
        cv.save(); cv.scale(1f, 0.18f, cx, by + h * 0.01f)
        radial(cv, cx, by + h * 0.01f, w * 0.32f * max(0.15f, g), intArrayOf(0x59000000, 0), floatArrayOf(0f, 1f)); cv.restore()

        if (p < 0.05f || g <= 0f) {                     // a seed in a small mound, then a sprout
            val t = p / 0.05f
            fill.shader = null
            fill.color = tint(L, c(0x4A3326)); oval.set(cx - h * 0.085f, by + h * 0.004f - h * 0.026f, cx + h * 0.085f, by + h * 0.004f + h * 0.026f); cv.drawOval(oval, fill)
            cv.save(); cv.rotate(Math.toDegrees(0.3).toFloat(), cx, by - h * 0.018f)
            fill.color = tint(L, c(0xA9764E)); oval.set(cx - h * 0.024f, by - h * 0.018f - h * 0.02f, cx + h * 0.024f, by - h * 0.018f + h * 0.02f); cv.drawOval(oval, fill)
            cv.restore()
            if (t > 0.5f) {
                val sl = h * 0.05f * (t - 0.5f) * 2
                line.color = tint(L, c(0x7DB26A)); line.strokeWidth = h * 0.007f
                cv.drawLine(cx, by - h * 0.03f, cx, by - h * 0.03f - sl, line)
                leafShape(cv, cx, by - h * 0.03f - sl, h * 0.03f * (t - 0.5f) * 2, -0.9f, tint(L, c(0x9BCB78)))
                leafShape(cv, cx, by - h * 0.03f - sl, h * 0.03f * (t - 0.5f) * 2, 0.9f, tint(L, c(0x8DBF6A)))
            }
            vignette(cv, w, h); return
        }

        val gw = 0.15f + 0.85f * g; val sap = clamp(g / 0.32f)
        class Open(val x: Float, val y: Float, val i: Int, val bt: Float, val s: Float, val tl: Float, val d: Int)
        val open = ArrayList<Open>()
        for (tp in tips) {
            val i = tp.i; if ((i * 7) % Garden.MAX_LEAVES < lost) continue
            val tl = clamp((g - tp.d * 0.115f) / 0.115f)
            if (g <= 0.3f + (i % 7) * 0.025f || tl < 0.4f) continue
            val at = 0.55f + tp.s * 0.4f; val bt = if (done || p >= 1f) 1f else clamp((p - at) / 0.22f)
            open += Open(cx + (tp.px + (tp.x - tp.px) * tl) * sc, by + (tp.py + (tp.y - tp.py) * tl) * sc, i, bt, tp.s, tl, tp.d)
        }
        val cr = h * 0.05f; val back = tintBloom(L, c(0xB86A8C)); val mid = tintBloom(L, c(0xE79AB6))
        for (o in open) if (o.bt > 0f) glow(cv, o.x - cr * 0.3f, o.y - cr * 0.2f, cr * 1.35f * (0.5f + o.bt * 0.5f), back, 0.5f * o.bt)

        val young = mix(tint(L, c(0x6F9F5C)), bark, sap); val youngHi = mix(tint(L, c(0xA8D488)), barkHi, sap)
        val rr = segs[0].r0 * gw * sc                    // root flare where the trunk meets the ground
        fill.shader = null; fill.color = if (sap < 1f) young else bark
        path.reset(); path.moveTo(cx - rr * 2.3f, by + h * 0.008f); path.quadTo(cx - rr * 0.95f, by - rr * 0.2f, cx - rr * 0.85f, by - rr * 2.2f)
        path.lineTo(cx + rr * 0.85f, by - rr * 2.2f); path.quadTo(cx + rr * 0.95f, by - rr * 0.2f, cx + rr * 2.3f, by + h * 0.008f); path.close()
        cv.drawPath(path, fill)
        for (s in segs) {
            val local = clamp((g - s.d * 0.115f) / 0.115f); if (local <= 0f) continue
            limb(cv, s, local, sc, cx, by, gw, if (sap < 1f) young else bark, if (sap < 1f) youngHi else barkHi, side)
            if (sap < 1f && local < 1f && local > 0.15f) {   // a sapling's growing shoots carry two small leaves
                val t2 = local
                val ex = (1 - t2) * (1 - t2) * s.x0 + 2 * (1 - t2) * t2 * s.qx + t2 * t2 * s.x1
                val ey = (1 - t2) * (1 - t2) * s.y0 + 2 * (1 - t2) * t2 * s.qy + t2 * t2 * s.y1
                val lsz = h * 0.022f * (1 - sap * 0.5f)
                leafShape(cv, cx + ex * sc, by + ey * sc, lsz, -0.8f, tint(L, c(0x9BCB78)))
                leafShape(cv, cx + ex * sc, by + ey * sc, lsz, 0.8f, tint(L, c(0x86B862)))
            }
        }
        val pal = intArrayOf(0xFFE9EF, 0xFBD0DD, 0xF6B6CA, 0xF09BB4, 0xFFF5F7).map { tintBloom(L, c(it)) }
        for (o in open) {
            val r = Rng(1000L + o.i); val bt = o.bt; val x = o.x; val y = o.y
            if (bt < 1f) {                                // fresh green leaves before the blossoms open
                val lf = tint(L, c(0x86B862)); val ls = h * 0.028f * o.tl * (1 - bt * 0.7f)
                for (k in 0 until 3) leafShape(cv, x, y, ls, -1.4f + k * 1.4f + o.s, if (k == 1) tint(L, c(0x9BCB78)) else lf)
            }
            if (bt <= 0f) continue
            val crr = cr * (0.75f + o.s * 0.6f) * (if (o.d < 5) 1.35f else 1f)
            glow(cv, x, y, crr * (0.6f + bt * 0.6f), mid, 0.55f * bt)
            val nb = (11 * bt).roundToInt()
            for (b in 0 until nb) {
                val a = r.f() * 6.283f; val d = sqrt(r.f()) * crr * (0.55f + bt * 0.5f)
                val fx = x + cos(a) * d; val fy = y + sin(a) * d * 0.8f
                val lit = clamp(0.5f - (fy - y) / (cr * 2) + side * (fx - x) / (cr * 4))
                val size = h * (0.0095f + r.f() * 0.007f) * (0.6f + bt * 0.4f)
                val col = pal[min(4, floor((1 - lit) * 4 + r.f() * 1.2f).toInt())]
                flower(cv, fx, fy, size, col, r.f() * 6.283f)
            }
        }
        if (done) {                                       // a finished tree lets a few petals go
            val rd = Rng(77)
            for (k in 0 until 16) {
                val px = w * (0.1f + rd.f() * 0.8f); val py = h * (0.18f + rd.f() * 0.6f); val ps = h * (0.012f + rd.f() * 0.006f)
                petal(cv, px, py, ps, rd.f() * 6.28f, alpha(pal[k % 4], 0.85f))
            }
        }
        if (L.day) glow(cv, sunX(L, w), sunY(L, h), w * 0.7f, c(0xFFE2B8), 0.1f + L.warm * 0.12f)
        vignette(cv, w, h)
    }

    /** Petals drifting over the scene; sec = seconds since start, amount 0..1. Cheap enough to redraw every frame. */
    fun petals(cv: Canvas, w: Float, h: Float, sec: Float, amount: Float) {
        val n = (22 * clamp(amount)).roundToInt(); if (n == 0) return
        val r = Rng(555); val cols = intArrayOf(c(0xFBD0DD), c(0xF6B6CA), c(0xFFE9EF))
        for (k in 0 until n) {
            val sp = 0.035f + r.f() * 0.05f; val ph = r.f(); val sway = 0.03f + r.f() * 0.05f; val x0 = r.f()
            val s = h * (0.011f + r.f() * 0.008f); val spin = 0.6f + r.f() * 1.4f
            val f = (ph + sec * sp) % 1f; val x = (x0 + f * 0.35f + sin(sec * 0.7f + k) * sway) % 1f; val y = -0.05f + f * 1.1f
            cv.save(); cv.translate(x * w, y * h); cv.rotate(Math.toDegrees((sec * spin + k).toDouble()).toFloat())
            cv.scale(cos(sec * spin * 1.3f + k), 1f)
            petal(cv, 0f, 0f, s, 0f, alpha(cols[k % 3], 0.9f * min(1f, (1 - f) * 6)))
            cv.restore()
        }
    }
}
