package com.maatram.hardlock

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The sakura scene: a 15-frame animated clip (supplied by the Maatram team) that "blooms" as the
 * lock runs. Growth 0 = bare, grey and dim; growth 1 = the full, glowing tree. The sky tint follows
 * the real time of day and leaves dropped by temptation lie on the ground.
 *
 * Rule: any finished lock grows a blooming tree; a 90-minute lock grows a full tree.
 * Plain android.graphics, so the app screen and the home-screen widget draw it the same way.
 */
object SakuraArt {
    const val FULL_MINUTES = 90
    const val BLOOM = 0.8f                 // where a blooming (not full) tree stops
    const val FRAME_MS = 170L
    val FRAMES = intArrayOf(
        R.drawable.sakura_00, R.drawable.sakura_01, R.drawable.sakura_02, R.drawable.sakura_03, R.drawable.sakura_04,
        R.drawable.sakura_05, R.drawable.sakura_06, R.drawable.sakura_07, R.drawable.sakura_08, R.drawable.sakura_09,
        R.drawable.sakura_10, R.drawable.sakura_11, R.drawable.sakura_12, R.drawable.sakura_13, R.drawable.sakura_14,
    )

    /** How far this lock's tree grows: a full tree only for 90+ minute locks. */
    fun target(minutes: Int): Float = if (minutes >= FULL_MINUTES) 1f else BLOOM
    /** Growth while a lock runs, from its progress (0..1) and length in minutes. */
    fun growth(progress: Float, minutes: Int): Float = progress.coerceIn(0f, 1f) * target(minutes)
    fun isFull(minutes: Int) = minutes >= FULL_MINUTES

    fun stage(g: Float): String = when {
        g < 0.12f -> "Seed planted"
        g < 0.35f -> "Sprouting"
        g < 0.6f -> "Growing"
        g < 0.95f -> "Blooming"
        else -> "Full tree"
    }

    fun hourNow(): Float = Calendar.getInstance().run { get(Calendar.HOUR_OF_DAY) + get(Calendar.MINUTE) / 60f }

    private var cache: List<Bitmap>? = null
    /** Decoded once per process (about 4 MB). Call off the main thread. */
    fun frames(ctx: Context): List<Bitmap> = cache ?: FRAMES.map { BitmapFactory.decodeResource(ctx.resources, it) }.also { cache = it }
    fun firstFrame(ctx: Context): Bitmap = cache?.first() ?: BitmapFactory.decodeResource(ctx.resources, FRAMES[0])

    /** Colour for growth g: the tree's colour and light come in as it grows. */
    fun matrix(g: Float): ColorMatrix {
        val gg = g.coerceIn(0f, 1f)
        val m = ColorMatrix().apply { setSaturation(0.1f + 0.9f * gg) }
        val b = 0.5f + 0.5f * gg
        m.postConcat(ColorMatrix(floatArrayOf(b, 0f, 0f, 0f, 0f, 0f, b, 0f, 0f, 0f, 0f, 0f, b, 0f, 0f, 0f, 0f, 0f, 1f, 0f)))
        return m
    }

    /** Sky tint for the hour: deep blue at night, warm gold at sunrise and sunset, none by day. ARGB. */
    fun tint(hour: Float): Int {
        val h = ((hour % 24f) + 24f) % 24f
        val night = when { h < 5f || h > 20.3f -> 1f; h < 6.5f -> (6.5f - h) / 1.5f; h > 18.8f -> (h - 18.8f) / 1.5f; else -> 0f }.coerceIn(0f, 1f)
        val warm = max(0f, max(1 - abs(h - 6.6f) / 1.2f, 1 - abs(h - 18.1f) / 1.3f)).coerceIn(0f, 1f)
        return if (night > 0.01f) ((night * 0.5f * 255).toInt() shl 24) or 0x0B1440
               else ((warm * 0.22f * 255).toInt() shl 24) or 0xFF8A3D
    }

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    /** Draws one frame filling w x h (centre crop), coloured for growth g, tinted for the hour, with dropped leaves. */
    fun draw(cv: Canvas, w: Float, h: Float, frame: Bitmap, g: Float, leaves: Int, hour: Float = hourNow()) {
        val fw = frame.width.toFloat(); val fh = frame.height.toFloat()
        val s = max(w / fw, h / fh); val sw = w / s; val sh = h / s
        val src = Rect(((fw - sw) / 2).toInt(), ((fh - sh) / 2).toInt(), ((fw + sw) / 2).toInt(), ((fh + sh) / 2).toInt())
        synchronized(this) {
            paint.colorFilter = ColorMatrixColorFilter(matrix(g))
            cv.drawBitmap(frame, src, RectF(0f, 0f, w, h), paint)
            fill.color = tint(hour); cv.drawRect(0f, 0f, w, h, fill)
            for (k in 0 until min(leaves, Garden.MAX_LEAVES)) {      // fallen leaves on the meadow
                val x = w * (0.18f + ((k * 41) % 64) / 100f); val y = h * (0.86f + ((k * 23) % 9) / 100f); val sz = h * 0.05f
                cv.save(); cv.translate(x, y); cv.rotate(70f + (k % 3) * 30f)
                path.reset(); path.moveTo(0f, 0f); path.quadTo(sz * 0.55f, -sz * 0.45f, 0f, -sz); path.quadTo(-sz * 0.55f, -sz * 0.45f, 0f, 0f)
                fill.color = if (k % 2 == 1) 0xFFC9963E.toInt() else 0xFFA8722E.toInt(); cv.drawPath(path, fill)
                cv.restore()
            }
        }
    }

    /** Still picture for the widget: rounded corners baked in (RemoteViews can't clip). */
    fun bitmap(ctx: Context, w: Int, h: Int, g: Float, leaves: Int): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.clipPath(Path().apply { addRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), 60f, 60f, Path.Direction.CW) })
        draw(c, w.toFloat(), h.toFloat(), firstFrame(ctx), g, leaves)
        return b
    }
}
