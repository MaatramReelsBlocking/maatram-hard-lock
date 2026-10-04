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
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
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

private val BG = Color(0xFF0B0C0B)
private val CARD = Color(0xFF141915)
private val ACCENT = Color(0xFF6FBF9A)
private val INK = Color(0xFFECEFEE)
private val DIM = Color(0xFF8FA39A)

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

    MaterialTheme(colorScheme = darkColorScheme(primary = ACCENT, background = BG)) {
        Surface(Modifier.fillMaxSize(), color = BG) {
            when {
                locked -> LockedScreen(ctx, shield)
                picking -> AppPicker(ctx) { picking = false }
                else -> SetupScreen(ctx, shield, admin, battery, onPick = { picking = true }) { tick++ }
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
                color = Color(0xFFE57373), fontSize = 14.sp, textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = {
                ctx.startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }) { Text("Turn on Shield", color = ACCENT) }
        }
    }
}

@Composable
private fun SetupScreen(
    ctx: Context, shield: Boolean, admin: Boolean, battery: Boolean,
    onPick: () -> Unit, onStarted: () -> Unit
) {
    var minutes by remember { mutableStateOf(25) }
    // Count only installed apps (defaults include apps the phone may not have).
    val lockedCount = remember {
        LockManager.lockedApps(ctx).count { ctx.packageManager.getLaunchIntentForPackage(it) != null }
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
        Text("Maatram", color = INK, fontWeight = FontWeight.Bold, fontSize = 30.sp)
        Text("Hard Lock", color = ACCENT, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Spacer(Modifier.height(6.dp))
        Text("Bringing a change in you", color = DIM, fontSize = 14.sp)
        Spacer(Modifier.height(24.dp))

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
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = ACCENT, contentColor = BG)
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
        ScheduleCard(ctx)

        Spacer(Modifier.height(12.dp))
        MotivationCard(ctx)

        Spacer(Modifier.height(22.dp))
        Text("Apps to lock", color = INK, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        Spacer(Modifier.height(6.dp))
        Text(
            if (lockedCount == 0) "No apps chosen yet. Only the apps you pick get locked."
            else "$lockedCount app${if (lockedCount == 1) "" else "s"} will be locked. Everything else keeps working.",
            color = if (lockedCount == 0) DIM else ACCENT, fontSize = 13.sp, lineHeight = 18.sp
        )
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = onPick,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = ACCENT)
        ) { Text("Choose apps to lock", fontWeight = FontWeight.SemiBold) }
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun ScheduleCard(ctx: Context) {
    var plan by remember { mutableStateOf(LockSchedule.get(ctx)) }
    val askNotify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    fun save(p: LockSchedule.Plan) { plan = p; LockSchedule.set(ctx, p) }
    Surface(color = CARD, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
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
                    colors = SwitchDefaults.colors(checkedTrackColor = ACCENT, checkedThumbColor = BG)
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = {
                        android.app.TimePickerDialog(ctx, { _, h, m -> save(plan.copy(hour = h, minute = m)) },
                            plan.hour, plan.minute, true).show()
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = ACCENT)
                ) { Text("Time %02d:%02d".format(plan.hour, plan.minute), fontWeight = FontWeight.SemiBold) }
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
    // Reminders are opt-in: if switched on, make sure notification permission is granted (Android 13+).
    LaunchedEffect(Unit) {
        if (on && Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) ask.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    Surface(color = CARD, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Motivation reminders", color = INK, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Optional. A short line 3 times a day with a one-tap ${Motivation.QUICK_MINUTES}-minute Hard Lock. Skipped while a lock is running.",
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
                colors = SwitchDefaults.colors(checkedTrackColor = ACCENT, checkedThumbColor = BG)
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
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.weight(1f).height(52.dp)
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
    Surface(color = CARD, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (on) "✅" else "⚠️", fontSize = 18.sp)
                Spacer(Modifier.width(10.dp))
                Text(title, color = INK, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            }
            Spacer(Modifier.height(6.dp))
            Text(if (on) onText else offText, color = if (on) ACCENT else DIM, fontSize = 13.sp)
            if (button != null) {
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = onClick,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = ACCENT)
                ) { Text(button, fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

private data class AppItem(val pkg: String, val label: String, val icon: ImageBitmap?)

/** Every launchable app, minus this app, the phone dialer and the always-guarded Settings. */
private fun loadApps(ctx: Context): List<AppItem> {
    val pm = ctx.packageManager
    val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val dialer = pm.resolveActivity(Intent(Intent.ACTION_DIAL), PackageManager.MATCH_DEFAULT_ONLY)
        ?.activityInfo?.packageName
    val skip = LockManager.GUARDED + setOfNotNull(ctx.packageName, dialer)
    return pm.queryIntentActivities(launcher, 0)
        .distinctBy { it.activityInfo.packageName }
        .filter { it.activityInfo.packageName !in skip }
        .map { ri ->
            val icon = try { ri.loadIcon(pm).toBitmap(96, 96).asImageBitmap() } catch (_: Exception) { null }
            AppItem(ri.activityInfo.packageName, ri.loadLabel(pm).toString(), icon)
        }
        .sortedBy { it.label.lowercase() }
}

@Composable
private fun AppPicker(ctx: Context, onDone: () -> Unit) {
    val chosen = remember { mutableStateListOf<String>().apply { addAll(LockManager.lockedApps(ctx)) } }
    var query by remember { mutableStateOf("") }
    val apps by produceState<List<AppItem>?>(initialValue = null) {
        value = withContext(Dispatchers.IO) { loadApps(ctx) }
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
            Button(
                onClick = save,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ACCENT, contentColor = BG)
            ) { Text("Done", fontWeight = FontWeight.Bold) }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search apps") },
            singleLine = true,
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
                        val ic = app.icon
                        if (ic != null) Image(bitmap = ic, contentDescription = null, modifier = Modifier.size(40.dp))
                        else Spacer(Modifier.size(40.dp))
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
