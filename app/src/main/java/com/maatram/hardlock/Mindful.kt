package com.maatram.hardlock

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.util.Calendar

/**
 * Mindful mode, outside a Hard Lock, for the picked apps only:
 *  - Pause: opening one shows [PauseActivity] first (breathe, then a small task
 *    from the 2nd open of the day), and it opens for 2/5/10 minutes.
 *  - Daily limits: minutes per app per day, read from Android's own usage events
 *    (Usage access). Used up = sent home until midnight.
 */
object Mindful {
    private const val PREFS = "MaatramMindful"
    private const val KEY_PAUSE = "pause_on"
    private const val KEY_RECENT = "recent_opens"   // comma list of epoch ms, last hour

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun day(): String { val c = Calendar.getInstance(); return "%d-%d".format(c.get(Calendar.YEAR), c.get(Calendar.DAY_OF_YEAR)) }
    private fun midnight(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    fun pauseOn(ctx: Context) = p(ctx).getBoolean(KEY_PAUSE, false)
    fun setPauseOn(ctx: Context, on: Boolean) = p(ctx).edit().putBoolean(KEY_PAUSE, on).apply()

    fun limit(ctx: Context, pkg: String) = p(ctx).getInt("limit_$pkg", 0)

    /** Sets a limit (0 = none). A limit already used up today can't be raised or removed. Returns the limit kept. */
    fun setLimit(ctx: Context, pkg: String, min: Int): Int {
        val old = limit(ctx, pkg)
        val kept = if (old > 0 && usedMs(ctx, pkg) >= old * 60_000L && (min == 0 || min > old)) old else min.coerceIn(0, 24 * 60)
        p(ctx).edit().putInt("limit_$pkg", kept).apply()
        return kept
    }

    /** Foreground ms today, from Android's usage events (includes the stint running now). 0 without Usage access. */
    fun usedMs(ctx: Context, pkg: String): Long = try {
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val from = midnight()
        val ev = usm.queryEvents(from, now)
        val e = UsageEvents.Event()
        val list = ArrayList<Triple<Int, String, Long>>()
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e)
            if (e.packageName == pkg) list += Triple(e.eventType, e.className.orEmpty(), e.timeStamp)
        }
        foregroundMs(list, from, now)
    } catch (_: Exception) { 0L }

    /**
     * Pure part of [usedMs]: time any activity of the app was resumed, from (type, activity, time) events.
     * Tracked per activity, because moving between screens gives A paused, B resumed, A stopped and
     * A's stop must not end B's time. A pause with no resume since [from] was open at midnight.
     */
    internal fun foregroundMs(events: List<Triple<Int, String, Long>>, from: Long, now: Long): Long {
        val open = HashSet<String>()
        val seen = HashSet<String>()
        var total = 0L; var since = -1L
        for ((type, cls, t) in events) {
            when (type) {
                UsageEvents.Event.ACTIVITY_RESUMED -> { if (open.isEmpty()) since = t; open += cls }
                UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> when {
                    open.remove(cls) -> if (open.isEmpty()) { total += t - since; since = -1L }
                    type == UsageEvents.Event.ACTIVITY_PAUSED && cls !in seen && open.isEmpty() -> total += t - from
                }
            }
            seen += cls
        }
        if (since >= 0) total += now - since
        return total
    }

    fun overLimit(ctx: Context, pkg: String): Boolean {
        val l = limit(ctx, pkg)
        return l > 0 && usedMs(ctx, pkg) >= l * 60_000L
    }

    fun graceEnd(ctx: Context, pkg: String) = p(ctx).getLong("grace_$pkg", 0L)
    fun opensToday(ctx: Context, pkg: String) = p(ctx).getInt("opens_${day()}_$pkg", 0)

    /** Wait in seconds: 8, doubled per approved open in the last hour (cap 32). */
    fun waitSec(ctx: Context): Int {
        val now = System.currentTimeMillis()
        val n = recent(ctx).count { now - it < 3_600_000L }
        return 8 shl minOf(n, 2)
    }

    private fun recent(ctx: Context) =
        p(ctx).getString(KEY_RECENT, "").orEmpty().split(',').mapNotNull { it.toLongOrNull() }

    /** User chose to open [pkg] for [minutes]. */
    fun approve(ctx: Context, pkg: String, minutes: Int) {
        val now = System.currentTimeMillis()
        val keep = recent(ctx).filter { now - it < 3_600_000L } + now
        p(ctx).edit()
            .putLong("grace_$pkg", now + minutes * 60_000L)
            .putInt("opens_${day()}_$pkg", opensToday(ctx, pkg) + 1)
            .putString(KEY_RECENT, keep.joinToString(","))
            .apply()
    }
}

