package com.maatram.hardlock

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock

/**
 * Single source of truth for the lock. State lives in SharedPreferences so it
 * survives the app being killed or the phone rebooting. Time-based, so the lock
 * always ends on its own even if every other component fails.
 */
object LockManager {

    private const val PREFS = "MaatramLock"
    private const val KEY_END = "lock_end"          // epoch millis, 0 = not locked
    private const val KEY_APPS = "locked_apps"      // exactly the apps the user picked
    private const val KEY_END_RT = "lock_end_rt"    // end on the uptime clock (immune to clock changes)
    private const val KEY_BOOT = "lock_boot"        // boot the uptime end belongs to
    private const val KEY_LEFT = "lock_left"        // time left at the last checkpoint, for after a reboot
    const val MAX_MINUTES = 90

    // Settings-type apps. Never locked as a whole: while a lock runs, only their
    // pages that show "Maatram" (Shield toggle, App info / Force stop, Device
    // admin, uninstall dialog) are blocked. Wi-Fi, brightness etc. keep working.
    val GUARDED: Set<String> = setOf(
        "com.android.settings",
        "com.android.settings.intelligence",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.miui.packageinstaller",
        "com.miui.securitycenter",
        "com.samsung.android.sm",
        "com.samsung.android.lool",
        // OEM battery managers: their per-app page can restrict Maatram and stop the Shield.
        "com.miui.powerkeeper",
        "com.coloros.safecenter",
        "com.huawei.systemmanager",
        // The volume-key accessibility shortcut picker runs in the system ("android") package.
        "android",
    )

    fun isLocked(ctx: Context): Boolean = remainingMs(ctx) > 0L

