package com.maatram.hardlock

import android.content.Context
import java.util.Calendar

/**
 * The sakura garden. While a lock runs it tracks the "current plant" (start, end,
 * leaves dropped by temptation). When the lock's time is up the plant is planted
 * in the garden, once. Settling is idempotent and time-based, so it works whether
 * the end alarm, the app, the widget or a reboot notices first.
 */
object Garden {
    private const val PREFS = "MaatramGarden"
    private const val MAX_PLANTS = 400
    const val MAX_LEAVES = 12   // a tree can lose at most this many leaves

    data class Plant(val end: Long, val minutes: Int, val leaves: Int)
    data class Current(val start: Long, val end: Long, val minutes: Int, val leaves: Int, val tries: Map<String, Int>)

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun begin(ctx: Context, minutes: Int, end: Long) {
        val running = current(ctx)
        if (running != null && System.currentTimeMillis() < running.end) {
            // Same lock, made longer (e.g. a linked device locked for longer): keep growing this tree.
            val total = ((end - running.start) / 60_000L).toInt().coerceIn(running.minutes, LockManager.MAX_MINUTES)
            p(ctx).edit().putLong("cur_end", maxOf(end, running.end)).putInt("cur_min", total).apply()
            PlantWidget.refresh(ctx)
            return
        }
        settle(ctx)
        p(ctx).edit()
            .putLong("cur_start", System.currentTimeMillis()).putLong("cur_end", end)
            .putInt("cur_min", minutes).putInt("cur_leaves", 0).putString("cur_tries", "")
            .apply()
        PlantWidget.refresh(ctx)
    }

    fun current(ctx: Context): Current? {
        val s = p(ctx)
        val end = s.getLong("cur_end", 0L)
        if (end <= 0L) return null
        val tries = s.getString("cur_tries", "").orEmpty().split(',').mapNotNull {
            val i = it.lastIndexOf(':'); if (i <= 0) null else it.substring(0, i) to (it.substring(i + 1).toIntOrNull() ?: 0)
        }.toMap()
        return Current(s.getLong("cur_start", end), end, s.getInt("cur_min", 0), s.getInt("cur_leaves", 0), tries)
    }

    private var lastPkg = ""
    private var lastAt = 0L

    /** A blocked app was opened during the lock: the tree drops a leaf. */
    fun tempted(ctx: Context, pkg: String) {
        val now = System.currentTimeMillis()
        if (pkg == lastPkg && now - lastAt < 3_000L) return   // one open fires several window events
        lastPkg = pkg; lastAt = now
        val c = current(ctx) ?: return
        if (now >= c.end) return
        val tries = c.tries.toMutableMap().apply { this[pkg] = (this[pkg] ?: 0) + 1 }
        p(ctx).edit()
            .putInt("cur_leaves", (c.leaves + 1).coerceAtMost(MAX_LEAVES))
            .putString("cur_tries", tries.entries.joinToString(",") { "${it.key}:${it.value}" })
            .apply()
        PlantWidget.refresh(ctx)
    }

    /** Plants the finished tree. Returns true if one was planted now. */
    fun settle(ctx: Context): Boolean {
        val c = current(ctx) ?: return false
        if (System.currentTimeMillis() + 5_000L < c.end) return false   // end alarm may fire a few seconds early
        if (LockManager.endTime(ctx) == c.end && LockManager.remainingMs(ctx) > 5_000L) return false   // clock moved forward: not finished
        val s = p(ctx)
        val line = "${c.end},${c.minutes},${c.leaves}"
        val all = (s.getString("plants", "").orEmpty().split('\n').filter { it.isNotBlank() } + line).takeLast(MAX_PLANTS)
        s.edit().putString("plants", all.joinToString("\n")).putLong("cur_end", 0L)
            .putInt("last_leaves", c.leaves).apply()
        return true
    }

    fun plants(ctx: Context): List<Plant> =
        p(ctx).getString("plants", "").orEmpty().split('\n').mapNotNull { l ->
            val f = l.split(','); if (f.size < 3) null else
                Plant(f[0].toLongOrNull() ?: return@mapNotNull null, f[1].toIntOrNull() ?: 0, f[2].toIntOrNull() ?: 0)
        }

    fun dayKey(ms: Long): Int = Calendar.getInstance().run {
        timeInMillis = ms; get(Calendar.YEAR) * 1000 + get(Calendar.DAY_OF_YEAR)
    }

    /** Days in a row (ending today, or yesterday if today has no plant yet) with at least one plant. */
    fun streak(plants: List<Plant>): Int {
        val days = plants.map { dayKey(it.end) }.toSet()
        val cal = Calendar.getInstance()
        if (dayKey(cal.timeInMillis) !in days) cal.add(Calendar.DAY_OF_YEAR, -1)
        var n = 0
        while (dayKey(cal.timeInMillis) in days) { n++; cal.add(Calendar.DAY_OF_YEAR, -1) }
        return n
    }

    /** Last [n] days, oldest first: the longest lock finished that day in minutes (0 = gap). 90+ = a full tree. */
    fun lastDaysBest(plants: List<Plant>, n: Int = 28): List<Int> {
        val byDay = plants.groupBy { dayKey(it.end) }.mapValues { e -> e.value.maxOf { it.minutes } }
        val c = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -(n - 1)) }
        return List(n) { (byDay[dayKey(c.timeInMillis)] ?: 0).also { c.add(Calendar.DAY_OF_YEAR, 1) } }
    }

    /** Last [n] days, oldest first: minutes planted per day (0 = gap). */
    fun lastDays(plants: List<Plant>, n: Int = 28): List<Int> {
        val byDay = plants.groupBy { dayKey(it.end) }.mapValues { e -> e.value.sumOf { it.minutes } }
        val c = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -(n - 1)) }
        return List(n) { (byDay[dayKey(c.timeInMillis)] ?: 0).also { c.add(Calendar.DAY_OF_YEAR, 1) } }
    }
}
