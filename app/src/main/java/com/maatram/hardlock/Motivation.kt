package com.maatram.hardlock

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import java.util.Calendar

/**
 * Motivation reminders: a few times a day, a short line that nudges the user
 * to start a Hard Lock. Tapping "Lock 25 min" starts one straight away when the
 * Shield is ready; otherwise it opens the app to finish setup.
 */
object Motivation {
    private const val PREFS = "MaatramLock"
    private const val KEY_ON = "motivate_on"
    // New id: a channel's importance can't be raised once created, and these must pop up on screen.
    private const val CHANNEL = "motivation_popup"
    private const val NOTIF_ID = 2001
    const val ACTION_SHOW = "com.maatram.hardlock.MOTIVATE"
    const val EXTRA_QUICK_LOCK = "quick_lock"
    const val QUICK_MINUTES = 25

    // Five times a day (24h): morning, late morning, afternoon, after school, evening study.
    private val TIMES = listOf(8 to 0, 11 to 0, 14 to 0, 17 to 0, 20 to 30)

    // Maatram's 200 lines. Five a day, a different set each day, cycling every 40 days.
    val LINES = listOf(
        "Start before you feel ready.",
        "Small steps create big changes.",
        "Consistency beats intensity.",
        "Keep going. You're building something.",
        "Progress starts with one decision.",
        "Discipline creates freedom.",
        "Show up for yourself.",
        "One focused hour can change your day.",
        "Do it today, not someday.",
        "Your future needs today's effort.",
        "Keep the promise you made to yourself.",
        "Progress loves consistency.",
        "Make today count.",
        "Focus on the next step.",
        "Start small. Stay consistent.",
        "Effort compounds.",
        "Don't break the streak of effort.",
        "Keep moving forward.",
        "Discipline today, confidence tomorrow.",
        "Build habits that build you.",
        "Your actions shape your future.",
        "Keep showing up.",
        "Better every day.",
        "Make progress, not excuses.",
        "Stay committed to the process.",
        "One day at a time.",
        "Keep the momentum alive.",
        "Do the work quietly.",
        "Let consistency speak.",
        "Progress is still progress.",
        "Protect your attention.",
        "Focus is your superpower.",
        "One task. Full attention.",
        "Your attention is valuable.",
        "Choose focus over distraction.",
        "Deep work. Real progress.",
        "Less scrolling. More doing.",
        "Give your goal your attention.",
        "Focus on what matters.",
        "Distractions can wait.",
        "Be where your goals need you.",
        "Attention creates achievement.",
        "Clear mind. Clear direction.",
        "Put the phone down. Pick the goal up.",
        "Focus now. Relax later.",
        "Don't trade your goals for distractions.",
        "Your time deserves your attention.",
        "One focused session at a time.",
        "Make your attention count.",
        "Protect your productive hours.",
        "Silence the noise.",
        "Choose progress over notifications.",
        "Your goals need focus, not excuses.",
        "Stay locked in.",
        "Focus creates momentum.",
        "Control your attention.",
        "Give less to distractions, more to yourself.",
        "Your next breakthrough needs focus.",
        "Focus is a choice.",
        "Be intentional with your time.",
        "You haven't reached your limit.",
        "Learn. Adapt. Grow.",
        "Growth begins outside comfort.",
        "Become better than yesterday.",
        "Keep learning.",
        "Mistakes are part of progress.",
        "Challenge yourself.",
        "Growth takes time.",
        "Trust the process.",
        "Keep improving.",
        "Every attempt teaches you something.",
        "Progress begins with practice.",
        "Learn from today.",
        "Keep raising your standard.",
        "You are capable of more.",
        "Growth is built, not given.",
        "Practice makes progress.",
        "Keep pushing your boundaries.",
        "Every day is another opportunity.",
        "Improvement is always possible.",
        "Learn something. Build something.",
        "Turn effort into ability.",
        "Your potential grows with practice.",
        "Keep becoming.",
        "Progress starts where comfort ends.",
        "Don't fear the learning curve.",
        "Challenge creates growth.",
        "Stay curious.",
        "Keep developing your potential.",
        "Growth happens one choice at a time.",
        "Tough days build strong minds.",
        "Keep going when it's difficult.",
        "You can handle the next step.",
        "Don't quit on a hard day.",
        "Strength grows through challenges.",
        "Stay strong. Stay steady.",
        "Difficult doesn't mean impossible.",
        "Keep moving through the challenge.",
        "You are stronger than one bad day.",
        "Pressure can build resilience.",
        "Stand back up and continue.",
        "Hard work builds confidence.",
        "Keep your head up.",
        "Challenges don't define you.",
        "Be patient with your progress.",
        "Stay steady under pressure.",
        "Keep fighting for your goals.",
        "Strong habits create strong minds.",
        "Don't let one setback stop you.",
        "You can start again.",
        "Persistence changes outcomes.",
        "Keep your momentum.",
        "Stay patient. Stay persistent.",
        "Hard moments pass. Keep moving.",
        "Your effort matters.",
        "Keep going, even slowly.",
        "Resilience is built daily.",
        "Don't underestimate steady effort.",
        "Fall behind? Start again.",
        "Keep choosing progress.",
        "Think big. Start small.",
        "Your goals need action.",
        "Dream it. Build it.",
        "Make your future worth the effort.",
        "Chase progress, not perfection.",
        "Turn goals into habits.",
        "Your ambition needs consistency.",
        "Set the goal. Do the work.",
        "Build the future you imagine.",
        "Aim higher.",
        "Keep your eyes on the goal.",
        "Big goals start with small actions.",
        "Make your goals measurable.",
        "Work toward something meaningful.",
        "Your future is built today.",
        "Don't just dream it. Work for it.",
        "Give your goals a chance.",
        "Ambition starts with action.",
        "Keep moving toward the target.",
        "Your goals are worth your effort.",
        "Make your next move count.",
        "Build, don't just wish.",
        "Let your actions match your ambition.",
        "Keep the vision. Do the work.",
        "Progress toward something bigger.",
        "Make today part of the plan.",
        "Your destination starts with today's step.",
        "Work for the version of you you want to become.",
        "Stay hungry for improvement.",
        "Keep building your future.",
        "Study now. Thank yourself later.",
        "Your effort today becomes confidence tomorrow.",
        "Learn for your future, not just the exam.",
        "One chapter at a time.",
        "Your hard work will add up.",
        "Focus on understanding, not just finishing.",
        "Make your study time count.",
        "Keep learning. Keep growing.",
        "Your future self is watching.",
        "Don't underestimate one study session.",
        "Knowledge compounds too.",
        "Focus now. Results later.",
        "Study with purpose.",
        "Make progress before perfection.",
        "One page closer.",
        "One problem closer.",
        "One session closer.",
        "Your goals deserve your best effort.",
        "Learn today. Lead tomorrow.",
        "Build skills, not just marks.",
        "Stay curious. Stay focused.",
        "Every lesson adds up.",
        "Your preparation creates confidence.",
        "Don't wait for motivation. Start.",
        "Make your study hour powerful.",
        "Lock in.",
        "Keep moving.",
        "Stay focused.",
        "Start now.",
        "Keep building.",
        "Make it count.",
        "Stay consistent.",
        "Trust yourself.",
        "Keep improving.",
        "Choose progress.",
        "Stay disciplined.",
        "Keep pushing.",
        "Don't settle.",
        "Go further.",
        "Stay hungry.",
        "Think forward.",
        "Keep learning.",
        "Take control.",
        "Make progress.",
        "Stay on track.",
        "Do the work.",
        "Own your time.",
        "Build your future.",
        "Keep becoming.",
        "Your time. Your choice. Your Maatram."
    )

