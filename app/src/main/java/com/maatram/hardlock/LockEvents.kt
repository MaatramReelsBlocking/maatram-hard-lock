package com.maatram.hardlock

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

/**
 * Hard Lock event notifications. Only these, nothing else:
 * lock started, a meaningful time milestone, lock ended, a user-scheduled lock
 * beginning, and important state changes or errors.
 */
object LockEvents {
    private const val CHANNEL = "lock_events"
    private const val ID_START = 3001
    private const val ID_MILESTONE = 3002
    private const val ID_END = 3003
    private const val ID_ERROR = 3004
    private const val PREFS = "MaatramLock"
    private const val KEY_TOTAL = "lock_total_min"
    const val ACTION_MILESTONE = "com.maatram.hardlock.MILESTONE"
    const val ACTION_SCHEDULED = "com.maatram.hardlock.SCHEDULED"
    private const val EXTRA_END = "end"
    private const val EXTRA_LEFT = "left"

    private fun nm(ctx: Context): NotificationManager {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Hard Lock updates", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Lock started, time left, lock ended, scheduled locks and problems" }
        )
        return nm
    }

    private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 3005,
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun post(ctx: Context, id: Int, title: String, text: String, tap: PendingIntent = openApp(ctx)) {
        try {
            val n = Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(tap)
                .setAutoCancel(true)
                .build()
            nm(ctx).notify(id, n)
        } catch (_: SecurityException) { /* notifications not allowed; the lock still works */ }
    }

    private fun clock(ms: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ms))

    /** Lock started (manual, quick-lock or scheduled). Also arms the milestone alerts. */
    fun started(ctx: Context, minutes: Int, end: Long, scheduled: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_TOTAL, minutes).apply()
        nm(ctx).cancel(ID_END)
        post(
            ctx, ID_START,
            if (scheduled) "Scheduled Hard Lock started" else "Hard Lock started",
            "$minutes min · your chosen apps are locked until ${clock(end)}."
        )
        armMilestones(ctx, minutes, end)
    }

    /** Halfway for 30+ min locks, 5 min left for 15+ min locks, 1 min left for short ones. */
    private fun milestones(total: Int): List<Int> = buildList {
        if (total >= 30) add(total / 2)
        if (total >= 15) add(5)
        if (total in 3..14) add(1)
    }

    private fun milestoneIntent(ctx: Context, left: Int, end: Long): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, 3100 + left,
            Intent(ctx, LockEventReceiver::class.java).setAction(ACTION_MILESTONE)
                .putExtra(EXTRA_END, end).putExtra(EXTRA_LEFT, left),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun armMilestones(ctx: Context, total: Int, end: Long) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        for (left in milestones(total)) {
            val at = end - left * 60_000L
            if (at <= System.currentTimeMillis()) continue
            val pi = milestoneIntent(ctx, left, end)
            try {
                if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                else am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } catch (_: SecurityException) { am.set(AlarmManager.RTC_WAKEUP, at, pi) }
        }
    }

    /** After a reboot: re-arm the alerts still ahead for the running lock. */
    fun rearm(ctx: Context) {
        if (!LockManager.isLocked(ctx)) return
        val total = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_TOTAL, 0)
        if (total > 0) armMilestones(ctx, total, LockManager.endTime(ctx))
    }

    fun milestone(ctx: Context, end: Long, left: Int) {
        // Ignore an alert left over from an older lock.
        if (!LockManager.isLocked(ctx) || LockManager.endTime(ctx) != end) return
        val total = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_TOTAL, 0)
        val title = if (total >= 30 && left == total / 2) "Halfway there" else "$left min left"
        post(ctx, ID_MILESTONE, title, "$left min to go. Your apps unlock at ${clock(end)}. Keep going.")
    }

    fun ended(ctx: Context) {
        val total = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_TOTAL, 0)
        val nm = nm(ctx)
        nm.cancel(ID_START); nm.cancel(ID_MILESTONE)
        post(ctx, ID_END, "Your sakura bloomed", (if (total > 0) "$total min done. " else "") + "A new tree is in your garden and your apps are unlocked.")
    }

    fun error(ctx: Context, title: String, text: String, tap: PendingIntent = openApp(ctx)) =
        post(ctx, ID_ERROR, title, text, tap)

    /** The Shield stopped while a lock is still running: nothing is blocked until it is back. */
    fun shieldOff(ctx: Context) {
        if (!LockManager.isLocked(ctx)) return
        val tap = PendingIntent.getActivity(
            ctx, 3006,
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        error(ctx, "Shield switched off", "Your Hard Lock is still running, but apps are not blocked. Tap to turn the Shield back on.", tap)
    }
}

/** One lock the user schedules: a start time, a length, and optionally every day. */
object LockSchedule {
    private const val PREFS = "MaatramLock"
    data class Plan(val on: Boolean, val hour: Int, val minute: Int, val minutes: Int, val daily: Boolean)

    fun get(ctx: Context): Plan {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Plan(
            p.getBoolean("sched_on", false), p.getInt("sched_h", 18), p.getInt("sched_m", 0),
            p.getInt("sched_min", 60), p.getBoolean("sched_daily", false)
        )
    }

    fun set(ctx: Context, plan: Plan) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("sched_on", plan.on).putInt("sched_h", plan.hour).putInt("sched_m", plan.minute)
            .putInt("sched_min", plan.minutes.coerceIn(1, LockManager.MAX_MINUTES)).putBoolean("sched_daily", plan.daily)
            .apply()
        arm(ctx)
    }

    private fun intent(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, 3200,
        Intent(ctx, LockEventReceiver::class.java).setAction(LockEvents.ACTION_SCHEDULED),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    fun nextAt(plan: Plan): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, plan.hour); set(Calendar.MINUTE, plan.minute)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
    }.timeInMillis

    /** Arms (or cancels) the alarm for the next scheduled start. Exact when allowed, since the user picked the time. */
    fun arm(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = intent(ctx)
        am.cancel(pi)
        val plan = get(ctx)
        if (!plan.on) return
        val at = nextAt(plan)
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (_: SecurityException) { am.set(AlarmManager.RTC_WAKEUP, at, pi) }
    }

    fun fire(ctx: Context) {
        val plan = get(ctx)
        if (!plan.on) return
        when {
            LockManager.isLocked(ctx) ->
                LockEvents.error(ctx, "Scheduled lock skipped", "A Hard Lock was already running at ${"%02d:%02d".format(plan.hour, plan.minute)}.")
            LockManager.canLock(ctx) -> LockManager.start(ctx, plan.minutes, scheduled = true)
            else -> LockEvents.error(
                ctx, "Scheduled Hard Lock couldn't start",
                "Open Maatram Hard Lock and finish setup: turn on the Shield, allow background running and pick apps to lock."
            )
        }
        if (plan.daily) arm(ctx)
        else ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("sched_on", false).apply()
    }
}

class LockEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val i = intent ?: return
        when (i.action) {
            LockEvents.ACTION_MILESTONE ->
                LockEvents.milestone(context, i.getLongExtra("end", 0L), i.getIntExtra("left", 0))
            LockEvents.ACTION_SCHEDULED -> LockSchedule.fire(context)
        }
    }
}
