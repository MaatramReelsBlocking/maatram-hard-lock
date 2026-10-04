package com.maatram.hardlock

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// Same tokens as maatram.co.in (theme.src.js, html.minimal).
private val BG = Color(0xFF0B0C0B)
private val CARD = Color(0x0EFFFFFF)       // --card rgba(255,255,255,.055)
private val STROKE = Color(0x21FFFFFF)     // --stroke rgba(255,255,255,.13)
private val ACCENT = Color(0xFF9CC0B2)     // --accent sage
private val INK = Color(0xFFECEFEE)        // --ink
private val DIM = Color(0xFF8A928E)        // --dim
private val DANGER = Color(0xFFD98A94)     // --danger
private val ORB = Color(0xFF9CC0B2)
private val CARD_SHAPE = RoundedCornerShape(24.dp)   // --radius 24px
private val PILL = RoundedCornerShape(100.dp)        // site buttons are pills
private val Jakarta = FontFamily(
    Font(R.font.jakarta_regular, FontWeight.Normal),
    Font(R.font.jakarta_semibold, FontWeight.SemiBold),
    Font(R.font.jakarta_bold, FontWeight.Bold),
)

/** Glass card: faint white fill + thin white stroke, 24dp corners. */
@Composable
private fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) =
    Surface(color = CARD, shape = CARD_SHAPE, border = BorderStroke(1.dp, STROKE),
        modifier = modifier.fillMaxWidth(), content = content)

/** Primary button: light pill with dark text, like the site's download button. */
@Composable
private fun PrimaryButton(text: String, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) =
    Button(
        onClick = onClick, enabled = enabled, shape = PILL,
        modifier = modifier.height(54.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = INK, contentColor = BG,
            disabledContainerColor = Color(0x1FFFFFFF), disabledContentColor = DIM
        )
    ) { Text(text, fontWeight = FontWeight.Bold, fontSize = 16.sp) }

@Composable
private fun GhostButton(text: String, onClick: () -> Unit) =
    OutlinedButton(onClick = onClick, shape = PILL, border = BorderStroke(1.dp, STROKE),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = ACCENT)
    ) { Text(text, fontWeight = FontWeight.SemiBold) }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Motivation.handleIntent(this, intent)
        Motivation.scheduleNext(this)
        LockSchedule.arm(this)
        setContent { App() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Motivation.handleIntent(this, intent)
    }
}

