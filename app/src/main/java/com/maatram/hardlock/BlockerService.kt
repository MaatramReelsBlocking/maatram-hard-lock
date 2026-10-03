package com.maatram.hardlock

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.TextView

/**
 * The blocker. On every app switch, if a lock is running and the foreground app
 * is one the user picked (or Settings/installer), it snaps home and shows a
 * small pill naming the app. While a lock runs it also holds a foreground
 * notification, so Recents "Clear all" treats it as in use and leaves it alone.
 */
class BlockerService : AccessibilityService() {

    private val ui = Handler(Looper.getMainLooper())
    private var pill: TextView? = null
    private var lastPillAt = 0L
    private var foreground = false

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
        if (pkg == packageName || !LockManager.isBlocked(this, pkg)) return

        performGlobalAction(GLOBAL_ACTION_HOME)
        showPill(pkg)
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

    private val endCheck = Runnable { keepAlive(LockManager.isLocked(this)) }

    private fun showPill(pkg: String) {
        val now = System.currentTimeMillis()
        if (now - lastPillAt < 900L) return          // throttle, keep it cheap
        lastPillAt = now

        val name = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0))
        } catch (_: Exception) { pkg }
        val minsLeft = ((LockManager.remainingMs(this) + 59_999L) / 60_000L).toInt()
        ui.post {
            try {
                val wm = getSystemService(WINDOW_SERVICE) as WindowManager
                val tv = pill ?: TextView(this).also { pill = it }
                tv.text = "🔒  $name is locked · $minsLeft min left"
                tv.setPadding(44, 26, 44, 26)
                tv.textSize = 15f
                tv.setTextColor(0xFFFFFFFF.toInt())
                tv.setBackgroundColor(0xE6101915.toInt())
                if (tv.parent == null) {
                    val lp = WindowManager.LayoutParams(
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                        PixelFormat.TRANSLUCENT
                    )
                    lp.gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
                    lp.y = 220
                    wm.addView(tv, lp)
                }
                ui.removeCallbacks(hidePill)
                ui.postDelayed(hidePill, 2200L)
            } catch (_: Exception) { /* overlay optional; bounce already happened */ }
        }
    }

    private val hidePill = Runnable {
        try {
            pill?.let { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) }
        } catch (_: Exception) {}
        pill = null
    }

    override fun onInterrupt() {}

    // Service can be switched off while the pill is showing; don't leak the window.
    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        hidePill.run()
        super.onDestroy()
    }

    private companion object {
        const val CHANNEL = "hard_lock"
        const val NOTIF_ID = 1
    }
}