    fun isOn(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ON, true)

    fun setOn(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ON, on).apply()
        if (on) scheduleNext(ctx) else cancel(ctx)
    }

    private fun alarmIntent(ctx: Context): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, 2002,
            Intent(ctx, MotivationReceiver::class.java).setAction(ACTION_SHOW),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /** Arms the next reminder. Inexact on purpose: no exact-alarm permission needed, kind to battery. */
    fun scheduleNext(ctx: Context) {
        if (!isOn(ctx)) return
        val now = Calendar.getInstance()
        val next = TIMES.map { (h, m) ->
            Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                if (!after(now)) add(Calendar.DAY_OF_YEAR, 1)
            }
        }.minByOrNull { it.timeInMillis } ?: return
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        // Fires even in Doze, within a few minutes of the time. No exact-alarm permission needed.
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.timeInMillis, alarmIntent(ctx))
    }

    private fun cancel(ctx: Context) {
        (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(alarmIntent(ctx))
        ctx.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
    }

    /** Posts one reminder, unless a lock is already running. */
    fun show(ctx: Context) {
        if (!isOn(ctx) || LockManager.isLocked(ctx)) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 33 && !nm.areNotificationsEnabled()) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Daily motivation", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Five short lines a day, shown as a pop-up on your screen" }
        )
        // Which of today's five: the slot nearest the current time. Day number picks the set.
        val cal = Calendar.getInstance()
        val nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val slot = TIMES.indices.minByOrNull { kotlin.math.abs(TIMES[it].first * 60 + TIMES[it].second - nowMin) } ?: 0
        val day = (System.currentTimeMillis() + java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis())) / 86_400_000L
        val line = LINES[((day * TIMES.size + slot) % LINES.size).toInt()]
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = PendingIntent.getActivity(
            ctx, 2003,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            flags
        )
        // Opens the app directly (no receiver trampoline, which Android 12+ blocks).
        val lock = PendingIntent.getActivity(
            ctx, 2004,
            Intent(ctx, MainActivity::class.java)
                .putExtra(EXTRA_QUICK_LOCK, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            flags
        )
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Maatram")
            .setPriority(Notification.PRIORITY_HIGH)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setContentText(line)
            .setStyle(Notification.BigTextStyle().bigText(line))
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(Notification.Action.Builder(Icon.createWithResource(ctx, R.drawable.ic_launcher_foreground), "Lock $QUICK_MINUTES min", lock).build())
            .build()
        nm.notify(NOTIF_ID, n)
    }

    /** Called by MainActivity for "Lock 25 min": starts only if the Shield can actually block,
     *  otherwise the app simply opens on the setup screen. */
    fun handleIntent(ctx: Context, intent: Intent?) {
        if (intent == null || !intent.getBooleanExtra(EXTRA_QUICK_LOCK, false)) return
        intent.removeExtra(EXTRA_QUICK_LOCK)
        ctx.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
        if (LockManager.canLock(ctx) && !LockManager.isLocked(ctx)) LockManager.start(ctx, QUICK_MINUTES)
    }
}

class MotivationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Motivation.ACTION_SHOW) { Motivation.show(context); Motivation.scheduleNext(context) }
    }
}