    fun endTime(ctx: Context): Long =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_END, 0L)

    private fun boot(ctx: Context) =
        android.provider.Settings.Global.getInt(ctx.contentResolver, android.provider.Settings.Global.BOOT_COUNT, -1)

    /** Pins the end to the uptime clock, so changing the date/time in Settings can't end (or stretch) the lock. */
    private fun anchor(ctx: Context, leftMs: Long) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_END_RT, SystemClock.elapsedRealtime() + leftMs).putInt(KEY_BOOT, boot(ctx))
            .putLong(KEY_LEFT, leftMs).apply()
    }

    /** Saves the time left (the Shield calls this about once a minute), so a reboot resumes from here. */
    fun checkpoint(ctx: Context) {
        val left = remainingMs(ctx)
        if (left > 0L) ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(KEY_LEFT, left).apply()
    }

    fun remainingMs(ctx: Context): Long {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val b = boot(ctx)
        val sameBoot = b >= 0 && p.getInt(KEY_BOOT, -2) == b
        val ms = remaining(p.getLong(KEY_END, 0L), p.getLong(KEY_END_RT, 0L), sameBoot,
            SystemClock.elapsedRealtime(), System.currentTimeMillis(), if (b >= 0) p.getLong(KEY_LEFT, 0L) else 0L)
        if (b >= 0 && !sameBoot && ms > 0L) anchor(ctx, ms)   // first look after a reboot: count down from here
        // Finished: drop the checkpoint so a later reboot can't revive the lock.
        if (sameBoot && ms == 0L && p.getLong(KEY_LEFT, 0L) > 0L) p.edit().putLong(KEY_LEFT, 0L).apply()
        return ms
    }

    /**
     * Pure part of [remainingMs], capped. Same boot: the uptime end. After a reboot: the time left at the
     * last checkpoint, so moving the clock forward and restarting can't end the lock (time the phone is
     * off doesn't count). Wall-clock end only for a lock saved by an older version (no checkpoint).
     */
    internal fun remaining(end: Long, endRt: Long, sameBoot: Boolean, elapsed: Long, wall: Long, left: Long = 0L): Long {
        if (end <= 0L) return 0L
        val ms = when {
            endRt > 0L && sameBoot -> endRt - elapsed
            left > 0L -> left
            else -> end - wall
        }
        return ms.coerceIn(0L, MAX_MINUTES * 60_000L + 60_000L)
    }

    /** Exactly the apps the user ticked. Nothing pre-ticked, nothing added. */
    fun lockedApps(ctx: Context): Set<String> {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // One-time reset: older versions pre-ticked a default list (YouTube,
        // Instagram...) that got saved with the user's own picks. Start clean.
        if (!p.getBoolean("picks_v2", false) && !isLocked(ctx)) {
            p.edit().remove(KEY_APPS).putBoolean("picks_v2", true).apply()
        }
        return p.getStringSet(KEY_APPS, emptySet()) ?: emptySet()
    }

    /** While a lock runs apps can only be added, never removed. */
    fun setLockedApps(ctx: Context, pkgs: Set<String>) {
        val keep = if (isLocked(ctx)) pkgs + lockedApps(ctx) else pkgs
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY_APPS, HashSet(keep)).apply()
    }

    /** Only the picked apps. Settings pages are handled by BlockerService's guard. */
    fun isBlocked(ctx: Context, pkg: String): Boolean = pkg in lockedApps(ctx)

    /** Starts a lock for [minutes] (clamped to 1..MAX_MINUTES), arms the end alarm and notifies. */
    fun start(ctx: Context, minutes: Int, scheduled: Boolean = false) {
        val m = minutes.coerceIn(1, MAX_MINUTES)
        // Never shorten a running lock (a linked one can arrive while the screen still shows Start).
        val now = System.currentTimeMillis()
        startUntil(ctx, now + maxOf(m * 60_000L, remainingMs(ctx)), m, scheduled)
    }

    /**
     * Locks until [end]. [fromLink] = started on a linked device (website / Chrome), so it is not
     * pushed back; a lock started on this phone is pushed so the linked devices lock too.
     */
    fun startUntil(ctx: Context, end: Long, minutes: Int, scheduled: Boolean = false, fromLink: Boolean = false) {
        val m = minutes.coerceIn(1, MAX_MINUTES)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(KEY_END, end).apply()
        anchor(ctx, end - System.currentTimeMillis())
        scheduleEnd(ctx, end)
        Garden.begin(ctx, m, end)
        // A linked device can extend a running lock: report the whole lock, not just the new part.
        LockEvents.started(ctx, Garden.current(ctx)?.minutes ?: m, end, scheduled)
        if (!fromLink) Link.push(ctx, m)
        BlockerService.lockStarted()
    }

    /**
     * True when the Shield is on an accessibility shortcut (volume keys, accessibility button or gesture,
     * Quick Settings): one press would switch it off mid-lock. Unreadable settings count as no shortcut.
     */
    fun shieldOnShortcut(ctx: Context): Boolean = listOf(
        "accessibility_shortcut_target_service", "accessibility_button_targets",
        "accessibility_button_target_component", "accessibility_qs_targets",
    ).any { key ->
        try {
            android.provider.Settings.Secure.getString(ctx.contentResolver, key)?.contains(ctx.packageName + "/") == true
        } catch (_: Exception) { false }
    }

    /** Same checks as the Start button: Shield on and off every shortcut, background running allowed, at least one installed app picked. */
    fun canLock(ctx: Context): Boolean {
        val flat = android.provider.Settings.Secure.getString(
            ctx.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val me = android.content.ComponentName(ctx, BlockerService::class.java).flattenToString()
        val shield = flat.split(':').any { it.equals(me, ignoreCase = true) }
        val battery = (ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager)
            .isIgnoringBatteryOptimizations(ctx.packageName)
        val apps = lockedApps(ctx).any { ctx.packageManager.getLaunchIntentForPackage(it) != null }
        return shield && !shieldOnShortcut(ctx) && battery && apps
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
        val planted = Garden.settle(ctx); PlantWidget.refresh(ctx)
        val rem = remainingMs(ctx)
        if (end <= 0L || rem <= 0L) {
            clear(ctx)
            // The end alarm died with the reboot, so LockEndReceiver never ran: say so now.
            if (planted) LockEvents.ended(ctx)
        } else {
            // Time the phone was off doesn't count (and a clock change doesn't move the lock), so the
            // lock now ends rem from now: move the stored end, the growing tree and the alerts with it.
            val resumed = System.currentTimeMillis() + rem
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(KEY_END, resumed).apply()
            Garden.follow(ctx, resumed)
            anchor(ctx, rem); scheduleEnd(ctx, resumed); LockEvents.rearm(ctx)
        }
    }

    private fun endIntent(ctx: Context): PendingIntent {
        val i = Intent(ctx, LockEndReceiver::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(ctx, 1001, i, flags)
    }

    private fun scheduleEnd(ctx: Context, end: Long) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = endIntent(ctx)
        val at = SystemClock.elapsedRealtime() + remainingMs(ctx)    // uptime clock: clock changes don't move it
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
            }
        } catch (_: SecurityException) {
            am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
        }
    }

    private fun cancelEnd(ctx: Context) {
        (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(endIntent(ctx))
    }
}
