package com.maatram.hardlock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val a = intent?.action
        if (a != Intent.ACTION_BOOT_COMPLETED && a != Intent.ACTION_MY_PACKAGE_REPLACED) return
        // Lock end time is stored; re-arm the end alarm or clear if already past.
        LockManager.reconcileAfterBoot(context)
        Motivation.scheduleNext(context)
        LockSchedule.arm(context)
    }
}
