package com.maatram.hardlock

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.TextView

/**
 * The blocker. On every window change, if a lock is running and the foreground
 * app is on the block/guard list, it snaps home immediately and shows a small
 * "Locked" pill. Going home is instant, so there is no perceptible lag.
 */
class BlockerService : AccessibilityService() {

    private val ui = Handler(Looper.getMainLooper())
    private var pill: TextView? = null
    private var lastPillAt = 0L

    override fun onServiceConnected() {
        // Configure programmatically too — some OEMs ignore the XML.
        serviceInfo = AccessibilityServiceInfo().apply {
            // App switches only; TYPE_WINDOWS_CHANGED fired constantly for no gain.
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            notificationTimeout = 0L
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!LockManager.isLocked(this)) return     // cheapest check first
        val pkg = event?.packageName?.toString() ?: return
        if (pkg == packageName) return
        if (!LockManager.isBlocked(this, pkg)) return

        // Instant bounce home.
        performGlobalAction(GLOBAL_ACTION_HOME)
        showPill()
    }

    private fun showPill() {
        val now = System.currentTimeMillis()
        if (now - lastPillAt < 900L) return          // throttle, keep it cheap
        lastPillAt = now

        val minsLeft = ((LockManager.remainingMs(this) + 59_999L) / 60_000L).toInt()
        ui.post {
            try {
                val wm = getSystemService(WINDOW_SERVICE) as WindowManager
                val tv = pill ?: TextView(this).also { pill = it }
                tv.text = "🔒  Locked · $minsLeft min left"
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
}
