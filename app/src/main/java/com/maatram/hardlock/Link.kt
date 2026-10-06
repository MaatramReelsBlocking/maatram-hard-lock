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
        val c = raw.uppercase().filter { it.isLetterOrDigit() }
        if (c.isNotEmpty() && !CODE.matches(c)) return false
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("code", c).apply()
        if (c.isNotEmpty()) poll(ctx)
        return true
    }

    fun pretty(c: String) = if (c.length == 8) c.take(4) + "-" + c.drop(4) else c

    /** Checks the link and starts the shared lock here if it is newer or longer than ours. */
    fun poll(ctx: Context) {
        val app = ctx.applicationContext
        val c = code(app); if (!CODE.matches(c)) return
        io.execute {
            try {
                val con = (URL("$API?code=$c").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000; readTimeout = 8000; useCaches = false
                }
                if (con.responseCode != 200) return@execute
                val d = JSONObject(con.inputStream.bufferedReader().readText())
                val serverEnd = d.optLong("end"); val serverNow = d.optLong("now"); val minutes = d.optInt("minutes")
                val end = serverEnd + (System.currentTimeMillis() - serverNow)      // correct for this phone's clock
                if (serverEnd > serverNow + 3_000L && end > LockManager.endTime(app) + 5_000L && minutes > 0)
                    LockManager.startUntil(app, end, minutes, fromLink = true)
            } catch (_: Exception) { /* offline: try again next minute */ }
        }
    }

    /** Tells the other linked devices about a lock started on this phone. */
    fun push(ctx: Context, minutes: Int) {
        val c = code(ctx.applicationContext); if (!CODE.matches(c)) return
        io.execute {
            try {
                val con = (URL(API).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; doOutput = true; connectTimeout = 8000; readTimeout = 8000
                    setRequestProperty("Content-Type", "application/json")
                }
                con.outputStream.use { it.write(JSONObject().put("code", c).put("minutes", minutes).put("by", "phone").toString().toByteArray()) }
                con.responseCode
            } catch (_: Exception) {}
        }
    }
}
