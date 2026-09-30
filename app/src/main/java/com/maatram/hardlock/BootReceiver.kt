package com.maatram.hardlock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        // Lock end time is stored; re-arm the end alarm or clear if already past.
        LockManager.reconcileAfterBoot(context)
    }
}