private val BG = Color(0xFF02090C)
private val CARD = Color(0x0FFFFFFF)
private val STROKE = Color(0x332FE38F)
private val ACCENT = Color(0xFF2FE38F)
private val BLUE = Color(0xFF37B6FF)
private val INK = Color(0xFFEAFFF6)
private val DIM = Color(0xFF7DA99B)
private val AMBER = Color(0xFFFFD166)
private val PILL = RoundedCornerShape(100.dp)

/** The pause screen, launched by BlockerService over a picked app. */
class PauseActivity : ComponentActivity() {
    private var pkg by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pkg = intent.getStringExtra(EXTRA_PKG).orEmpty()
        setContent { Pause(pkg) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_PKG)?.let { pkg = it }   // same app: keep the timer running
    }

    private fun goHome() {
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finishAndRemoveTask()
    }

    @Composable
    private fun Pause(pkg: String) {
        val ctx = this
        BackHandler { goHome() }
        val name = remember(pkg) {
            try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { "this app" }
        }
        val opens = remember(pkg) { Mindful.opensToday(ctx, pkg) }
        val lim = remember(pkg) { Mindful.limit(ctx, pkg) }
        val usedMin = remember(pkg) { Mindful.usedMs(ctx, pkg) / 60_000L }
        val total = remember(pkg) { Mindful.waitSec(ctx) }
        var left by remember(pkg) { mutableStateOf(total) }
        LaunchedEffect(pkg) { while (left > 0) { delay(1000); left-- } }

        // From the 2nd open today: a sum or a typed sentence.
        val task = remember(pkg) {
            if (opens == 0) null
            else if (Math.random() < 0.5) {
                val a = (12..49).random(); val b = (12..49).random()
                "What is $a + $b?" to "${a + b}"
            } else "Type: “I choose to open $name”" to "i choose to open ${name.lowercase()}"
        }
        var answer by remember(pkg) { mutableStateOf("") }
        val taskOk = task == null || answer.trim().replace(Regex("\\s+"), " ").lowercase() == task.second
        var minutes by remember { mutableStateOf(5) }

        val breath = rememberInfiniteTransition(label = "breath")
        val s by breath.animateFloat(0.82f, 1f, infiniteRepeatable(tween(4000, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "s")

        Column(
            Modifier.fillMaxSize().background(BG).verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(24.dp))
            Text("MAATRAM · PAUSE", color = DIM, fontSize = 12.sp, letterSpacing = 3.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text("Hold on — you're opening", color = INK, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(name, color = ACCENT, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                (if (opens == 0) "First open today" else "Open #${opens + 1} today") +
                    (if (lim > 0) " · $usedMin of $lim min daily limit used" else ""),
                color = if (lim > 0) AMBER else DIM, fontSize = 13.sp, textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(28.dp))
            Box(Modifier.size(190.dp), Alignment.Center) {
                Box(
                    Modifier.fillMaxSize().scale(if (left > 0) s else 1f)
                        .background(Brush.radialGradient(listOf(ACCENT.copy(alpha = .35f), BLUE.copy(alpha = .12f), Color.Transparent)), CircleShape)
                )
                Text(if (left > 0) "$left" else "✓", color = INK, fontSize = 40.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                when {
                    left > 0 -> if (((total - left) / 4) % 2 == 0) "Breathe in…" else "Breathe out…"
                    !taskOk -> "Finish the step below to continue."
                    else -> "Your call now."
                },
                color = INK, fontSize = 15.sp, fontWeight = FontWeight.SemiBold
            )

            if (task != null) {
                Spacer(Modifier.height(20.dp))
                Surface(color = CARD, shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, STROKE), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(task.first, color = INK, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = answer, onValueChange = { answer = it.take(60) }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = if (task.first.startsWith("What")) KeyboardType.Number else KeyboardType.Text),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = ACCENT, unfocusedBorderColor = if (taskOk) ACCENT else STROKE,
                                focusedTextColor = INK, unfocusedTextColor = INK, cursorColor = ACCENT
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            Text("For how long?", color = DIM, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(2, 5, 10).forEach { m ->
                    FilterChip(
                        selected = m == minutes, onClick = { minutes = m }, label = { Text("$m min") }, shape = PILL,
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = ACCENT, selectedLabelColor = BG, labelColor = INK
                        )
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = { Mindful.approve(ctx, pkg, minutes); finishAndRemoveTask() },
                enabled = left == 0 && taskOk, shape = PILL, modifier = Modifier.fillMaxWidth().height(54.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ACCENT, contentColor = BG,
                    disabledContainerColor = Color(0x1FFFFFFF), disabledContentColor = DIM)
            ) { Text("Open $name for $minutes min", fontWeight = FontWeight.Bold, fontSize = 16.sp) }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = { goHome() }, shape = PILL, border = BorderStroke(1.dp, STROKE),
                modifier = Modifier.fillMaxWidth().height(50.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = DIM)
            ) { Text("Never mind — take me home", fontWeight = FontWeight.SemiBold) }
            Spacer(Modifier.height(24.dp))
        }
    }

    companion object { const val EXTRA_PKG = "pkg" }
}
