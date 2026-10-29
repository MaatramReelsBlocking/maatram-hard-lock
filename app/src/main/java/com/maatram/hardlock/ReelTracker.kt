package com.maatram.hardlock

import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Calendar

/**
 * Counts reels (short videos) watched in Instagram, YouTube, Facebook, Snapchat and X.
 *
 * Method: a reel is identified by its on-screen text (captions, account names, long labels).
 * When that text changes in a tracked app, the user moved to a new reel and the count goes up.
 * Likes and other short counters are ignored because only long text is compared.
 * Several events fire per swipe, so the count waits [IDLE_MS] between reels.
 * Opening an app sets the baseline and does not count. Resets every day (local time).
 *
 * Limit: apps that show little text on a reel (or ads with none) can miss a swipe, and a
 * feed or comments screen with changing text can add extra counts.
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
    private const val CHECK_EVERY_MS = 300L
    private const val MIN_TEXT = 12
    private const val PREFS = "ReelCount"

    private var lastApp = ""
    private var baseline: Set<String> = emptySet()
    private var lastCountAt = 0L
    private var lastCheckAt = 0L

    /** Pure rule, unit tested: a new reel starts only after a quiet gap. */
    fun startsNewReel(lastCountAt: Long, now: Long): Boolean = now - lastCountAt > IDLE_MS

    /** Pure rule, unit tested: two or more new long texts on screen means a different reel. One changed label does not. */
    fun isNewReel(old: Set<String>, new: Set<String>): Boolean = (new - old).size >= 2

    /** Called for scroll and content-change events from the accessibility service. */
    fun onActivity(ctx: Context, root: AccessibilityNodeInfo?, fgPkg: String) {
        if (fgPkg !in APPS || LockManager.isLocked(ctx)) return
        val now = System.currentTimeMillis()
        if (now - lastCheckAt < CHECK_EVERY_MS) return
        lastCheckAt = now
        val now2 = fingerprint(root)
        if (now2.isEmpty()) return
        if (baseline.isEmpty()) { baseline = now2; return }
        if (!isNewReel(baseline, now2) || !startsNewReel(lastCountAt, now)) return
        lastCountAt = now
        baseline = now2
        ReelPill.update(bump(ctx))
    }

    /** Shows the pill while a tracked app is in front, hides it otherwise. Runs on window changes. */
    fun sync(ctx: Context, fgPkg: String) {
        if (fgPkg != lastApp) { lastApp = fgPkg; baseline = emptySet() }
        if (fgPkg in APPS && !LockManager.isLocked(ctx)) ReelPill.show(ctx, count(ctx)) else ReelPill.hide()
    }

    /** Long visible texts on screen: captions, account names, labels. Short counters are skipped. */
    private fun fingerprint(root: AccessibilityNodeInfo?): Set<String> {
        val out = HashSet<String>()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 14 || out.size > 80) return
            val t = (n.text ?: n.contentDescription)?.toString()?.trim()
            if (t != null && t.length >= MIN_TEXT && n.isVisibleToUser) out.add(t)
            for (i in 0 until n.childCount) {
                val child = n.getChild(i)
                walk(child, depth + 1)
                if (Build.VERSION.SDK_INT < 33) recycleNode(child)
            }
        }
        walk(root, 0)
        return out
    }

    @Suppress("DEPRECATION")
    private fun recycleNode(n: AccessibilityNodeInfo?) {
        n?.recycle()
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
