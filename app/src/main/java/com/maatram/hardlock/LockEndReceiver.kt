package com.maatram.hardlock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Failsafe: fires at lock end and clears the lock. Time-based clear is the backstop. */
class LockEndReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        // Only announce a real finish (a lock was set and its time is up).
        val end = LockManager.endTime(context)
        val finished = end > 0L && end <= System.currentTimeMillis() + 5_000L
        LockManager.clear(context)
        Garden.settle(context)
        PlantWidget.refresh(context)
        if (finished) LockEvents.ended(context)
    }
}
