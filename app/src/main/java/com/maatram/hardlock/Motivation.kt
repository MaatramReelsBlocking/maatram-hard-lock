package com.maatram.hardlock

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import java.util.Calendar

/**
 * Motivation reminders: a few times a day, a short line that nudges the user
 * to start a Hard Lock. Tapping "Lock 25 min" starts one straight away when the
 * Shield is ready; otherwise it opens the app to finish setup.
 */
object Motivation {
    private const val PREFS = "MaatramLock"
    private const val KEY_ON = "motivate_on"
    private const val CHANNEL = "motivation"
    private const val NOTIF_ID = 2001
    const val ACTION_SHOW = "com.maatram.hardlock.MOTIVATE"
    const val EXTRA_QUICK_LOCK = "quick_lock"
    const val QUICK_MINUTES = 25

    // Hours (24h) when a reminder is shown: mid-morning, after school, evening study.
    private val TIMES = listOf(10 to 0, 16 to 30, 20 to 0)

    // Original lines written for Maatram. Each one points back to the Hard Lock.
    val LINES = listOf(
        "Your future self is watching. Lock the apps for 25 minutes and give them something to thank you for.",
        "The reel will still be there later. This hour won't. Start a Hard Lock.",
        "Small locks, big change. 25 focused minutes beat 2 distracted hours.",
        "You don't need more motivation. You need fewer distractions. Lock them out.",
        "Scrolling feels like rest. Focus actually is. Try one Hard Lock now.",
        "Every lock you finish is a promise you kept to yourself.",
        "Champions train when nobody is watching. Put the phone on Hard Lock and get to work.",
        "One chapter. One problem set. One Hard Lock. Begin.",
        "Boredom is where good ideas start. Lock the feed and find out.",
        "Your attention is the most valuable thing you own. Don't give it away for free.",
        "Start before you feel ready. A 25-minute lock is enough to get going.",
        "The best time to focus was this morning. The next best time is now.",
        "Discipline is choosing what you want most over what you want right now.",
        "Five minutes of scrolling turns into fifty. A Hard Lock turns it into progress.",
        "Exams don't care about streaks. Lock the apps and study.",
        "You are one focused hour away from a better day.",
        "Turn the noise off. Turn your goals on.",
        "Done is better than perfect. Lock in and get it done.",
        "Your phone can wait. Your dreams are on a deadline.",
        "Be the person who finishes. Start a Hard Lock.",
        "Less screen, more you. Bringing a change in you starts with one lock.",
        "Progress is quiet. Give it 25 minutes of silence.",
        "Each lock is a rep for your focus muscle. Do one more.",
        "Notifications will fight for your time. Win this round with a Hard Lock.",
        "Tomorrow's results are built in today's quiet hours."
    )

    fun isOn(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ON, false)

    fun setOn(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ON, on).apply()
        if (on) scheduleNext(ctx) else cancel(ctx)
    }

    private fun alarmIntent(ctx: Context): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, 2002,
            Intent(ctx, MotivationReceiver::class.java).setAction(ACTION_SHOW),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /** Arms the next reminder. Inexact on purpose: no exact-alarm permission needed, kind to battery. */
    fun scheduleNext(ctx: Context) {
        if (!isOn(ctx)) return
        val now = Calendar.getInstance()
        val next = TIMES.map { (h, m) ->
            Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                if (!after(now)) add(Calendar.DAY_OF_YEAR, 1)
            }
        }.minByOrNull { it.timeInMillis } ?: return
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.set(AlarmManager.RTC_WAKEUP, next.timeInMillis, alarmIntent(ctx))
    }

    private fun cancel(ctx: Context) {
        (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(alarmIntent(ctx))
        ctx.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
    }

    /** Posts one reminder, unless a lock is already running. */
    fun show(ctx: Context) {
        if (!isOn(ctx) || LockManager.isLocked(ctx)) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 33 && !nm.areNotificationsEnabled()) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Motivation reminders", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "A few short nudges a day to start a Hard Lock" }
        )
        val line = LINES.random()
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = PendingIntent.getActivity(
            ctx, 2003,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            flags
        )
        // Opens the app directly (no receiver trampoline, which Android 12+ blocks).
        val lock = PendingIntent.getActivity(
            ctx, 2004,
            Intent(ctx, MainActivity::class.java)
                .putExtra(EXTRA_QUICK_LOCK, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            flags
        )
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Time to focus")
            .setContentText(line)
            .setStyle(Notification.BigTextStyle().bigText(line))
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(Notification.Action.Builder(Icon.createWithResource(ctx, R.drawable.ic_launcher_foreground), "Lock $QUICK_MINUTES min", lock).build())
            .build()
        nm.notify(NOTIF_ID, n)
    }

    /** Called by MainActivity for "Lock 25 min": starts only if the Shield can actually block,
     *  otherwise the app simply opens on the setup screen. */
    fun handleIntent(ctx: Context, intent: Intent?) {
        if (intent == null || !intent.getBooleanExtra(EXTRA_QUICK_LOCK, false)) return
        intent.removeExtra(EXTRA_QUICK_LOCK)
        ctx.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
        if (LockManager.canLock(ctx) && !LockManager.isLocked(ctx)) LockManager.start(ctx, QUICK_MINUTES)
    }
}

class MotivationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Motivation.ACTION_SHOW) { Motivation.show(context); Motivation.scheduleNext(context) }
    }
}
