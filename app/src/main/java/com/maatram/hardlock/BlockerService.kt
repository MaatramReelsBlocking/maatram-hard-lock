package com.maatram.hardlock

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat

/**
 * The blocker. While a lock runs:
 *  - a picked app comes to the front -> sent home + full-screen "disabled" card;
 *  - a Settings-type app shows a page mentioning Maatram (Shield toggle, App info,
 *    Device admin, uninstall) -> same. All other Settings pages work normally.
 * It also holds a foreground notification so Recents "Clear all" leaves it alone.
 */
class BlockerService : AccessibilityService() {

    private val ui = Handler(Looper.getMainLooper())
    private var card: View? = null
    private var foreground = false
    private var guardPkg = ""

    override fun onServiceConnected() {
        // Configure programmatically too — some OEMs ignore the XML.
        serviceInfo = AccessibilityServiceInfo().apply {
            // App switches only; TYPE_WINDOWS_CHANGED fired constantly for no gain.
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            notificationTimeout = 0L
        }
        keepAlive(LockManager.isLocked(this))
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val locked = LockManager.isLocked(this)
        keepAlive(locked)
        if (!locked) return
        val pkg = event?.packageName?.toString() ?: return
        // Only the exact app that came to the front. No launcher/Recents guessing:
        // on Xiaomi the launcher runs app-open animations, which looked like
        // "Recents" and made every app launch bounce.
        if (pkg == packageName) return
        val cls = event?.className?.toString().orEmpty()
        when {
            LockManager.isBlocked(this, pkg) -> { Garden.tempted(this, pkg); block(pkg) }
            isRecents(pkg, cls) -> block(pkg, "Recents")
            pkg in LockManager.GUARDED -> guard(pkg)
        }
    }

    /** Settings pages fill in a moment after opening, so look now and twice more. */
    private fun guard(pkg: String) {
        guardPkg = pkg
        ui.removeCallbacks(guardCheck)
        guardCheck.run()
        ui.postDelayed(guardCheck, 400L)
        ui.postDelayed(guardCheck, 1200L)
    }

    private val guardCheck = Runnable {
        if (guardPkg.isEmpty()) return@Runnable
        try {
            val root = rootInActiveWindow ?: return@Runnable
            if (root.packageName?.toString() == guardPkg &&
                root.findAccessibilityNodeInfosByText("Maatram").isNotEmpty()
            ) {
                val pkg = guardPkg
                guardPkg = ""          // pending re-checks become no-ops
                block(pkg)
            }
        } catch (_: Exception) { /* window gone; nothing to guard */ }
    }

    private fun block(pkg: String, label: String? = null) {
        performGlobalAction(GLOBAL_ACTION_HOME)
        showCard(pkg, label)
    }

    /**
     * The Recents screen, matched by its window class only (never by text), so a
     * normal app launch can't be mistaken for it. Bounced while locked: "Clear all"
     * in Recents is how most phones kill the Shield. Unknown launchers simply
     * don't match, which is safe.
     */
    private fun isRecents(pkg: String, cls: String): Boolean {
        val systemUi = pkg == "com.android.systemui" || "launcher" in pkg || pkg.endsWith(".home")
        return systemUi && cls.contains("recents", ignoreCase = true)
    }

    /** Foreground notification while locked; dropped when the lock ends. */
    private fun keepAlive(on: Boolean) {
        if (on == foreground) return
        try {
            if (on) {
                val nm = getSystemService(NotificationManager::class.java)
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Hard Lock", NotificationManager.IMPORTANCE_LOW)
                )
                val n = Notification.Builder(this, CHANNEL)
                    .setSmallIcon(R.drawable.ic_launcher_foreground)
                    .setContentTitle("Hard Lock is on")
                    .setContentText("Your chosen apps stay locked until the timer ends.")
                    .setOngoing(true)
                    .build()
                if (Build.VERSION.SDK_INT >= 34) {
                    startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                } else {
                    startForeground(NOTIF_ID, n)
                }
                // Drop the notification on time even if no app switch happens.
                ui.removeCallbacks(endCheck)
                ui.postDelayed(endCheck, LockManager.remainingMs(this) + 1_000L)
            } else {
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
            foreground = on
        } catch (_: Exception) { /* notification is a bonus; blocking still works */ }
    }

    private val endCheck = Runnable {
        val on = LockManager.isLocked(this)
        keepAlive(on)
        if (!on) hideCard.run()
    }

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    /** Full-screen "disabled" card in the maatram.co.in look. Tap Go home to close. */
    private fun showCard(pkg: String, label: String?) {
        val pm = packageManager
        val settingsPage = label != null || pkg in LockManager.GUARDED
        val info = try { pm.getApplicationInfo(pkg, 0) } catch (_: Exception) { null }
        val name = label ?: if (settingsPage) "This page" else info?.let { pm.getApplicationLabel(it) } ?: pkg
        val minsLeft = ((LockManager.remainingMs(this) + 59_999L) / 60_000L).toInt()
        val bold = ResourcesCompat.getFont(this, R.font.jakarta_bold)
        val regular = ResourcesCompat.getFont(this, R.font.jakarta_regular)
        try {
            hideCard.run()
            val icon = ImageView(this).apply {
                if (info != null && !settingsPage) setImageDrawable(pm.getApplicationIcon(info))
                else setImageResource(R.drawable.ic_launcher_foreground)
                alpha = 0.55f
            }
            val title = TextView(this).apply {
                text = "$name is disabled"
                typeface = bold; textSize = 24f; gravity = Gravity.CENTER
                setTextColor(0xFFECEFEE.toInt())
            }
            val sub = TextView(this).apply {
                text = "Hard Lock · $minsLeft min left"
                typeface = regular; textSize = 15f; gravity = Gravity.CENTER
                setTextColor(0xFF9CC0B2.toInt())
            }
            val home = TextView(this).apply {
                text = "Go home"
                typeface = bold; textSize = 16f; gravity = Gravity.CENTER
                setTextColor(0xFF0B0C0B.toInt())
                background = GradientDrawable().apply { cornerRadius = dp(100).toFloat(); setColor(0xFFECEFEE.toInt()) }
                setPadding(dp(28), dp(14), dp(28), dp(14))
                setOnClickListener { hideCard.run() }
            }
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundColor(0xFA0B0C0B.toInt())
                setPadding(dp(32), dp(32), dp(32), dp(32))
                addView(icon, LinearLayout.LayoutParams(dp(84), dp(84)))
                addView(title, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(24) })
                addView(sub, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
                addView(home, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(36) })
            }
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
            (getSystemService(WINDOW_SERVICE) as WindowManager).addView(box, lp)
            card = box
        } catch (_: Exception) { /* overlay optional; the app was already sent home */ }
    }

    private val hideCard = Runnable {
        try {
            card?.let { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) }
        } catch (_: Exception) {}
        card = null
    }

    override fun onInterrupt() {}

    // Turned off in Accessibility settings (or by the system) while a lock runs.
    override fun onUnbind(intent: android.content.Intent?): Boolean {
        LockEvents.shieldOff(this)
        return super.onUnbind(intent)
    }

    // Service can be switched off while the card is showing; don't leak the window.
    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        hideCard.run()
        super.onDestroy()
    }

    private companion object {
        const val CHANNEL = "hard_lock"
        const val NOTIF_ID = 1
    }
}
