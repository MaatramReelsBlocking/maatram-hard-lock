package com.maatram.hardlock

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
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
}
