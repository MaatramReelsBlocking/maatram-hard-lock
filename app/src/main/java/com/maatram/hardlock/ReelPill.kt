package com.maatram.hardlock

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.graphics.Typeface

/**
 * Floating pill at the top centre of the screen (camera area): Maatram mascot + reel count.
 * Drawn as an accessibility overlay, so no "display over other apps" permission is needed.
 * Tap it to open Maatram. Colors match the site: dark #0B0C0B, neon green #2FE38F.
 */
object ReelPill {
    private const val GREEN = 0xFF2FE38F.toInt()
    private const val DARK = 0xE60B0C0B.toInt()

    private var view: View? = null
    private var count: TextView? = null
    private var wm: WindowManager? = null

    fun show(ctx: Context, n: Int) {
        if (view != null) { update(n); return }
        try {
            // Service context, not applicationContext: accessibility overlays must be added from the service's window manager.
            val app = ctx
            fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), app.resources.displayMetrics).toInt()
            val icon = ImageView(app).apply {
                setImageResource(R.drawable.ic_reel_mascot)
            }
            val text = TextView(app).apply {
                text = n.toString()
                setTextColor(GREEN)
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
            }
            val pill = LinearLayout(app).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(4), dp(14), dp(4))
                background = GradientDrawable().apply {
                    cornerRadius = dp(100).toFloat()
                    setColor(DARK)
                    setStroke(dp(1), GREEN)
                }
                addView(icon, LinearLayout.LayoutParams(dp(26), dp(26)))
                addView(text, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6) })
                setOnClickListener { ReelTracker.openApp(app) }
            }
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = dp(6)
            }
            val manager = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            manager.addView(pill, lp)
            view = pill
            count = text
            wm = manager
        } catch (e: Exception) {
            // Overlay is optional (blocking and counting still work), but log it so a failure is visible.
            android.util.Log.w("ReelPill", "pill not shown", e)
        }
    }

    fun update(n: Int) {
        count?.text = n.toString()
    }

    fun hide() {
        try {
            view?.let { wm?.removeView(it) }
        } catch (_: Exception) {}
        view = null
        count = null
    }
}
