package com.maatram.hardlock

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews

/**
 * Home-screen widget: the sakura growing with the lock and a live countdown,
 * or your garden streak when no lock is running. Tap opens the app.
 * The countdown is a Chronometer (ticks on its own); the tree redraws every 5 min,
 * when a leaf drops, and when a lock starts or ends.
 */
class PlantWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) = refresh(ctx)

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        if (intent.action == ACTION_TICK) refresh(ctx)
    }

    companion object {
        private const val ACTION_TICK = "com.maatram.hardlock.WIDGET_TICK"

        fun refresh(ctx: Context) {
            try {
                val mgr = AppWidgetManager.getInstance(ctx) ?: return
                val ids = mgr.getAppWidgetIds(ComponentName(ctx, PlantWidget::class.java))
                if (ids.isEmpty()) return
                Garden.settle(ctx)
                val views = build(ctx)
                for (id in ids) mgr.updateAppWidget(id, views)
                scheduleTick(ctx)
            } catch (_: Exception) { /* widget is a bonus; never break the lock */ }
        }

        private fun build(ctx: Context): RemoteViews {
            val v = RemoteViews(ctx.packageName, R.layout.widget_plant)
            val locked = LockManager.isLocked(ctx)
            val cur = Garden.current(ctx)
            val plants = Garden.plants(ctx)
            val bmp: Bitmap
            if (locked && cur != null) {
                val total = (cur.end - cur.start).coerceAtLeast(1L)
                val progress = 1f - LockManager.remainingMs(ctx).toFloat() / total
                bmp = art(progress, cur.leaves, false)
                v.setTextViewText(R.id.w_title, "Hard Lock · growing")
                v.setViewVisibility(R.id.w_timer, View.VISIBLE)
                v.setChronometer(R.id.w_timer, SystemClock.elapsedRealtime() + LockManager.remainingMs(ctx), null, true)
                v.setChronometerCountDown(R.id.w_timer, true)
                v.setTextViewText(R.id.w_sub,
                    if (cur.leaves == 0) "No leaves lost yet" else "${cur.leaves} leaf${if (cur.leaves == 1) "" else "s"} dropped")
            } else {
                val last = plants.lastOrNull()
                bmp = if (last != null) art(1f, last.leaves, true) else art(0f, 0, false)
                val streak = Garden.streak(plants)
                v.setTextViewText(R.id.w_title, if (streak > 0) "$streak day streak" else "My garden")
                v.setViewVisibility(R.id.w_timer, View.GONE)
                v.setChronometer(R.id.w_timer, SystemClock.elapsedRealtime(), null, false)
                v.setTextViewText(R.id.w_sub,
                    if (plants.isEmpty()) "Tap to plant your first tree" else "${plants.size} tree${if (plants.size == 1) "" else "s"} · tap to grow one")
            }
            v.setImageViewBitmap(R.id.w_plant, bmp)
            val open = PendingIntent.getActivity(
                ctx, 4001,
                Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            v.setOnClickPendingIntent(R.id.w_root, open)
            return v
        }

        /** 360x300 bitmap with rounded corners (RemoteViews can't clip). */
        private fun art(progress: Float, leaves: Int, done: Boolean): Bitmap {
            val w = 360; val h = 300
            val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            val clip = Path().apply { addRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), 36f, 36f, Path.Direction.CW) }
            c.clipPath(clip)
            PlantArt.draw(c, w.toFloat(), h.toFloat(), progress, leaves, PlantArt.hourNow(), done)
            return b
        }

        private fun tickIntent(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
            ctx, 4002, Intent(ctx, PlantWidget::class.java).setAction(ACTION_TICK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        /** While locked, redraw the tree about every 5 minutes and right at the end. Inexact: battery-friendly. */
        private fun scheduleTick(ctx: Context) {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = tickIntent(ctx)
            am.cancel(pi)
            if (!LockManager.isLocked(ctx)) return
            val left = LockManager.remainingMs(ctx)
            val next = System.currentTimeMillis() + minOf(5 * 60_000L, left + 2_000L)
            am.set(AlarmManager.RTC, next, pi)
        }
    }
}