private fun accessibilityOn(ctx: Context): Boolean {
    val flat = Settings.Secure.getString(
        ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    val me = ComponentName(ctx, BlockerService::class.java).flattenToString()
    val splitter = TextUtils.SimpleStringSplitter(':')
    splitter.setString(flat)
    while (splitter.hasNext()) if (splitter.next().equals(me, true)) return true
    return false
}

private fun adminOn(ctx: Context): Boolean {
    val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    return dpm.isAdminActive(ComponentName(ctx, AdminReceiver::class.java))
}

private fun batteryFree(ctx: Context): Boolean =
    (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager)
        .isIgnoringBatteryOptimizations(ctx.packageName)

// Xiaomi/Redmi/POCO: without Autostart, "Clear all" in Recents force-stops the
// app and Android never restarts the Shield, so the lock silently dies.
private fun autostartIntent(ctx: Context): Intent? =
    Intent().setComponent(
        ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
    ).takeIf { it.resolveActivity(ctx.packageManager) != null }

@Composable
private fun App() {
    val ctx = LocalContext.current
    var tick by remember { mutableStateOf(0L) }
    var resumes by remember { mutableStateOf(0) }
    var picking by remember { mutableStateOf(false) }

    // Re-check permissions/lock whenever the screen resumes.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) { tick++; resumes++ } }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val locked = remember(tick) { LockManager.isLocked(ctx) }
    // Permissions only change while we're in Settings, so check on resume, not every second.
    val shield = remember(resumes) { accessibilityOn(ctx) }
    val admin = remember(resumes) { adminOn(ctx) }
    val battery = remember(resumes) { batteryFree(ctx) }
    // Tick every second only while locked (countdown); idle setup screen does no work.
    LaunchedEffect(locked) { while (locked) { delay(1000); tick++ } }
    // Hide this app's card from Recents while locked, so "Clear all" can't target it.
    LaunchedEffect(locked) {
        try {
            (ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager)
                .appTasks.forEach { it.setExcludeFromRecents(locked) }
        } catch (_: Exception) {}
    }

    val type = Typography().let { t ->
        Typography(
            bodyLarge = t.bodyLarge.copy(fontFamily = Jakarta), bodyMedium = t.bodyMedium.copy(fontFamily = Jakarta),
            labelLarge = t.labelLarge.copy(fontFamily = Jakarta), titleMedium = t.titleMedium.copy(fontFamily = Jakarta)
        )
    }
    MaterialTheme(colorScheme = darkColorScheme(primary = ACCENT, background = BG, surface = BG), typography = type) {
        ProvideTextStyle(TextStyle(fontFamily = Jakarta)) {
        Box(
            Modifier.fillMaxSize().background(BG).drawBehind {
                // Two soft sage orbs, same placement as the site.
                val r1 = size.minDimension * 0.42f
                drawCircle(Brush.radialGradient(listOf(ORB.copy(alpha = .22f), Color.Transparent),
                    Offset(size.width * .82f, size.height * .12f), r1), r1, Offset(size.width * .82f, size.height * .12f))
                val r2 = size.minDimension * 0.34f
                drawCircle(Brush.radialGradient(listOf(ORB.copy(alpha = .16f), Color.Transparent),
                    Offset(size.width * .14f, size.height * .78f), r2), r2, Offset(size.width * .14f, size.height * .78f))
            }
        ) {
            when {
                locked -> LockedScreen(ctx, shield)
                picking -> AppPicker(ctx) { picking = false }
                else -> SetupScreen(ctx, shield, admin, battery, onPick = { picking = true }) { tick++ }
            }
        }
        }
    }
}

@Composable
private fun LockedScreen(ctx: Context, shield: Boolean) {
    val leftMs = LockManager.remainingMs(ctx)
    val total = (leftMs + 59_999L) / 60_000L
    val mm = leftMs / 60_000L
    val ss = (leftMs % 60_000L) / 1000L
    Column(
        Modifier.fillMaxSize().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("🔒", fontSize = 64.sp)
        Spacer(Modifier.height(20.dp))
        Text("HARD LOCK ACTIVE", color = ACCENT, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Spacer(Modifier.height(12.dp))
        Text(
            String.format("%02d:%02d", mm, ss),
            color = INK, fontWeight = FontWeight.Bold, fontSize = 68.sp
        )
        Spacer(Modifier.height(8.dp))
        Text("$total min remaining", color = DIM, fontSize = 15.sp)
        Spacer(Modifier.height(28.dp))
        Text(
            "Distracting apps are blocked and the lock cannot be cancelled. " +
                "It ends on its own when the timer reaches zero.",
            color = DIM, fontSize = 14.sp, textAlign = TextAlign.Center,
            lineHeight = 20.sp
        )
        // Force-stopping the app (e.g. "Clear all" on some phones) switches the
        // Shield off in Android itself. Say so instead of pretending to be locked.
        if (!shield) {
            Spacer(Modifier.height(24.dp))
            Text(
                "The Shield was switched off, so nothing is blocked right now. Turn it back on.",
                color = DANGER, fontSize = 14.sp, textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(12.dp))
            GhostButton("Turn on Shield") {
                ctx.startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }
}

@Composable
private fun SetupScreen(
    ctx: Context, shield: Boolean, admin: Boolean, battery: Boolean,
    onPick: () -> Unit, onStarted: () -> Unit
) {
    var minutes by remember { mutableStateOf(25) }
    // Count only apps still installed.
    val picked = remember {
        LockManager.lockedApps(ctx).filter { ctx.packageManager.getLaunchIntentForPackage(it) != null }
    }
    val lockedCount = picked.size
    val pickedIcons by produceState(emptyList<ImageBitmap>(), picked) {
        value = withContext(Dispatchers.IO) { picked.take(7).mapNotNull { appIcon(ctx, it) } }
    }
    // Xiaomi: Autostart can't be read back, so remember that the user opened it.
    val autostart = remember { autostartIntent(ctx) }
    val prefs = remember { ctx.getSharedPreferences("MaatramLock", Context.MODE_PRIVATE) }
    var autostartDone by remember { mutableStateOf(prefs.getBoolean("autostart_opened", false)) }
    val keepRunning = battery && (autostart == null || autostartDone)
    // No lock until the Shield can survive "Clear all"; otherwise the lock silently dies.
    val ready = shield && keepRunning && lockedCount > 0

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp)
    ) {
        Spacer(Modifier.height(16.dp))
        Text("Maatram", color = INK, fontWeight = FontWeight.Bold, fontSize = 32.sp, letterSpacing = (-0.5).sp)
        Text("Hard Lock", color = ACCENT, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Spacer(Modifier.height(6.dp))
        Text("Bringing a change in you", color = DIM, fontSize = 14.sp)
        Spacer(Modifier.height(24.dp))

        // Apps to lock — first, since nothing locks until apps are picked.
        Card {
            Column(Modifier.padding(18.dp)) {
                Text("Apps to lock", color = INK, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                Spacer(Modifier.height(4.dp))
                Text(
                    if (lockedCount == 0) "No apps chosen yet. Only the apps you pick get locked."
                    else "$lockedCount app${if (lockedCount == 1) "" else "s"} will be locked. Everything else keeps working.",
                    color = if (lockedCount == 0) DIM else ACCENT, fontSize = 13.sp, lineHeight = 18.sp
                )
                if (pickedIcons.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pickedIcons.take(7).forEach { Image(it, null, Modifier.size(34.dp)) }
                        if (lockedCount > 7) Text("+${lockedCount - 7}", color = DIM, fontSize = 13.sp,
                            modifier = Modifier.align(Alignment.CenterVertically))
                    }
                }
                Spacer(Modifier.height(14.dp))
                GhostButton(if (lockedCount == 0) "Choose apps to lock" else "Change apps", onPick)
            }
        }

        Spacer(Modifier.height(22.dp))
        Text("Lock duration", color = INK, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        Spacer(Modifier.height(12.dp))
        DurationChips(minutes) { minutes = it }

        // Android 13+: allow the "Hard Lock is on" notification that keeps the Shield alive.
        val askNotify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
        Spacer(Modifier.height(26.dp))
        Button(
            onClick = {
                if (Build.VERSION.SDK_INT >= 33) askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
                LockManager.start(ctx, minutes); onStarted()
            },
            enabled = ready,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = PILL,
            colors = ButtonDefaults.buttonColors(
                containerColor = INK, contentColor = BG,
                disabledContainerColor = Color(0x1FFFFFFF), disabledContentColor = DIM
            )
        ) {
            Text(
                when {
                    !shield -> "Turn on Shield first"
                    !keepRunning -> "Finish step 3 first"
                    lockedCount == 0 -> "Choose apps to lock first"
                    else -> "Start Hard Lock · $minutes min"
                },
                fontWeight = FontWeight.Bold, fontSize = 16.sp
            )
        }

        Spacer(Modifier.height(22.dp))
        Text("Setup", color = INK, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        Spacer(Modifier.height(10.dp))
        // Step 1 — Shield
        StatusCard(
            title = "1 · Focus Shield",
            on = shield,
            onText = "On — blocking is armed",
            offText = "Off — turn on to enable blocking",
            button = if (shield) null else "Turn on Shield"
        ) {
            ctx.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }

        Spacer(Modifier.height(12.dp))

        // Step 2 — Device Admin (optional but recommended)
        StatusCard(
            title = "2 · Lock protection (recommended)",
            on = admin,
            onText = "On — app can't be uninstalled while locked",
            offText = "Off — grant so the lock can't be removed",
            button = if (admin) null else "Turn on protection"
        ) {
            val cn = ComponentName(ctx, AdminReceiver::class.java)
            val i = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, cn)
                .putExtra(
                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "Keeps Maatram Hard Lock from being removed while a lock is running."
                )
            // No FLAG_ACTIVITY_NEW_TASK: Settings' DeviceAdminAdd silently finishes
            // when launched as a new task, which made this button do nothing.
            ctx.startActivity(i)
        }

        Spacer(Modifier.height(12.dp))

        // Step 3 — keep the Shield alive through Recents "Clear all"
        StatusCard(
            title = "3 · Keep running (required)",
            on = keepRunning,
            onText = "On — also lock this app in Recents (hold its card, tap the lock)",
            offText = if (!battery) "Off — Clear all in Recents can switch the lock off"
                      else "One more step — turn on Autostart for Maatram Hard Lock",
            button = when {
                !battery -> "Allow background running"
                !keepRunning -> "Open Autostart settings"
                else -> null
            }
        ) {
            val i = if (!battery) Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${ctx.packageName}")
            ) else autostart
            // Newer Xiaomi (HyperOS) refuses to open its Autostart screen directly, so the
            // app-info page (where the Autostart switch also lives) opens instead. Either way
            // the user was sent to turn it on, so the step is marked done.
            if (battery) { prefs.edit().putBoolean("autostart_opened", true).apply(); autostartDone = true }
            try {
                i?.let { ctx.startActivity(it) }
            } catch (_: Exception) {
                ctx.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }

        Spacer(Modifier.height(22.dp))
        ScheduleCard(ctx)

        Spacer(Modifier.height(12.dp))
        MotivationCard(ctx)

        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun ScheduleCard(ctx: Context) {
    var plan by remember { mutableStateOf(LockSchedule.get(ctx)) }
    val askNotify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    fun save(p: LockSchedule.Plan) { plan = p; LockSchedule.set(ctx, p) }
    Card {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Scheduled lock", color = INK, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (plan.on) "Starts at %02d:%02d for %d min%s".format(plan.hour, plan.minute, plan.minutes, if (plan.daily) ", every day" else ", once")
                        else "Off. Pick a time and the lock starts on its own.",
                        color = if (plan.on) ACCENT else DIM, fontSize = 13.sp, lineHeight = 18.sp
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = plan.on,
                    onCheckedChange = {
                        save(plan.copy(on = it))
                        if (it && Build.VERSION.SDK_INT >= 33) askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
                    },
                    colors = SwitchDefaults.colors(checkedTrackColor = ACCENT, checkedThumbColor = BG, uncheckedTrackColor = CARD, uncheckedBorderColor = STROKE)
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                GhostButton("Time %02d:%02d".format(plan.hour, plan.minute)) {
                    android.app.TimePickerDialog(ctx, { _, h, m -> save(plan.copy(hour = h, minute = m)) },
                        plan.hour, plan.minute, true).show()
                }
                Spacer(Modifier.width(12.dp))
                Checkbox(
                    checked = plan.daily,
                    onCheckedChange = { save(plan.copy(daily = it)) },
                    colors = CheckboxDefaults.colors(checkedColor = ACCENT, checkmarkColor = BG)
                )
                Text("Every day", color = INK, fontSize = 14.sp)
            }
            Spacer(Modifier.height(10.dp))
            DurationChips(plan.minutes) { save(plan.copy(minutes = it)) }
        }
    }
}

@Composable
private fun MotivationCard(ctx: Context) {
    var on by remember { mutableStateOf(Motivation.isOn(ctx)) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) { on = false; Motivation.setOn(ctx, false) }
    }
    // On by default: make sure notification permission is granted (Android 13+).
    LaunchedEffect(Unit) {
        if (on && Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) ask.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    Card {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Daily motivation", color = INK, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Spacer(Modifier.height(4.dp))
                Text(
                    "5 short lines a day (8:00, 11:00, 14:00, 17:00, 20:30), shown as a pop-up on your screen. Skipped while a lock is running.",
                    color = DIM, fontSize = 13.sp, lineHeight = 18.sp
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = on,
                onCheckedChange = {
                    on = it; Motivation.setOn(ctx, it)
                    if (it && Build.VERSION.SDK_INT >= 33) ask.launch(Manifest.permission.POST_NOTIFICATIONS)
                },
                colors = SwitchDefaults.colors(checkedTrackColor = ACCENT, checkedThumbColor = BG, uncheckedTrackColor = CARD, uncheckedBorderColor = STROKE)
            )
        }
    }
}

@Composable
private fun DurationChips(selected: Int, onPick: (Int) -> Unit) {
    val opts = listOf(5, 15, 25, 45, 60, 90)
    Column {
        opts.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { m ->
                    val sel = m == selected
                    Surface(
                        color = if (sel) ACCENT else CARD,
                        shape = PILL,
                        border = if (sel) null else BorderStroke(1.dp, STROKE),
                        modifier = Modifier.weight(1f).height(50.dp)
                    ) {
                        Box(Modifier.fillMaxSize().clickable { onPick(m) }, Alignment.Center) {
                            Text(
                                "$m min",
                                color = if (sel) BG else INK,
                                fontWeight = FontWeight.SemiBold, fontSize = 15.sp
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun StatusCard(
    title: String, on: Boolean, onText: String, offText: String,
    button: String?, onClick: () -> Unit
) {
    Card {
        Column(Modifier.padding(horizontal = 18.dp, vertical = if (on && button == null) 14.dp else 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (on) "✓" else "!", color = if (on) ACCENT else DANGER, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(Modifier.width(10.dp))
                Text(title, color = INK, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            }
            // Done steps shrink to one line; only unfinished ones explain themselves.
            if (!on || button != null) {
                Spacer(Modifier.height(6.dp))
                Text(if (on) onText else offText, color = if (on) ACCENT else DIM, fontSize = 13.sp, lineHeight = 18.sp)
            }
            if (button != null) {
                Spacer(Modifier.height(12.dp))
                GhostButton(button, onClick)
            }
        }
    }
}

private data class AppItem(val pkg: String, val label: String)

// Kept for the life of the process: reopening the picker is instant.
private var appCache: List<AppItem>? = null
private val iconCache = HashMap<String, ImageBitmap?>()

private fun appIcon(ctx: Context, pkg: String): ImageBitmap? =
    try { ctx.packageManager.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap() } catch (_: Exception) { null }

/** Every launchable app, minus this app, the phone dialer and Settings-type apps. Names only; icons load per row. */
private fun loadApps(ctx: Context): List<AppItem> {
    val pm = ctx.packageManager
    val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val dialer = pm.resolveActivity(Intent(Intent.ACTION_DIAL), PackageManager.MATCH_DEFAULT_ONLY)
        ?.activityInfo?.packageName
    val skip = LockManager.GUARDED + setOfNotNull(ctx.packageName, dialer)
    return pm.queryIntentActivities(launcher, 0)
        .distinctBy { it.activityInfo.packageName }
        .filter { it.activityInfo.packageName !in skip }
        .map { ri -> AppItem(ri.activityInfo.packageName, ri.loadLabel(pm).toString()) }
        .sortedBy { it.label.lowercase() }
}

@Composable
private fun AppIcon(ctx: Context, pkg: String) {
    val icon by produceState(iconCache[pkg], pkg) {
        if (!iconCache.containsKey(pkg)) {
            val b = withContext(Dispatchers.IO) { appIcon(ctx, pkg) }
            iconCache[pkg] = b
            value = b
        }
    }
    val ic = icon
    if (ic != null) Image(bitmap = ic, contentDescription = null, modifier = Modifier.size(40.dp))
    else Spacer(Modifier.size(40.dp))
}

@Composable
private fun AppPicker(ctx: Context, onDone: () -> Unit) {
    val chosen = remember { mutableStateListOf<String>().apply { addAll(LockManager.lockedApps(ctx)) } }
    var query by remember { mutableStateOf("") }
    val apps by produceState(appCache) {
        value = withContext(Dispatchers.IO) { loadApps(ctx) }.also { appCache = it }
    }
    // Keep only installed apps, so the count matches what's on the phone.
    val save = {
        val installed = apps?.map { it.pkg }?.toSet()
        LockManager.setLockedApps(ctx, if (installed == null) chosen.toSet() else chosen.filter { it in installed }.toSet())
        onDone()
    }
    BackHandler { save() }

    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Choose apps to lock", color = INK, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                Text(
                    "Only the apps you tick get locked",
                    color = DIM, fontSize = 13.sp
                )
            }
            Spacer(Modifier.width(10.dp))
            PrimaryButton("Done", onClick = save)
        }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search apps") },
            singleLine = true,
            shape = PILL,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = ACCENT, unfocusedBorderColor = STROKE,
                focusedTextColor = INK, unfocusedTextColor = INK, cursorColor = ACCENT
            ),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        val list = apps
        if (list == null) {
            Box(Modifier.fillMaxWidth().padding(40.dp), Alignment.Center) {
                CircularProgressIndicator(color = ACCENT)
            }
        } else {
            val q = query.trim()
            val shown = if (q.isEmpty()) list else list.filter { it.label.contains(q, ignoreCase = true) }
            LazyColumn(Modifier.fillMaxSize()) {
                items(shown, key = { it.pkg }) { app ->
                    val on = app.pkg in chosen
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { if (on) chosen.remove(app.pkg) else chosen.add(app.pkg) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AppIcon(ctx, app.pkg)
                        Spacer(Modifier.width(14.dp))
                        Text(app.label, color = INK, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        Checkbox(
                            checked = on,
                            onCheckedChange = { if (it) chosen.add(app.pkg) else chosen.remove(app.pkg) },
                            colors = CheckboxDefaults.colors(checkedColor = ACCENT, checkmarkColor = BG)
                        )
                    }
                }
            }
        }
    }
}
