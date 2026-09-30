package com.maatram.hardlock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Failsafe: fires at lock end and clears the lock. Time-based clear is the backstop. */
class LockEndReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        LockManager.clear(context)
    }
}
