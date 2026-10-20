package com.maatram.hardlock

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Linked devices: one Hard Lock for maatram.co.in, this app and the Chrome extension.
 * They share a link code (8 letters/digits, made on the website). A lock started on any of
 * them is stored under the code at maatram.co.in/api/link; this app checks it about once a
 * minute (from the Shield service, which is always running) and locks until the same end time.
 * A lock started here is pushed to the link so the website and Chrome lock too.
 * Network work runs on one background thread; failures are silent (the lock is local-first).
 */
object Link {
    private const val PREFS = "MaatramLink"
    private const val API = "https://maatram.co.in/api/link"
    private val CODE = Regex("^[A-HJ-NP-Z2-9]{8}$")
    private val io = Executors.newSingleThreadExecutor()

    fun code(ctx: Context): String = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("code", "").orEmpty()

    /** Saves a code typed by the user ("abcd-2345" is fine). Returns false if it isn't a valid code. Blank unlinks. */
    fun setCode(ctx: Context, raw: String): Boolean {
        val c = normalize(raw)
        if (c.isNotEmpty() && !isCode(c)) return false
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("code", c).apply()
        if (c.isNotEmpty()) poll(ctx)
        return true
    }

    internal fun normalize(raw: String) = raw.uppercase().filter { it.isLetterOrDigit() }
    internal fun isCode(c: String) = CODE.matches(c)

    fun pretty(c: String) = if (c.length == 8) c.take(4) + "-" + c.drop(4) else c

    /** Checks the link and starts the shared lock here if it is newer or longer than ours. */
    fun poll(ctx: Context) {
        val app = ctx.applicationContext
        val c = code(app); if (!isCode(c)) return
        io.execute {
            try {
                val con = (URL("$API?code=$c").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000; readTimeout = 8000; useCaches = false
                }
                val d = try {
                    if (con.responseCode != 200) return@execute
                    JSONObject(con.inputStream.bufferedReader().use { it.readText() })
                } finally { con.disconnect() }
                val minutes = d.optInt("minutes")
                val end = linkedEnd(d.optLong("end"), d.optLong("now"), minutes, System.currentTimeMillis(), LockManager.endTime(app))
                if (end != null) LockManager.startUntil(app, end, minutes, fromLink = true)
            } catch (_: Exception) { /* offline: try again next minute */ }
        }
    }

    /**
     * The end to lock until for a link reply, moved onto this phone's clock, or null when the
     * shared lock is over (or ends within 3 s), has no minutes, or isn't over 5 s past ours.
     * Capped at the longest lock, so a bad reply (no server time, or an end days away) can't
     * leave a lock that never ends.
     */
    internal fun linkedEnd(serverEnd: Long, serverNow: Long, minutes: Int, localNow: Long, ourEnd: Long): Long? {
        if (serverNow <= 0L) return null
        val end = minOf(serverEnd + (localNow - serverNow), localNow + LockManager.MAX_MINUTES * 60_000L)
        return end.takeIf { serverEnd > serverNow + 3_000L && it > ourEnd + 5_000L && minutes > 0 }
    }

    /** Tells the other linked devices about a lock started on this phone. */
    fun push(ctx: Context, minutes: Int) {
        val c = code(ctx.applicationContext); if (!isCode(c)) return
        io.execute {
            try {
                val con = (URL(API).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; doOutput = true; connectTimeout = 8000; readTimeout = 8000
                    setRequestProperty("Content-Type", "application/json")
                }
                try {
                    con.outputStream.use { it.write(JSONObject().put("code", c).put("minutes", minutes).put("by", "phone").toString().toByteArray()) }
                    con.responseCode
                } finally { con.disconnect() }
            } catch (_: Exception) {}
        }
    }

    /** Social apps whose screen time goes to the website's Stats page. Nothing else about the phone is sent. */
    private val USAGE_IDS = mapOf(
        "com.instagram.android" to "instagram", "com.instagram.lite" to "instagram",
        "com.snapchat.android" to "snapchat",
        "com.zhiliaoapp.musically" to "tiktok", "com.ss.android.ugc.trill" to "tiktok",
        "com.google.android.youtube" to "youtube",
        "com.whatsapp" to "whatsapp", "com.whatsapp.w4b" to "whatsapp",
        "com.twitter.android" to "other", "com.facebook.katana" to "other", "com.facebook.lite" to "other",
        "com.reddit.frontpage" to "other", "com.instagram.barcelona" to "other",
    )

    /** Foreground milliseconds per package -> whole minutes per Stats app id; ids under a minute are left out. */
    internal fun usageMinutes(ms: Map<String, Long>): Map<String, Int> =
        ms.entries.filter { it.key in USAGE_IDS }
            .groupBy({ USAGE_IDS.getValue(it.key) }, { it.value })
            .mapValues { (it.value.sum() / 60_000L).toInt().coerceAtMost(1440) }
            .filterValues { it >= 1 }

    /**
     * Sends today's screen time for the social apps to maatram.co.in so the Stats page fills itself in.
     * Needs a link code and Usage access (Settings > Usage access); does nothing without them.
     */
    fun pushUsage(ctx: Context) {
        val app = ctx.applicationContext
        val c = code(app); if (!isCode(c)) return
        val minutes = try {
            val usm = app.getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
            val start = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            usageMinutes(usm.queryAndAggregateUsageStats(start, System.currentTimeMillis()).mapValues { it.value.totalTimeInForeground })
        } catch (_: Exception) { return }
        if (minutes.isEmpty()) return
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(System.currentTimeMillis())
        io.execute {
            try {
                val con = (URL("https://maatram.co.in/api/usage").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; doOutput = true; connectTimeout = 8000; readTimeout = 8000
                    setRequestProperty("Content-Type", "application/json")
                }
                try {
                    con.outputStream.use { it.write(JSONObject().put("code", c).put("day", day).put("apps", JSONObject(minutes)).toString().toByteArray()) }
                    con.responseCode
                } finally { con.disconnect() }
            } catch (_: Exception) { /* offline: the next push sends it */ }
        }
    }
}
