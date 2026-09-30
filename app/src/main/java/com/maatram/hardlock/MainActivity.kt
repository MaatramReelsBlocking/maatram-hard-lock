package com.maatram.hardlock

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import kotlinx.coroutines.delay

private val BG = Color(0xFF0B0C0B)
private val CARD = Color(0xFF141915)
private val ACCENT = Color(0xFF6FBF9A)
private val INK = Color(0xFFECEFEE)
private val DIM = Color(0xFF8FA39A)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { App() }
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

@Composable
private fun App() {
    val ctx = LocalContext.current
    var tick by remember { mutableStateOf(0L) }

    // Re-check permissions/lock whenever the screen resumes, and tick every second.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(Unit) { while (true) { delay(1000); tick++ } }

    val locked = remember(tick) { LockManager.isLocked(ctx) }
    val shield = remember(tick) { accessibilityOn(ctx) }
    val admin = remember(tick) { adminOn(ctx) }

    MaterialTheme(colorScheme = darkColorScheme(primary = ACCENT, background = BG)) {
        Surface(Modifier.fillMaxSize(), color = BG) {
            if (locked) LockedScreen(ctx) else SetupScreen(ctx, shield, admin)
        }
    }
}

@Composable
private fun LockedScreen(ctx: Context) {
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
    }
}

@Composable
private fun SetupScreen(ctx: Context, shield: Boolean, admin: Boolean) {
    var minutes by remember { mutableStateOf(25) }
    val ready = shield

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
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        }

        Spacer(Modifier.height(22.dp))
        Text("Lock duration", color = INK, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        Spacer(Modifier.height(12.dp))
        DurationChips(minutes) { minutes = it }

        Spacer(Modifier.height(26.dp))
        Button(
            onClick = { LockManager.start(ctx, minutes) },
            enabled = ready,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = ACCENT, contentColor = BG)
        ) {
            Text(
                if (ready) "Start Hard Lock · $minutes min" else "Turn on Shield first",
                fontWeight = FontWeight.Bold, fontSize = 16.sp
            )
        }

        Spacer(Modifier.height(22.dp))
        Text("Blocked while locked", color = INK, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        Spacer(Modifier.height(8.dp))
        Text(
            "Instagram · YouTube · TikTok · Snapchat · X · Facebook · Reddit · Threads",
            color = DIM, fontSize = 14.sp, lineHeight = 20.sp
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Calls, messages, maps and the camera keep working.",
            color = DIM, fontSize = 13.sp
        )
        Spacer(Modifier.height(28.dp))
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
