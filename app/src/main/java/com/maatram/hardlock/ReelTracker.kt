package com.maatram.hardlock

import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import java.util.Calendar

/**
 * Counts reels (short videos) watched in Instagram, YouTube, Facebook, Snapchat and X.
 *
 * Method: each swipe to the next reel fires a scroll event in the app. One swipe fires
 * several scroll events, so a new reel is counted only after [IDLE_MS] with no scroll.
 * Opening an app is NOT a reel; app switches and UI refreshes never count.
 * Limit: any scroll in those apps counts, so a scroll in comments or a feed can count too.
 * Resets every day (local time).
 */
object ReelTracker {
    val APPS = mapOf(
        "com.instagram.android" to "Instagram",
        "com.google.android.youtube" to "YouTube",
        "com.facebook.katana" to "Facebook",
        "com.snapchat.android" to "Snapchat",
        "com.twitter.android" to "X",
    )
    const val IDLE_MS = 800L
    private const val PREFS = "ReelCount"

    private var lastScrollAt = 0L

    /** Pure rule, unit tested: a scroll starts a new reel only after a quiet gap. */
    fun startsNewReel(lastScrollAt: Long, now: Long): Boolean = now - lastScrollAt > IDLE_MS

    /** Called for every scroll event from the accessibility service. */
    fun onScroll(ctx: Context, event: AccessibilityEvent, fgPkg: String) {
        if (event.packageName?.toString() != fgPkg || fgPkg !in APPS) return
        if (LockManager.isLocked(ctx)) return
        // Ignore scroll events that did not move the list (some apps send zero-delta events).
        if (android.os.Build.VERSION.SDK_INT >= 29 && event.scrollDeltaY == 0 && event.fromIndex == event.toIndex) return
        val now = System.currentTimeMillis()
        val isNew = startsNewReel(lastScrollAt, now)
        lastScrollAt = now
        if (!isNew) return
        val n = bump(ctx)
        ReelPill.update(n)
    }

    /** Shows the pill while a tracked app is in front, hides it otherwise. Runs on window changes. */
    fun sync(ctx: Context, fgPkg: String) {
        if (fgPkg in APPS && !LockManager.isLocked(ctx)) ReelPill.show(ctx, count(ctx)) else ReelPill.hide()
    }

    fun count(ctx: Context): Int {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return if (sp.getString("day", "") == today()) sp.getInt("count", 0) else 0
    }

    private fun bump(ctx: Context): Int {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val n = (if (sp.getString("day", "") == today()) sp.getInt("count", 0) else 0) + 1
        sp.edit().putString("day", today()).putInt("count", n).apply()
        return n
    }

    private fun today(): String {
        val c = Calendar.getInstance()
        return "%04d-%02d-%02d".format(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    /** Tap target for the pill: opens Maatram's main screen. */
    fun openApp(ctx: Context) {
        ctx.startActivity(Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }
}
