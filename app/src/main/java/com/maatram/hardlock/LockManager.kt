package com.maatram.hardlock

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Single source of truth for the lock. State lives in SharedPreferences so it
 * survives the app being killed or the phone rebooting. Time-based, so the lock
 * always ends on its own even if every other component fails.
 */
object LockManager {

    private const val PREFS = "MaatramLock"
    private const val KEY_END = "lock_end"          // epoch millis, 0 = not locked
    private const val KEY_APPS = "locked_apps"      // exactly the apps the user picked
    const val MAX_MINUTES = 90

    // Pre-ticked in the picker until the user saves their own choice. Not forced.
    val DEFAULTS: Set<String> = setOf(
        "com.instagram.android", "com.instagram.lite", "com.instagram.barcelona",
        "com.zhiliaoapp.musically", "com.ss.android.ugc.trill",
        "com.snapchat.android",
        "com.google.android.youtube", "com.google.android.apps.youtube.music",
        "com.facebook.katana", "com.facebook.lite",
        "com.twitter.android",
        "com.reddit.frontpage"
    )

    // Settings/installer screens bounced while locked, so the shield can't be
    // switched off, the app can't be force-stopped, and it can't be uninstalled.
    val GUARDED: Set<String> = setOf(
        "com.android.settings",
        "com.android.settings.intelligence",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.miui.securitycenter",           // Xiaomi
        "com.samsung.android.sm"             // Samsung device care
    )

    fun isLocked(ctx: Context): Boolean = remainingMs(ctx) > 0L

    fun endTime(ctx: Context): Long =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_END, 0L)

    fun remainingMs(ctx: Context): Long {
        val end = endTime(ctx)
        val left = end - System.currentTimeMillis()
        return if (left > 0L) left else 0L
    }

    /** The apps locked during a Hard Lock: the user's picks, or DEFAULTS if never picked. */
    fun lockedApps(ctx: Context): Set<String> {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return if (p.contains(KEY_APPS)) p.getStringSet(KEY_APPS, emptySet()) ?: emptySet()
        else DEFAULTS
    }

    fun setLockedApps(ctx: Context, pkgs: Set<String>) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY_APPS, HashSet(pkgs)).apply()
    }

    // Only the picked apps, plus Settings/installer so the lock can't be removed.
    fun isBlocked(ctx: Context, pkg: String): Boolean =
        pkg in GUARDED || pkg in lockedApps(ctx)

    /** Starts a lock for [minutes] (clamped to 1..MAX_MINUTES) and arms the end alarm. */
    fun start(ctx: Context, minutes: Int) {
        val m = minutes.coerceIn(1, MAX_MINUTES)
        val end = System.currentTimeMillis() + m * 60_000L
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(KEY_END, end).apply()
        scheduleEnd(ctx, end)
    }

    /** Clears the lock. Not callable from the UI while a lock is active. */
    fun clear(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(KEY_END, 0L).apply()
        cancelEnd(ctx)
    }

    /** Re-arm the end alarm after a reboot, or clear if the time already passed. */
    fun reconcileAfterBoot(ctx: Context) {
        val end = endTime(ctx)
        if (end <= System.currentTimeMillis()) clear(ctx) else scheduleEnd(ctx, end)
    }

    private fun endIntent(ctx: Context): PendingIntent {
        val i = Intent(ctx, LockEndReceiver::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(ctx, 1001, i, flags)
    }

    private fun scheduleEnd(ctx: Context, end: Long) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = endIntent(ctx)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                am.set(AlarmManager.RTC_WAKEUP, end, pi)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, end, pi)
            }
        } catch (_: SecurityException) {
            am.set(AlarmManager.RTC_WAKEUP, end, pi)
        }
    }

    private fun cancelEnd(ctx: Context) {
        (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(endIntent(ctx))
    }
}
